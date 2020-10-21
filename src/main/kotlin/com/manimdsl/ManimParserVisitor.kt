package com.manimdsl

import antlr.ManimParser
import antlr.ManimParserBaseVisitor
import com.manimdsl.frontend.*

class ManimParserVisitor : ManimParserBaseVisitor<ASTNode>() {
    val symbolTable = SymbolTableVisitor()

    val lineNumberNodeMap = mutableMapOf<Int, ASTNode>()

    private val semanticAnalyser = SemanticAnalysis()
    private var inFunction: Boolean = false
    private var functionReturnType: Type = VoidType

    override fun visitProgram(ctx: ManimParser.ProgramContext): ProgramNode {
        return ProgramNode(
                ctx.function().map { visit(it) as FunctionNode },
                ctx.stat().map { visit(it) as StatementNode })
    }

    override fun visitFunction(ctx: ManimParser.FunctionContext): FunctionNode {
        inFunction = true
        val identifier = ctx.IDENT().symbol.text
        semanticAnalyser.redeclaredVariableCheck(symbolTable, identifier, ctx)

        val type = if (ctx.type() != null) {
            visit(ctx.type()) as Type
        } else {
            VoidType
        }
        functionReturnType = type

        val scope = symbolTable.enterScope()
        val parameters: List<ParameterNode> =
            visitParameterList(ctx.param_list() as ManimParser.ParameterListContext?).parameters
        val statements = ctx.stat().map { visit(it) as StatementNode }
        symbolTable.leaveScope()

        if (functionReturnType !is VoidType) {
            semanticAnalyser.missingReturnCheck(identifier, statements, functionReturnType, ctx)
        }

        symbolTable.addVariable(identifier, FunctionData(parameters, type))

        inFunction = false
        functionReturnType = VoidType

        val node = FunctionNode(scope, identifier, parameters, statements, ctx.start.line)
        lineNumberNodeMap[ctx.start.line] = node
        return node
    }

    override fun visitParameterList(ctx: ManimParser.ParameterListContext?): ParameterListNode{
        if (ctx == null) {
            return ParameterListNode(listOf())
        }
        return ParameterListNode(ctx.param().map { visit(it) as ParameterNode })
    }

    override fun visitParameter(ctx: ManimParser.ParameterContext): ParameterNode {
        val identifier = ctx.IDENT().symbol.text
        semanticAnalyser.redeclaredVariableCheck(symbolTable, identifier, ctx)

        val type = visit(ctx.type()) as Type
        symbolTable.addVariable(identifier, IdentifierData(type))
        return ParameterNode(identifier, type)
    }

    override fun visitReturnStatement(ctx: ManimParser.ReturnStatementContext): ReturnNode {
        semanticAnalyser.globalReturnCheck(inFunction, ctx)
        val expression = visit(ctx.expr()) as ExpressionNode
        semanticAnalyser.incompatibleReturnTypesCheck(symbolTable, functionReturnType, expression, ctx)
        val node = ReturnNode(ctx.start.line, expression)
        lineNumberNodeMap[ctx.start.line] = node
        return node

    }

    override fun visitSleepStatement(ctx: ManimParser.SleepStatementContext): SleepNode {
        val node = SleepNode(ctx.start.line, visit(ctx.expr()) as ExpressionNode)
        lineNumberNodeMap[ctx.start.line] = node
        return node
    }

    override fun visitDeclarationStatement(ctx: ManimParser.DeclarationStatementContext): DeclarationNode {
        val identifier = ctx.IDENT().symbol.text
        semanticAnalyser.redeclaredVariableCheck(symbolTable, identifier, ctx)

        val rhs = visit(ctx.expr()) as ExpressionNode

        val rhsType = semanticAnalyser.inferType(symbolTable, rhs)
        val lhsType = if (ctx.type() != null) {
            visit(ctx.type()) as Type
        } else {
            rhsType
        }

        semanticAnalyser.voidTypeDeclarationCheck(rhsType, identifier, ctx)
        semanticAnalyser.incompatibleTypesCheck(lhsType, rhsType, identifier, ctx)

        symbolTable.addVariable(identifier, IdentifierData(rhsType))
        val node = DeclarationNode(ctx.start.line, identifier, rhs)
        lineNumberNodeMap[ctx.start.line] = node
        return node
    }

    override fun visitAssignmentStatement(ctx: ManimParser.AssignmentStatementContext): AssignmentNode {
        val expression = visit(ctx.expr()) as ExpressionNode
        val identifier = ctx.IDENT().symbol.text
        val rhsType = semanticAnalyser.inferType(symbolTable, expression)
        val identifierType = symbolTable.getTypeOf(identifier)

        semanticAnalyser.undeclaredIdentifierCheck(symbolTable, identifier, ctx)
        semanticAnalyser.incompatibleTypesCheck(identifierType, rhsType, identifier, ctx)
        val node = AssignmentNode(ctx.start.line, identifier, expression)
        lineNumberNodeMap[ctx.start.line] = node
        return node
    }

    override fun visitMethodCallStatement(ctx: ManimParser.MethodCallStatementContext): ASTNode {
        return visit(ctx.method_call())
    }

    override fun visitMethodCallExpression(ctx: ManimParser.MethodCallExpressionContext): ASTNode {
        return visit(ctx.method_call())
    }

    override fun visitArgumentList(ctx: ManimParser.ArgumentListContext?): ArgumentNode {
        return ArgumentNode((ctx?.expr()
            ?: listOf<ManimParser.ExprContext>()).map { visit(it) as ExpressionNode })
    }

    override fun visitMethodCall(ctx: ManimParser.MethodCallContext): MethodCallNode {
        // Type signature of methods to be determined by symbol table
        val arguments: List<ExpressionNode> =
            visitArgumentList(ctx.arg_list() as ManimParser.ArgumentListContext?).arguments
        val identifier = ctx.IDENT(0).symbol.text
        val methodName = ctx.IDENT(1).symbol.text

        semanticAnalyser.undeclaredIdentifierCheck(symbolTable, identifier, ctx)
        semanticAnalyser.notDataStructureCheck(symbolTable, identifier, ctx)
        semanticAnalyser.notValidMethodNameForDataStructureCheck(symbolTable, identifier, methodName, ctx)

        val dataStructureType = symbolTable.getTypeOf(identifier)

        val dataStructureMethod = if (dataStructureType is DataStructureType) {
            semanticAnalyser.invalidNumberOfArgumentsCheck(dataStructureType, methodName, arguments.size, ctx)

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

        val node = MethodCallNode(ctx.start.line, ctx.IDENT(0).symbol.text, dataStructureMethod, arguments)

        if (dataStructureMethod.returnType is ErrorType) {
            lineNumberNodeMap[ctx.start.line] = node
        }
        return node
    }

    override fun visitFunctionCall(ctx: ManimParser.FunctionCallContext): FunctionCallNode {
        val identifier = ctx.IDENT().symbol.text
        semanticAnalyser.undeclaredIdentifierCheck(symbolTable, identifier, ctx)

        val arguments: List<ExpressionNode> =
                visitArgumentList(ctx.arg_list() as ManimParser.ArgumentListContext?).arguments
        semanticAnalyser.invalidNumberOfArgumentsForFunctionsCheck(identifier, symbolTable, arguments.size, ctx)

        val argTypes = arguments.map { semanticAnalyser.inferType(symbolTable, it) }.toList()
        semanticAnalyser.incompatibleArgumentTypesForFunctionsCheck(identifier, symbolTable, argTypes, ctx)

        return FunctionCallNode(ctx.start.line, ctx.IDENT().symbol.text, arguments)
    }

    override fun visitDataStructureContructor(ctx: ManimParser.DataStructureContructorContext): ASTNode {
        return ConstructorNode(ctx.start.line, visit(ctx.data_structure_type()) as DataStructureType, listOf())
    }

    override fun visitIdentifier(ctx: ManimParser.IdentifierContext): IdentifierNode {
        return IdentifierNode(ctx.start.line, ctx.text)
    }

    override fun visitBinaryExpression(ctx: ManimParser.BinaryExpressionContext): BinaryExpression {
        val expr1 = visit(ctx.expr(0)) as ExpressionNode
        val expr2 = visit(ctx.expr(1)) as ExpressionNode
        if (expr1 is IdentifierNode) {
            semanticAnalyser.undeclaredIdentifierCheck(symbolTable, expr1.identifier, ctx)
        }
        if (expr2 is IdentifierNode) {
            semanticAnalyser.undeclaredIdentifierCheck(symbolTable, expr2.identifier, ctx)
        }
        return when (ctx.binary_operator.type) {
            ManimParser.ADD -> AddExpression(ctx.start.line, expr1, expr2)
            ManimParser.MINUS -> SubtractExpression(ctx.start.line, expr1, expr2)
            else -> MultiplyExpression(ctx.start.line, expr1, expr2)
        }
    }

    override fun visitUnaryOperator(ctx: ManimParser.UnaryOperatorContext): UnaryExpression {
        val expr = visit(ctx.expr()) as ExpressionNode
        if (expr is IdentifierNode) {
            semanticAnalyser.undeclaredIdentifierCheck(symbolTable, expr.identifier, ctx)
        }
        return when (ctx.unary_operator.type) {
            ManimParser.ADD -> PlusExpression(ctx.start.line, expr)
            else -> MinusExpression(ctx.start.line, expr)
        }
    }

    override fun visitCommentStatement(ctx: ManimParser.CommentStatementContext): CommentNode {
        // Command command given for render purposes
        val node = CommentNode(ctx.start.line, ctx.STRING().text)
        lineNumberNodeMap[ctx.start.line] = node
        return node
    }

    override fun visitNumberLiteral(ctx: ManimParser.NumberLiteralContext): NumberNode {
        return NumberNode(ctx.start.line, ctx.NUMBER().symbol.text.toDouble())
    }

    override fun visitNumberType(ctx: ManimParser.NumberTypeContext): NumberType {
        return NumberType
    }

    override fun visitDataStructureType(ctx: ManimParser.DataStructureTypeContext): DataStructureType {
        return visit(ctx.data_structure_type()) as DataStructureType
    }

    override fun visitPrimitiveType(ctx: ManimParser.PrimitiveTypeContext): PrimitiveType {
        return visit(ctx.primitive_type()) as PrimitiveType
    }

    override fun visitStackType(ctx: ManimParser.StackTypeContext): StackType {
        // Stack only contains primitives as per grammar
        val containerType = visit(ctx.primitive_type()) as PrimitiveType
        return StackType(containerType)
    }
}