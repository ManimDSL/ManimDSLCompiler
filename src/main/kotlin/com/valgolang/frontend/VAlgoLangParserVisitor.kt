package com.valgolang.frontend

import antlr.VAlgoLangParser.*
import antlr.VAlgoLangParserBaseVisitor
import com.valgolang.errorhandling.semanticerror.incompatibleOperatorTypeError
import com.valgolang.frontend.ast.*
import com.valgolang.frontend.datastructures.*
import com.valgolang.frontend.datastructures.array.Array2DInitialiserNode
import com.valgolang.frontend.datastructures.array.ArrayElemNode
import com.valgolang.frontend.datastructures.array.ArrayType
import com.valgolang.frontend.datastructures.array.InternalArrayMethodCallNode
import com.valgolang.frontend.datastructures.binarytree.*
import com.valgolang.frontend.datastructures.list.ListType
import com.valgolang.frontend.datastructures.stack.StackType
import java.util.*

/**
 * VAlgoLang parser visitor
 *
 * This class implements the VAlgoLangParserBaseVisitor abstract class returning an abstract syntax tree(AST).
 * Here it traverses the concrete syntax tree (parse tree) to perform semantic analysis,
 * construct the line to statement node map (lineNumberNodeMap) and the AST.
 *
 * @constructor Create empty V algo lang parser visitor
 */
class VAlgoLangParserVisitor : VAlgoLangParserBaseVisitor<ASTNode>() {
    val symbolTable = SymbolTableVisitor()

    val lineNumberNodeMap = mutableMapOf<Int, StatementNode>()

    private val semanticAnalyser = SemanticAnalysis()
    private var inFunction: Boolean = false

    // First value in pair is loop start line number and second is end line number
    private val loopLineNumberStack: Stack<Pair<Int, Int>> = Stack()

    // Special set of identifiers that cannot be reassigned within loop
    private val forLoopIdentifiers = hashSetOf<String>()

    private var functionReturnType: Type = VoidType

    override fun visitProgram(ctx: ProgramContext): ProgramNode {
        val functions = ctx.function().map { visit(it) as FunctionNode }
        semanticAnalyser.tooManyInferredFunctionsCheck(symbolTable, ctx)
        return ProgramNode(
            functions,
            flattenStatements(visit(ctx.stat()) as StatementNode)
        )
    }

    override fun visitFunction(ctx: FunctionContext): FunctionNode {
        inFunction = true
        val identifier = ctx.IDENT().symbol.text
        val type = if (ctx.type() != null) {
            visit(ctx.type()) as Type
        } else {
            VoidType
        }
        functionReturnType = type

        val currentScope = symbolTable.getCurrentScopeID()
        val scope = symbolTable.enterScope()
        val parameters: List<ParameterNode> =
            visitParameterList(ctx.param_list() as ParameterListContext?).parameters

        symbolTable.goToScope(currentScope)

        // Define function symbol in parent scope
        semanticAnalyser.redeclaredFunctionCheck(symbolTable, identifier, type, parameters, ctx)
        symbolTable.addVariable(
            identifier,
            FunctionData(inferred = false, firstTime = false, parameters = parameters, type = type)
        )

        symbolTable.goToScope(scope)

        val statements = visitAndFlattenStatements(ctx.statements)
        symbolTable.leaveScope()

        if (functionReturnType !is VoidType) {
            semanticAnalyser.missingReturnCheck(identifier, statements, functionReturnType, ctx)
        }

        inFunction = false
        functionReturnType = VoidType
        lineNumberNodeMap[ctx.start.line] = FunctionNode(ctx.start.line, scope, identifier, parameters, statements)
        return lineNumberNodeMap[ctx.start.line] as FunctionNode
    }

    override fun visitParameterList(ctx: ParameterListContext?): ParameterListNode {
        if (ctx == null) {
            return ParameterListNode(listOf())
        }
        return ParameterListNode(ctx.param().map { visit(it) as ParameterNode })
    }

    override fun visitParameter(ctx: ParameterContext): ParameterNode {
        val identifier = ctx.IDENT().symbol.text
        semanticAnalyser.redeclaredVariableCheck(symbolTable, identifier, ctx)

        val type = visit(ctx.type()) as Type
        symbolTable.addVariable(identifier, IdentifierData(type))
        return ParameterNode(identifier, type)
    }

    override fun visitReturnStatement(ctx: ReturnStatementContext): ReturnNode {
        semanticAnalyser.globalReturnCheck(inFunction, ctx)
        val expression = if (ctx.expr() != null) visit(ctx.expr()) as ExpressionNode else VoidNode(ctx.start.line)
        semanticAnalyser.incompatibleReturnTypesCheck(symbolTable, functionReturnType, expression, ctx)
        lineNumberNodeMap[ctx.start.line] = ReturnNode(ctx.start.line, expression)
        return lineNumberNodeMap[ctx.start.line] as ReturnNode
    }

    override fun visitCastExpression(ctx: CastExpressionContext): ASTNode {
        val expr = visit(ctx.expr()) as ExpressionNode

        val expectedTypes = when (ctx.cast_method()) {
            is ToCharacterContext -> setOf(NumberType, CharType)
            is ToNumberContext -> setOf(NumberType, CharType, StringType)
            else -> throw UnsupportedOperationException("Not implemented")
        }

        semanticAnalyser.checkExpressionTypeWithExpectedTypes(expr, expectedTypes, symbolTable, ctx)

        val targetType = when (ctx.cast_method()) {
            is ToCharacterContext -> CharType
            is ToNumberContext -> NumberType
            else -> throw UnsupportedOperationException("Not implemented")
        }

        return CastExpressionNode(ctx.start.line, targetType, expr)
    }

    override fun visitBracketedExpression(ctx: BracketedExpressionContext): ExpressionNode {
        return visit(ctx.expr()) as ExpressionNode
    }

    /** Statements **/

    override fun visitSleepStatement(ctx: SleepStatementContext): SleepNode {
        lineNumberNodeMap[ctx.start.line] = SleepNode(ctx.start.line, visit(ctx.expr()) as ExpressionNode)
        return lineNumberNodeMap[ctx.start.line] as SleepNode
    }

    private fun visitAndFlattenStatements(statementContext: StatContext?): List<StatementNode> {
        return if (statementContext != null) {
            flattenStatements(visit(statementContext) as StatementNode)
        } else {
            emptyList()
        }
    }

    private fun flattenStatements(statement: StatementNode): List<StatementNode> {
        if (statement is CodeTrackingNode) {
            return statement.statements
        }

        val statements = mutableListOf<StatementNode>()
        var statementNode = statement
        // Flatten consecutive statements
        while (statementNode is ConsecutiveStatementNode) {
            statements.addAll(flattenStatements(statementNode.stat1))
            statementNode = statementNode.stat2
        }
        statements.add(statementNode)
        return statements
    }

    override fun visitConsecutiveStatement(ctx: ConsecutiveStatementContext): ASTNode {
        return ConsecutiveStatementNode(visit(ctx.stat1) as StatementNode, visit(ctx.stat2) as StatementNode)
    }

    override fun visitDeclarationStatement(ctx: DeclarationStatementContext): DeclarationNode {
        val identifier = ctx.IDENT().symbol.text
        semanticAnalyser.redeclaredVariableCheck(symbolTable, identifier, ctx)

        val rhs = visit(ctx.expr()) as ExpressionNode

        var rhsType = semanticAnalyser.inferType(symbolTable, rhs)
        val lhsType = if (ctx.type() != null) {
            visit(ctx.type()) as Type
        } else {
            semanticAnalyser.unableToInferTypeCheck(rhsType, ctx)
            rhsType
        }

        if (rhs is FunctionCallNode && symbolTable.getTypeOf(rhs.functionIdentifier) != ErrorType) {
            val functionData = symbolTable.getData(rhs.functionIdentifier) as FunctionData
            semanticAnalyser.incompatibleMultipleFunctionCall(rhs.functionIdentifier, functionData, lhsType, ctx)
            if (functionData.inferred && functionData.firstTime) {
                functionData.type = lhsType
                rhsType = lhsType
                functionData.firstTime = false
            }
        }

        semanticAnalyser.voidTypeDeclarationCheck(rhsType, identifier, ctx)
        semanticAnalyser.incompatibleTypesCheck(lhsType, rhsType, identifier, ctx)
        if (rhsType is NullType && lhsType is DataStructureType) {
            rhsType = lhsType
        }
        symbolTable.addVariable(identifier, IdentifierData(rhsType))
        lineNumberNodeMap[ctx.start.line] =
            DeclarationNode(ctx.start.line, IdentifierNode(ctx.start.line, identifier), rhs)
        return lineNumberNodeMap[ctx.start.line] as DeclarationNode
    }

    override fun visitAssignmentStatement(ctx: AssignmentStatementContext): AssignmentNode {
        val expression = visit(ctx.expr()) as ExpressionNode
        val (lhsType, lhs) = visitAssignLHS(ctx.assignment_lhs())
        var rhsType = semanticAnalyser.inferType(symbolTable, expression)

        if (expression is FunctionCallNode && symbolTable.getTypeOf(expression.functionIdentifier) != ErrorType) {
            val functionData = symbolTable.getData(expression.functionIdentifier) as FunctionData
            if (functionData.inferred) {
                functionData.type = lhsType
                rhsType = lhsType
            }
        }

        semanticAnalyser.incompatibleTypesCheck(lhsType, rhsType, lhs.toString(), ctx)
        lineNumberNodeMap[ctx.start.line] = AssignmentNode(ctx.start.line, lhs, expression)
        return lineNumberNodeMap[ctx.start.line] as AssignmentNode
    }

    private fun visitAssignLHS(ctx: Assignment_lhsContext): Pair<Type, AssignLHS> {
        return when (ctx) {
            is IdentifierAssignmentContext -> visitIdentifierAssignmentLHS(ctx)
            is ArrayElemAssignmentContext -> visitArrayAssignmentLHS(ctx)
            is NodeElemAssignmentContext -> visitNodeAssignmentLHS(ctx)
            else -> Pair(ErrorType, EmptyLHS)
        }
    }

    private fun visitIdentifierAssignmentLHS(ctx: IdentifierAssignmentContext): Pair<Type, AssignLHS> {
        val identifier = ctx.IDENT().symbol.text

        semanticAnalyser.undeclaredIdentifierCheck(symbolTable, identifier, ctx)
        semanticAnalyser.forLoopIdentifierNotBeingReassignedCheck(identifier, forLoopIdentifiers, ctx)

        return Pair(symbolTable.getTypeOf(identifier), IdentifierNode(ctx.start.line, identifier))
    }

    private fun visitArrayAssignmentLHS(ctx: ArrayElemAssignmentContext): Pair<Type, AssignLHS> {
        val arrayElem = visit(ctx.array_elem()) as ArrayElemNode
        val arrayType = symbolTable.getTypeOf(arrayElem.identifier)

        semanticAnalyser.invalidArrayElemAssignment(arrayElem.identifier, arrayType, ctx)

        // Return element type
        return if (arrayType is ArrayType) {
            Pair(
                if (arrayType.is2D && arrayElem.indices.size == 1) ArrayType(arrayType.internalType) else arrayType.internalType,
                arrayElem
            )
        } else {
            Pair(ErrorType, EmptyLHS)
        }
    }

    private fun visitNodeAssignmentLHS(ctx: NodeElemAssignmentContext): Pair<Type, AssignLHS> {
        val nodeElem =
            visit(ctx.node_elem()) as BinaryTreeNodeAccess
        val treeType = semanticAnalyser.inferType(symbolTable, nodeElem)

        return if (treeType is ErrorType) {
            Pair(ErrorType, EmptyLHS)
        } else {
            Pair(treeType, nodeElem as AssignLHS)
        }
    }

    override fun visitMethodCallStatement(ctx: MethodCallStatementContext): ASTNode {
        return visit(ctx.method_call())
    }

    override fun visitWhileStatement(ctx: WhileStatementContext): ASTNode {
        val whileScope = symbolTable.enterScope()
        val whileCondition = visit(ctx.whileCond) as ExpressionNode
        semanticAnalyser.checkExpressionTypeWithExpectedType(whileCondition, BoolType, symbolTable, ctx)
        val startLineNumber = ctx.start.line
        val endLineNumber = ctx.stop.line

        loopLineNumberStack.push(Pair(startLineNumber, endLineNumber))
        val whileStatements = visitAndFlattenStatements(ctx.whileStat)
        whileStatements.forEach {
            lineNumberNodeMap[it.lineNumber] = it
        }
        symbolTable.leaveScope()
        loopLineNumberStack.pop()

        val whileStatementNode =
            WhileStatementNode(startLineNumber, endLineNumber, whileScope, whileCondition, whileStatements)
        lineNumberNodeMap[ctx.start.line] = whileStatementNode
        return whileStatementNode
    }

    private fun visitForHeader(ctx: ForHeaderContext): Triple<DeclarationNode, ExpressionNode, AssignmentNode> {
        return visitRange(ctx as RangeHeaderContext)
    }

    private fun visitRange(ctx: RangeHeaderContext): Triple<DeclarationNode, ExpressionNode, AssignmentNode> {
        val lineNumber = ctx.start.line
        val identifier = ctx.IDENT().symbol.text
        semanticAnalyser.redeclaredVariableCheck(symbolTable, identifier, ctx)
        forLoopIdentifiers.add(identifier)
        val startExpr = if (ctx.begin != null) {
            visit(ctx.begin) as ExpressionNode
        } else {
            NumberNode(lineNumber, 0.0)
        }
        val end = visit(ctx.end) as ExpressionNode
        val change = if (ctx.delta != null) {
            visit(ctx.delta) as ExpressionNode
        } else {
            NumberNode(lineNumber, 1.0)
        }
        semanticAnalyser.forLoopRangeUpdateNumberTypeCheck(symbolTable, change, ctx)
        semanticAnalyser.forLoopRangeTypeCheck(symbolTable, startExpr, end, ctx)

        val start = DeclarationNode(lineNumber, IdentifierNode(lineNumber, identifier), startExpr)
        val counterType = semanticAnalyser.inferType(symbolTable, startExpr)
        val update = if (counterType is CharType) {
            AssignmentNode(
                lineNumber,
                IdentifierNode(lineNumber, identifier),
                CastExpressionNode(
                    lineNumber,
                    CharType,
                    AddExpression(
                        lineNumber,
                        IdentifierNode(lineNumber, identifier),
                        change
                    )
                )
            )
        } else {
            AssignmentNode(
                lineNumber,
                IdentifierNode(lineNumber, identifier),
                AddExpression(
                    lineNumber,
                    IdentifierNode(lineNumber, identifier),
                    change
                )
            )
        }
        symbolTable.addVariable(identifier, IdentifierData(counterType))
        return Triple(start, end, update)
    }

    override fun visitForStatement(ctx: ForStatementContext): ASTNode {

        val forScope = symbolTable.enterScope()

        val (start, end, update) = visitForHeader(ctx.forHeader())
        val startLineNumber = ctx.start.line
        val endLineNumber = ctx.stop.line

        loopLineNumberStack.push(Pair(startLineNumber, endLineNumber))
        val forStatements = visitAndFlattenStatements(ctx.forStat)
        forStatements.forEach {
            lineNumberNodeMap[it.lineNumber] = it
        }

        symbolTable.leaveScope()
        loopLineNumberStack.pop()
        forLoopIdentifiers.remove(start.identifier.identifier)
        val forStatementNode =
            ForStatementNode(startLineNumber, endLineNumber, forScope, start, end, update, forStatements)
        lineNumberNodeMap[ctx.start.line] = forStatementNode
        return forStatementNode
    }

    override fun visitIfStatement(ctx: IfStatementContext): ASTNode {
        // if
        val ifScope = symbolTable.enterScope()
        val ifCondition = visit(ctx.ifCond) as ExpressionNode
        semanticAnalyser.checkExpressionTypeWithExpectedType(ifCondition, BoolType, symbolTable, ctx)
        val ifStatements = visitAndFlattenStatements(ctx.ifStat)
        ifStatements.forEach {
            lineNumberNodeMap[it.lineNumber] = it
        }
        symbolTable.leaveScope()

        // elif
        val elifs = ctx.elseIf().map { visit(it) as ElifNode }

        // else
        val elseNode = if (ctx.elseStat != null) {
            val scope = symbolTable.enterScope()
            val statements = visitAndFlattenStatements(ctx.elseStat)
            statements.forEach {
                lineNumberNodeMap[it.lineNumber] = it
            }
            symbolTable.leaveScope()
            ElseNode(ctx.ELSE().symbol.line, scope, statements)
        } else {
            ElseNode(ctx.stop.line, 0, emptyList())
        }

        ctx.ELSE()?.let { lineNumberNodeMap[it.symbol.line] = elseNode }

        val ifStatementNode =
            IfStatementNode(ctx.start.line, ctx.stop.line, ifScope, ifCondition, ifStatements, elifs, elseNode)
        lineNumberNodeMap[ctx.start.line] = ifStatementNode
        return ifStatementNode
    }

    override fun visitElseIf(ctx: ElseIfContext): ASTNode {
        val elifScope = symbolTable.enterScope()

        val elifCondition = visit(ctx.elifCond) as ExpressionNode
        semanticAnalyser.checkExpressionTypeWithExpectedType(elifCondition, BoolType, symbolTable, ctx)
        val elifStatements = visitAndFlattenStatements(ctx.elifStat)

        elifStatements.forEach {
            lineNumberNodeMap[it.lineNumber] = it
        }
        symbolTable.leaveScope()

        val elifNode = ElifNode(ctx.elifCond.start.line, elifScope, elifCondition, elifStatements)
        lineNumberNodeMap[ctx.elifCond.start.line] = elifNode
        return elifNode
    }

    override fun visitLoopStatement(ctx: LoopStatementContext): LoopStatementNode {
        return visit(ctx.loop_stat()) as LoopStatementNode
    }

    override fun visitBreakStatement(ctx: BreakStatementContext): BreakNode {
        val inLoop = loopLineNumberStack.isNotEmpty()
        val endLineNumber = if (inLoop) loopLineNumberStack.peek().second else -1
        semanticAnalyser.breakOrContinueOutsideLoopCheck("break", inLoop, ctx)
        return BreakNode(ctx.start.line, endLineNumber)
    }

    override fun visitContinueStatement(ctx: ContinueStatementContext): ContinueNode {
        val inLoop = loopLineNumberStack.isNotEmpty()
        val startLineNumber = if (inLoop) loopLineNumberStack.peek().first else -1
        semanticAnalyser.breakOrContinueOutsideLoopCheck("continue", inLoop, ctx)
        return ContinueNode(ctx.start.line, startLineNumber)
    }

    /** Annotations **/

    override fun visitCodeTrackingAnnotation(ctx: CodeTrackingAnnotationContext): CodeTrackingNode {
        val isStepInto = ctx.step.type == STEP_INTO

        val condition = if (ctx.condition == null) {
            BoolNode(ctx.start.line, true)
        } else {
            val definedCondition = visit(ctx.condition) as ExpressionNode
            semanticAnalyser.checkExpressionTypeWithExpectedType(definedCondition, BoolType, symbolTable, ctx)
            definedCondition
        }

        val statements = mutableListOf<StatementNode>()

        val start = StartCodeTrackingNode(ctx.start.line, isStepInto, condition)
        val end = StopCodeTrackingNode(ctx.stop.line, isStepInto, condition)

        statements.add(start)
        statements.addAll(visitAndFlattenStatements(ctx.stat()))
        statements.add(end)

        lineNumberNodeMap[ctx.start.line] = start
        lineNumberNodeMap[ctx.stop.line] = end

        return CodeTrackingNode(ctx.start.line, ctx.stop.line, statements)
    }

    override fun visitAnimationSpeedUpAnnotation(ctx: AnimationSpeedUpAnnotationContext): CodeTrackingNode {
        val arguments = (visit(ctx.arg_list()) as ArgumentNode).arguments
        semanticAnalyser.checkAnnotationArguments(ctx, symbolTable, arguments)

        val speedChange = arguments.first()

        val condition = if (arguments.size == 2) {
            arguments[1]
        } else {
            BoolNode(ctx.start.line, true)
        }

        val statements = mutableListOf<StatementNode>()

        val start = StartSpeedChangeNode(ctx.start.line, speedChange, condition)
        val end = StopSpeedChangeNode(ctx.stop.line, condition)

        statements.add(start)
        statements.addAll(visitAndFlattenStatements(ctx.stat()))
        statements.add(end)

        lineNumberNodeMap[ctx.start.line] = start
        lineNumberNodeMap[ctx.stop.line] = end

        return CodeTrackingNode(ctx.start.line, ctx.stop.line, statements)
    }

    override fun visitSubtitleAnnotation(ctx: SubtitleAnnotationContext): SubtitleAnnotationNode {
        val text = visit(ctx.subtitle_text) as ExpressionNode

        semanticAnalyser.checkExpressionTypeWithExpectedType(text, StringType, symbolTable, ctx)

        var condition: ExpressionNode = BoolNode(ctx.start.line, true)
        var duration: ExpressionNode? = null
        if (ctx.arg_list() != null) {
            val args = visit(ctx.arg_list()) as ArgumentNode
            val argTypes = semanticAnalyser.checkAnnotationArguments(ctx, symbolTable, args.arguments)
            if (argTypes.contains(BoolType)) condition = args.arguments[argTypes.indexOf(BoolType)]
            if (argTypes.contains(NumberType)) duration = args.arguments[argTypes.indexOf(NumberType)]
        }

        val node = SubtitleAnnotationNode(ctx.start.line, text, duration, condition, ctx.show.type == SUBTITLE_ONCE)
        lineNumberNodeMap[ctx.start.line] = node
        return node
    }

    /** Expressions **/

    override fun visitMethodCallExpression(ctx: MethodCallExpressionContext): ASTNode {
        return visit(ctx.method_call())
    }

    override fun visitArgumentList(ctx: ArgumentListContext?): ArgumentNode {
        return ArgumentNode(
            (ctx?.expr() ?: listOf<ExprContext>()).map { visit(it) as ExpressionNode }
        )
    }

    override fun visitMethodCall(ctx: MethodCallContext): ExpressionNode {
        // Type signature of methods to be determined by symbol table
        val arguments: List<ExpressionNode> =
            visitArgumentList(ctx.arg_list() as ArgumentListContext?).arguments
        val identifier = ctx.IDENT(0).symbol.text
        val methodName = ctx.IDENT(1).symbol.text

        semanticAnalyser.undeclaredIdentifierCheck(symbolTable, identifier, ctx)
        semanticAnalyser.notDataStructureCheck(symbolTable, identifier, ctx)
        semanticAnalyser.notValidMethodNameForDataStructureCheck(symbolTable, identifier, methodName, ctx)

        var dataStructureType = symbolTable.getTypeOf(identifier)

        val index = if (ctx.expr() != null) {
            // array indexed method call
            val indexExpression = visit(ctx.expr()) as ExpressionNode
            semanticAnalyser.checkExpressionTypeWithExpectedType(indexExpression, NumberType, symbolTable, ctx)
            // check datastructure is a 2d array
            if (dataStructureType is ArrayType) dataStructureType = ArrayType(dataStructureType.internalType)
            indexExpression
        } else {
            null
        }

        val dataStructureMethod = if (dataStructureType is DataStructureType) {
            val method = dataStructureType.getMethodByName(methodName)
            // Assume for now we only have one type inside the data structure and data structure functions only deal with this type
            val argTypes = arguments.map { semanticAnalyser.inferType(symbolTable, it) }.toList()
            semanticAnalyser.primitiveArgTypesCheck(argTypes, methodName, dataStructureType, ctx)
            semanticAnalyser.incompatibleArgumentTypesCheck(
                dataStructureType,
                argTypes,
                method,
                ctx
            )
            method
        } else {
            ErrorMethod
        }

        val methodCallNode = if (index == null) {
            MethodCallNode(ctx.start.line, ctx.IDENT(0).symbol.text, dataStructureMethod, arguments)
        } else {
            InternalArrayMethodCallNode(ctx.start.line, index, ctx.IDENT(0).symbol.text, dataStructureMethod, arguments)
        }
        lineNumberNodeMap[ctx.start.line] =
            methodCallNode
        return methodCallNode
    }

    override fun visitFunctionCall(ctx: FunctionCallContext): FunctionCallNode {
        val identifier = ctx.IDENT().symbol.text
        val arguments: List<ExpressionNode> =
            visitArgumentList(ctx.arg_list() as ArgumentListContext?).arguments
        val argTypes = arguments.map { semanticAnalyser.inferType(symbolTable, it) }.toList()

        semanticAnalyser.undeclaredFunctionCheck(symbolTable, identifier, inFunction, argTypes, ctx)
        semanticAnalyser.invalidNumberOfArgumentsForFunctionsCheck(identifier, symbolTable, arguments.size, ctx)
        semanticAnalyser.incompatibleArgumentTypesForFunctionsCheck(identifier, symbolTable, argTypes, ctx)

        val functionCallNode = FunctionCallNode(ctx.start.line, ctx.IDENT().symbol.text, arguments)

        lineNumberNodeMap[ctx.start.line] = functionCallNode

        return functionCallNode
    }

    override fun visitDataStructureConstructor(ctx: DataStructureConstructorContext): ASTNode {
        val dataStructureType = visit(ctx.data_structure_type()) as DataStructureType

        // Check arguments
        val (arguments, argumentTypes) = if (ctx.arg_list() != null) {
            val argExpressions = (visit(ctx.arg_list()) as ArgumentNode).arguments
            Pair(argExpressions, argExpressions.map { semanticAnalyser.inferType(symbolTable, it) })
        } else {
            Pair(emptyList(), emptyList())
        }

        semanticAnalyser.checkArrayDimensionsMatchConstructorArguments(dataStructureType, arguments.size, ctx)

        val initialiser = if (ctx.data_structure_initialiser() != null) {
            visit(ctx.data_structure_initialiser()) as InitialiserNode
        } else {
            EmptyInitialiserNode
        }

        semanticAnalyser.incompatibleInitialiserCheck(dataStructureType, initialiser, ctx)

        val initialValues = when (initialiser) {
            is DataStructureInitialiserNode -> {
                initialiser.expressions
            }
            is Array2DInitialiserNode -> {
                initialiser.nestedExpressions.flatten()
            }
            else -> {
                emptyList()
            }
        }

        semanticAnalyser.allExpressionsAreSameTypeCheck(dataStructureType.internalType, initialValues, symbolTable, ctx)
        semanticAnalyser.datastructureConstructorCheck(dataStructureType, initialValues, argumentTypes, ctx)
        semanticAnalyser.array2DDimensionsMatchCheck(initialiser, dataStructureType, ctx)
        return ConstructorNode(ctx.start.line, dataStructureType, arguments, initialiser)
    }

    override fun visitIdentifier(ctx: IdentifierContext): IdentifierNode {
        semanticAnalyser.undeclaredIdentifierCheck(symbolTable, ctx.text, ctx)
        return IdentifierNode(ctx.start.line, ctx.text)
    }

    override fun visitBinaryExpression(ctx: BinaryExpressionContext): BinaryExpression {
        val expr1 = visit(ctx.left) as ExpressionNode
        val expr2 = visit(ctx.right) as ExpressionNode
        if (expr1 is IdentifierNode) {
            semanticAnalyser.undeclaredIdentifierCheck(symbolTable, expr1.identifier, ctx)
        }
        if (expr2 is IdentifierNode) {
            semanticAnalyser.undeclaredIdentifierCheck(symbolTable, expr2.identifier, ctx)
        }

        val binaryOpExpr = when (ctx.binary_operator.type) {
            ADD -> AddExpression(ctx.start.line, expr1, expr2)
            MINUS -> SubtractExpression(ctx.start.line, expr1, expr2)
            TIMES -> MultiplyExpression(ctx.start.line, expr1, expr2)
            DIVIDE -> DivideExpression(ctx.start.line, expr1, expr2)
            AND -> AndExpression(ctx.start.line, expr1, expr2)
            OR -> OrExpression(ctx.start.line, expr1, expr2)
            EQ -> EqExpression(ctx.start.line, expr1, expr2)
            NEQ -> NeqExpression(ctx.start.line, expr1, expr2)
            GT -> GtExpression(ctx.start.line, expr1, expr2)
            GE -> GeExpression(ctx.start.line, expr1, expr2)
            LT -> LtExpression(ctx.start.line, expr1, expr2)
            LE -> LeExpression(ctx.start.line, expr1, expr2)
            else -> throw UnsupportedOperationException("Operation not supported")
        }

        semanticAnalyser.incompatibleOperatorTypeCheck(ctx.binary_operator.text, binaryOpExpr, symbolTable, ctx)

        return binaryOpExpr
    }

    override fun visitUnaryOperator(ctx: UnaryOperatorContext): UnaryExpression {
        val expr = visit(ctx.expr()) as ExpressionNode
        if (expr is IdentifierNode) {
            semanticAnalyser.undeclaredIdentifierCheck(symbolTable, expr.identifier, ctx)
        }
        val unaryOpExpr = when (ctx.unary_operator.type) {
            ADD -> PlusExpression(ctx.start.line, expr)
            MINUS -> MinusExpression(ctx.start.line, expr)
            NOT -> NotExpression(ctx.start.line, expr)
            else -> throw UnsupportedOperationException("Operation not supported")
        }

        semanticAnalyser.incompatibleOperatorTypeCheck(ctx.unary_operator.text, unaryOpExpr, symbolTable, ctx)

        return unaryOpExpr
    }

    override fun visitArray_elem(ctx: Array_elemContext): ArrayElemNode {
        val arrayIdentifier = ctx.IDENT().symbol.text
        val indices = ctx.expr().map { visit(it) as ExpressionNode }

        semanticAnalyser.undeclaredIdentifierCheck(symbolTable, arrayIdentifier, ctx)
        val type = symbolTable.getTypeOf(arrayIdentifier)

        val internalType = when (type) {
            is ArrayType -> {
                semanticAnalyser.checkArrayElemHasCorrectNumberOfIndices(indices, type.is2D, ctx)
                type.internalType
            }
            is StringType -> {
                semanticAnalyser.checkArrayElemHasCorrectNumberOfIndices(indices, false, ctx)
                CharType
            }
            else -> {
                incompatibleOperatorTypeError("[]", type, ctx = ctx)
                ErrorType
            }
        }

        semanticAnalyser.checkArrayElemIndexTypes(indices, symbolTable, ctx)
        return ArrayElemNode(ctx.start.line, arrayIdentifier, indices, internalType)
    }

    override fun visitNode_elem(ctx: Node_elemContext): ASTNode {
        val identifier = ctx.IDENT().symbol.text

        semanticAnalyser.undeclaredIdentifierCheck(symbolTable, identifier, ctx)
        semanticAnalyser.notDataStructureCheck(symbolTable, identifier, ctx)

        val identifierType = symbolTable.getTypeOf(identifier)

        val accessChain = if (ctx.node_elem_access() != null) {
            val list = mutableListOf<DataStructureMethod>()
            var type = identifierType
            for (m in ctx.node_elem_access()) {
                val member = visitNodeElemAccess(m, identifier, type)
                list.add(member)
                type = member.returnType
            }
            list
        } else {
            mutableListOf()
        }

        return if (accessChain.first() is BinaryTreeType.Root) {
            accessChain.removeFirst()
            BinaryTreeRootAccessNode(
                ctx.start.line,
                identifier,
                BinaryTreeNodeElemAccessNode(ctx.start.line, "root", accessChain)
            )
        } else {
            BinaryTreeNodeElemAccessNode(ctx.start.line, identifier, accessChain)
        }
    }

    private fun visitNodeElemAccess(ctx: Node_elem_accessContext, ident: String, type: Type): DataStructureMethod {
        val child = ctx.IDENT().text
        return if (type is DataStructureType) {
            semanticAnalyser.notValidMethodNameForDataStructureCheck(symbolTable, ident, child, ctx, type)
            type.getMethodByName(child)
        } else {
            ErrorMethod
        }
    }

    /** Literals **/

    override fun visitNumberLiteral(ctx: NumberLiteralContext): NumberNode {
        return NumberNode(ctx.start.line, ctx.text.toDouble())
    }

    override fun visitBooleanLiteral(ctx: BooleanLiteralContext): ASTNode {
        return BoolNode(ctx.start.line, ctx.bool().text.toBoolean())
    }

    override fun visitCharacterLiteral(ctx: CharacterLiteralContext): ASTNode {
        // char in format 'a'
        return CharNode(ctx.start.line, ctx.CHAR_LITER().text[1])
    }

    override fun visitStringLiteral(ctx: StringLiteralContext): ASTNode {
        // string in format "string"
        return StringNode(ctx.start.line, ctx.STRING().text.removeSurrounding("\""))
    }

    override fun visitNullLiteral(ctx: NullLiteralContext): ASTNode {
        return NullNode(ctx.start.line)
    }

    /** Types **/

    override fun visitPrimitiveType(ctx: PrimitiveTypeContext): PrimitiveType {
        return visit(ctx.primitive_type()) as PrimitiveType
    }

    override fun visitNumberType(ctx: NumberTypeContext): NumberType {
        return NumberType
    }

    override fun visitBoolType(ctx: BoolTypeContext): ASTNode {
        return BoolType
    }

    override fun visitCharType(ctx: CharTypeContext?): ASTNode {
        return CharType
    }

    override fun visitStringType(ctx: StringTypeContext?): ASTNode {
        return StringType
    }

    override fun visitDataStructureType(ctx: DataStructureTypeContext): DataStructureType {
        val dataStructureType = visit(ctx.data_structure_type()) as DataStructureType
        return dataStructureType
    }

    override fun visitStackType(ctx: StackTypeContext): StackType {
        // Stack only contains primitives as per grammar
        val containerType = visit(ctx.primitive_type()) as PrimitiveType
        return StackType(containerType)
    }

    override fun visitArrayType(ctx: ArrayTypeContext): ArrayType {
        semanticAnalyser.checkArrayConstructorItemLengthsMatch(ctx.ARRAY().size, ctx.GT().size, ctx)
        semanticAnalyser.checkArrayDimensionsNotGreaterThanTwo(ctx.ARRAY().size, ctx)
        val elementType = visit(ctx.type()) as Type
        semanticAnalyser.primitiveInternalTypeForDataStructureCheck(elementType, ctx)
        val arrayType = ArrayType(elementType)
        if (ctx.ARRAY().size == 2) arrayType.setTo2D()
        return arrayType
    }

    override fun visitNodeType(ctx: NodeTypeContext): BinaryTreeNodeType {
        return visit(ctx.node_type()) as BinaryTreeNodeType
    }

    override fun visitNode_type(ctx: Node_typeContext): BinaryTreeNodeType {
        val elementType = visit(ctx.primitive_type()) as PrimitiveType
        return BinaryTreeNodeType(elementType)
    }

    override fun visitTreeType(ctx: TreeTypeContext): BinaryTreeType {
        return BinaryTreeType(visit(ctx.node_type()) as BinaryTreeNodeType)
    }

    override fun visitListType(ctx: ListTypeContext): ASTNode {
        val elementType = visit(ctx.type()) as Type
        semanticAnalyser.primitiveInternalTypeForDataStructureCheck(elementType, ctx)
        return ListType(elementType)
    }

    /** Data structure Initialisers **/

    override fun visitInitialiser_list(ctx: Initialiser_listContext): Array2DInitialiserNode {
        return Array2DInitialiserNode(
            (
                ctx.arg_list() ?: listOf<ArgumentListContext>()
                ).map { visitArgumentList(it as ArgumentListContext?).arguments }
        )
    }

    override fun visitData_structure_initialiser(ctx: Data_structure_initialiserContext): InitialiserNode {
        if (ctx.arg_list() != null) {
            val arguments: List<ExpressionNode> =
                visitArgumentList(ctx.arg_list() as ArgumentListContext?).arguments
            return DataStructureInitialiserNode(arguments)
        }

        return visit(ctx.initialiser_list()) as Array2DInitialiserNode
    }
}
