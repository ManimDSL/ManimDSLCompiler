package com.manimdsl.runtime.utility

import com.manimdsl.frontend.*
import com.manimdsl.runtime.*
import com.manimdsl.stylesheet.PositionProperties

private val WRAP_LINE_LENGTH = 50

fun wrapCode(code: MutableList<String>): MutableList<MutableList<String>> {
    val wrappedCode = mutableListOf<MutableList<String>>()
    for (line in code) {
        val wrappedLine = mutableListOf<String>()
        var index = 0
        var prevIndex = 0
        while (index < line.length) {
            index = wrapLine(line.substring(index, line.length)) + prevIndex
            wrappedLine.add(line.substring(prevIndex, index))
            prevIndex = index
        }
        wrappedCode.add(wrappedLine)
    }
    return wrappedCode
}

fun wrapLine(line: String): Int {
    val list = line.split(" ")
    var counter = 0
    var prevCounter = 0
    for (word in list) {
        counter += word.length + 1
        if (counter >= WRAP_LINE_LENGTH) {
            return prevCounter
        }
        prevCounter = counter
    }
    return line.length
}

fun getBoundaries(position: PositionProperties?): List<Pair<Double, Double>> {
    val boundaries = mutableListOf<Pair<Double, Double>>()
    if (position != null) {
        val left = position.x
        val right = left + position.width
        val bottom = position.y
        val top = bottom + position.height
        boundaries.addAll(listOf(Pair(left, top), Pair(right, top), Pair(left, bottom), Pair(right, bottom)))
    }
    return boundaries
}

private fun inferType(value: ExecValue): Type {
    return when (value) {
        is CharValue -> CharType
        is DoubleValue -> NumberType
        is BoolValue -> BoolType
        else -> NullType
    }
}

private fun makeExpressionNode(value: ExecValue, lineNumber: Int): ExpressionNode {
    return when (value) {
        is CharValue -> CharNode(lineNumber, value.value)
        is DoubleValue -> NumberNode(lineNumber, value.value)
        is BoolValue -> BoolNode(lineNumber, value.value)
        else -> VoidNode(lineNumber)
    }
}

fun makeConstructorNode(assignedValue: ExecValue, lineNumber: Int): ConstructorNode {
    return when (assignedValue) {
        is ArrayValue -> {
            val dim = NumberNode(lineNumber, assignedValue.array.size.toDouble())
            val initialiser = DataStructureInitialiserNode(
                assignedValue.array.map { makeExpressionNode(it, lineNumber) }
            )
            val type = inferType(assignedValue.array[0])
            ConstructorNode(lineNumber, ArrayType(type, false), listOf(dim), initialiser)
        }
        is Array2DValue -> {
            val dimY = NumberNode(lineNumber, assignedValue.array.size.toDouble())
            val dimX = NumberNode(lineNumber, assignedValue.array[0].size.toDouble())
            val initialiser = Array2DInitialiserNode(
                assignedValue.array.map {
                    it.map { v -> makeExpressionNode(v, lineNumber) }
                }
            )
            val type = inferType(assignedValue.array[0][0])
            ConstructorNode(lineNumber, ArrayType(type, true), listOf(dimY, dimX), initialiser)
        }
        is StackValue -> {
            val type = inferType(assignedValue.value[0])
            val initialiser = DataStructureInitialiserNode(
                assignedValue.stack.map { makeExpressionNode(it, lineNumber) }
            )
            ConstructorNode(lineNumber, StackType(type), listOf(), initialiser)
        }
        is BinaryTreeValue -> {
            val type = inferType(assignedValue.value.value)
            ConstructorNode(lineNumber, TreeType(type), listOf(), EmptyInitialiserNode)
        }
        else -> ConstructorNode(lineNumber, ArrayType(NullType), listOf(), EmptyInitialiserNode)
    }
}
