package com.manimdsl

import antlr.ManimParser
import antlr.ManimParserBaseVisitor
import com.manimdsl.frontend.*
import javax.lang.model.type.ErrorType

class ManimParserVisitor: ManimParserBaseVisitor<ASTNode>() {
    val currentSymbolTable = SymbolTableNode()
    private val semanticAnalyser = SemanticAnalysis()
    override fun visitProgram(ctx: ManimParser.ProgramContext): ProgramNode {

        return ProgramNode(ctx.stat().map { visit(it) as StatementNode })
    }

    override fun visitSleepStatement(ctx: ManimParser.SleepStatementContext): SleepNode {
        return SleepNode(visit(ctx.expr()) as ExpressionNode)
    }

    override fun visitDeclarationStatement(ctx: ManimParser.DeclarationStatementContext): DeclarationNode {
        val identifier = ctx.IDENT().symbol.text
        semanticAnalyser.redeclaredVariableCheck(currentSymbolTable, identifier, ctx)

        val rhs = visit(ctx.expr()) as ExpressionNode

        val rhsType = semanticAnalyser.inferType(currentSymbolTable, rhs)
        val lhsType = if (ctx.type() != null) {
            visit(ctx.type()) as Type
        } else {
            rhsType
        }

        semanticAnalyser.incompatibleTypesCheck(lhsType, rhsType, identifier, ctx)

        currentSymbolTable.addVariable(identifier, rhsType)
        return DeclarationNode(ctx.start.line, identifier, rhs)
    }

    override fun visitAssignmentStatement(ctx: ManimParser.AssignmentStatementContext): AssignmentNode {
        val expression = visit(ctx.expr()) as ExpressionNode
        val identifier = ctx.IDENT().symbol.text
        val rhsType = semanticAnalyser.inferType(currentSymbolTable, expression)
        val identifierType = currentSymbolTable.getTypeOf(identifier)

        semanticAnalyser.undeclaredIdentifierCheck(currentSymbolTable, identifier, ctx)
        semanticAnalyser.incompatibleTypesCheck(identifierType, rhsType, identifier, ctx)
        return AssignmentNode(ctx.start.line, identifier, expression)
    }

    override fun visitMethodCallStatement(ctx: ManimParser.MethodCallStatementContext): MethodCallNode {
        return visitMethodCall(ctx.method_call() as ManimParser.MethodCallContext)
    }

    override fun visitMethodCallExpression(ctx: ManimParser.MethodCallExpressionContext): MethodCallNode {
        return visitMethodCall(ctx.method_call() as ManimParser.MethodCallContext)
    }

    override fun visitArgumentList(ctx: ManimParser.ArgumentListContext?): ArgumentNode {
        return ArgumentNode((ctx?.expr()
                ?: listOf<ManimParser.ExprContext>()).map { visit(it) as ExpressionNode })
    }

    override fun visitMethodCall(ctx: ManimParser.MethodCallContext): MethodCallNode {
        // Type signature of methods to be determined by symbol table
        val arguments: List<ExpressionNode> = visitArgumentList(ctx.arg_list() as ManimParser.ArgumentListContext?).arguments
        val identifier = ctx.IDENT(0).symbol.text
        val methodName = ctx.IDENT(1).symbol.text

        semanticAnalyser.undeclaredIdentifierCheck(currentSymbolTable, identifier, ctx)
        semanticAnalyser.notDataStructureCheck(currentSymbolTable, identifier, ctx)
        semanticAnalyser.notValidMethodNameForDataStructureCheck(currentSymbolTable, identifier, methodName, ctx)

        val dataStructureType = currentSymbolTable.getTypeOf(identifier)

        val dataStructureMethod = if (dataStructureType is DataStructureType) {
            semanticAnalyser.invalidNumberOfArgumentsCheck(dataStructureType, methodName, arguments.size, ctx)

            val method = dataStructureType.getMethodByName(methodName)

            // Assume for now we only have one type inside the data structure and data structure functions only deal with this type
            val argTypes = arguments.map { semanticAnalyser.inferType(currentSymbolTable, it) }.toList()
            semanticAnalyser.argTypesCheck(argTypes, methodName, dataStructureType, ctx)
            semanticAnalyser.incompatibleArgumentTypesCheck(
                    dataStructureType,
                    argTypes,
                    method,
                    ctx
            )
            method

        } else {
            ErrorMethod()
        }

        return MethodCallNode(ctx.start.line, ctx.IDENT(0).symbol.text, dataStructureMethod, arguments)
    }

    override fun visitStackCreate(ctx: ManimParser.StackCreateContext): ConstructorNode {
        return ConstructorNode(ctx.start.line, StackType(NumberType), listOf())
    }

    override fun visitIdentifier(ctx: ManimParser.IdentifierContext): IdentifierNode {
        return IdentifierNode(ctx.start.line, ctx.text)
    }

    override fun visitBinaryExpression(ctx: ManimParser.BinaryExpressionContext): BinaryExpression {
        val expr1 = visit(ctx.expr(0)) as ExpressionNode
        val expr2 = visit(ctx.expr(1)) as ExpressionNode
        if (expr1 is IdentifierNode) {
            semanticAnalyser.undeclaredIdentifierCheck(currentSymbolTable, expr1.identifier, ctx)
        }
        if (expr2 is IdentifierNode) {
            semanticAnalyser.undeclaredIdentifierCheck(currentSymbolTable, expr2.identifier, ctx)
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
            semanticAnalyser.undeclaredIdentifierCheck(currentSymbolTable, expr.identifier, ctx)
        }
        return when (ctx.unary_operator.type) {
            ManimParser.ADD -> PlusExpression(ctx.start.line, expr)
            else -> MinusExpression(ctx.start.line, expr)
        }
    }

    override fun visitCommentStatement(ctx: ManimParser.CommentStatementContext): CommentNode {
        // Command command given for render purposes
        return CommentNode(ctx.STRING().text)
    }
    override fun visitNumberLiteral(ctx: ManimParser.NumberLiteralContext): NumberNode {
        return NumberNode(ctx.start.line, ctx.NUMBER().symbol.text.toDouble())
    }

    override fun visitNumberType(ctx: ManimParser.NumberTypeContext): NumberType {
        return NumberType
    }

    override fun visitStackType(ctx: ManimParser.StackTypeContext): StackType {
        return StackType(NumberType)
    }
}