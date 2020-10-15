package com.manimdsl

import com.manimdsl.frontend.*
import junit.framework.TestCase.assertEquals

import org.junit.jupiter.api.Test


class ASTConstructionTests {

    @Test
    fun variableDeclaration() {
        val declarationProgram = "let x: number = 1;"
        val reference = ProgramNode(listOf(DeclarationNode(1, "x", NumberNode(1, 1.0))))
        val actual = buildAST(declarationProgram)
        assertEquals(reference, actual)
    }

    @Test
    fun sleepCommand() {
        val declarationProgram = "sleep(1.5);"
        val reference = ProgramNode(listOf(SleepNode(NumberNode(1, 1.5))))
        val actual = buildAST(declarationProgram)
        assertEquals(reference, actual)
    }

    @Test
    fun multiLineProgram() {
        val multiLineProgram = "let x: number = 1.5;\n" +
                "# code comment\n" +
                "let y: Stack<number> = new Stack<number>;\n"
        val statements = listOf(
            DeclarationNode(1, "x", NumberNode(1, 1.5)),
            DeclarationNode(3, "y", ConstructorNode(3, StackType(NumberType), listOf()))
        )
        val reference = ProgramNode(statements)
        val actual = buildAST(multiLineProgram)
        assertEquals(reference, actual)
    }

    @Test
    fun methodCallProgram() {
        val methodProgram = "let y: Stack<number> = new Stack<number>;\n" +
                "y.push(1);\n"
        val statements = listOf(
            DeclarationNode(1, "y", ConstructorNode(1, StackType(NumberType), listOf())),
            MethodCallNode(
                2,
                "y",
                StackType.PushMethod(returnType = NoType, argumentTypes = listOf(NumberType)),
                listOf(NumberNode(2, 1.0))
            )
        )
        val reference = ProgramNode(statements)
        val actual = buildAST(methodProgram)
        assertEquals(reference, actual)
    }

    // Assumes syntactically correct program
    private fun buildAST(program: String): ASTNode {
        val parser = ManimDSLParser(program.byteInputStream())
        val (_, ast, _) = parser.convertToAst(parser.parseFile().second)
        return ast
    }
}