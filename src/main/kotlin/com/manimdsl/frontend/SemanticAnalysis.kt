package com.manimdsl.frontend

import antlr.ManimParser
import com.manimdsl.errorhandling.semanticerror.*
import org.antlr.v4.runtime.ParserRuleContext

class SemanticAnalysis {

    fun redeclaredVariableCheck(currentSymbolTable: SymbolTableNode, identifier: String): Boolean {
        return currentSymbolTable.getTypeOf(identifier) != NoType
    }


    private fun getExpressionType(expression: ExpressionNode, currentSymbolTable: SymbolTableNode): Type = when (expression) {
        is IdentifierNode -> currentSymbolTable.getTypeOf(expression.identifier)
        is NumberNode -> NumberType
        is MethodCallNode -> expression.dataStructureMethod.returnType
        is ConstructorNode -> expression.type
        is BinaryExpression -> if (getExpressionType(expression.expr1, currentSymbolTable) is NumberType && getExpressionType(expression.expr2, currentSymbolTable) is NumberType) {
            NumberType
        } else {
            NoType
        }
        is UnaryExpression -> getExpressionType(expression.expr, currentSymbolTable)
    }

    fun inferType(currentSymbolTable: SymbolTableNode, expression: ExpressionNode): Type {
        return getExpressionType(expression, currentSymbolTable)
    }

    fun incompatibleTypesCheck(lhsType: Type, rhsType: Type, text: String, ctx: ParserRuleContext) {
        if (lhsType != rhsType){
            declareAssignError(text, rhsType, lhsType, ctx)
        }
    }

    fun undeclaredIdentifierCheck(currentSymbolTable: SymbolTableNode, identifier: String, ctx: ParserRuleContext) {
        if (currentSymbolTable.getTypeOf(identifier) == NoType){
            undeclaredAssignError(identifier, ctx)
        }
    }

    fun notDataStructureCheck(currentSymbolTable: SymbolTableNode, identifier: String, ctx: ParserRuleContext) {
        if (currentSymbolTable.getTypeOf(identifier) !is DataStructureType) {
            nonDataStructureMethodError(identifier, ctx)
        }
    }

    fun notValidMethodNameForDataStructureCheck(currentSymbolTable: SymbolTableNode, identifier: String, method: String, ctx: ParserRuleContext) {
        val dataStructureType = currentSymbolTable.getTypeOf(identifier)
        if (dataStructureType is DataStructureType && !dataStructureType.containsMethod(method)) {
            unsupportedMethodError(dataStructureType.toString(), method, ctx)
        }
    }

    fun invalidNumberOfArgumentsCheck(dataStructureType: DataStructureType, method: String, numArgs: Int, ctx: ParserRuleContext) {
        if (dataStructureType.getMethodByName(method).argumentTypes.size != numArgs) {
            numOfArgsInMethodCallError(dataStructureType.toString(), method, numArgs, ctx)
        }
    }

    fun argTypesCheck(argTypes: List<Type>, method: String, dataStructureType: DataStructureType, ctx: ManimParser.MethodCallContext) {
        argTypes.forEachIndexed { index, type ->
            if (type !is PrimitiveType) {
                val argCtx = ctx.arg_list().getRuleContext(ManimParser.ExprContext::class.java, index)
                val argName = ctx.arg_list().getChild(index).text
                typeOfArgsInMethodCallError(dataStructureType.toString(), method, type.toString(), argName, argCtx)
            }
        }
    }

    fun incompatibleArgumentTypesCheck(dataStructureType: DataStructureType, argumentTypes: List<Type>, dataStructureMethod: DataStructureMethod, ctx: ManimParser.MethodCallContext) {

        argumentTypes.forEachIndexed { index, type ->
            if (type !== dataStructureMethod.argumentTypes[index]) {
                val argCtx = ctx.arg_list().getRuleContext(ManimParser.ExprContext::class.java, index)
                val argName = ctx.arg_list().getChild(index).text
                typeOfArgsInMethodCallError(dataStructureType.toString(), dataStructureMethod.toString(), type.toString(), argName, argCtx)

            }
        }
    }

}