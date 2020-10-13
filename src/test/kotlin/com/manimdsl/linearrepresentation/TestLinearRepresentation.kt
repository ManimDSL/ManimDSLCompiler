package com.manimdsl.linearrepresentation

import com.manimdsl.ManimProjectWriter
import com.manimdsl.ManimWriter
import com.manimdsl.shapes.Rectangle
import org.apache.commons.io.FileUtils
import org.junit.Assert.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.*


class TestLinearRepresentation {

    @Test
    fun mockStackLinearRepresentation() {

        val codeBlock = listOf("let y = new Stack;", "y.push(2);", "y.push(3);", "y.pop();");

        val stackIR = listOf(
            CodeBlock(codeBlock, "code_block", "code_text", "pointer"),
            InitStructure(2, -1, Alignment.HORIZONTAL, "y", "empty"),
            NewObject(Rectangle("2"), "testIdent"),
            MoveToLine(2, "pointer", "code_block"),
            MoveObject("testIdent", "y", ObjectSide.ABOVE),
            MoveToLine(3, "pointer", "code_block"),
            NewObject(Rectangle("3"), "testIdent1"),
            MoveObject("testIdent1", "testIdent", ObjectSide.ABOVE),
            Sleep(2.0),
            MoveToLine(4, "pointer", "code_block"),
            MoveObject("testIdent1", "testIdent", ObjectSide.ABOVE, 20),
            Sleep(2.0)
        )

        val writer = ManimProjectWriter(ManimWriter(stackIR).build())
        val generated = writer.createPythonFile()
        val actual = "src/test/testFiles/python/stack.py"

        val generatedIsSameAsActual = FileUtils.contentEquals(File(writer.createPythonFile()), File(actual))

        if (!generatedIsSameAsActual) {
            getDifferenceBetweenGeneratedAndActualFiles(generated, actual)
        }

        assertTrue("The files generated does not match what is expected", generatedIsSameAsActual)
    }

    private fun getDifferenceBetweenGeneratedAndActualFiles(generated: String, actual: String) {
        var sCurrentLine: String?
        val generatedList: MutableList<String> = ArrayList()
        val actualList: MutableList<String> = ArrayList()

        /* Read files into list of strings */
        val br1 = BufferedReader(FileReader(generated))
        val br2 = BufferedReader(FileReader(actual))

        while (br1.readLine().also { sCurrentLine = it } != null) {
            generatedList.add(sCurrentLine!!)
        }
        while (br2.readLine().also { sCurrentLine = it } != null) {
            actualList.add(sCurrentLine!!)
        }

        /* Generate file with differences */
        val shorter: Pair<List<String>, String>;
        val longer: Pair<List<String>, String>;
        if (actualList.size < generatedList.size) {
            shorter = Pair(actualList, "actual")
            longer = Pair(generatedList, "generated")
        } else {
            shorter = Pair(generatedList, "generated")
            longer = Pair(actualList, "actual")
        }

        var differenceBlockShorter = mutableListOf<String>()
        var differenceBlockLonger = mutableListOf<String>()

        for (i in longer.first.indices) {
            /* If i is in range of shorter file compare and handle otherwise print excess */
            if (i in shorter.first.indices) {
                /* If line at i is not equal in both files print difference otherwise print line */
                if (shorter.first[i] != longer.first[i]) {
                    differenceBlockShorter.add("$i: ${shorter.first[i]}")
                    differenceBlockLonger.add("$i: ${longer.first[i]}")
                } else {
                    if (printDifferenceBlocks(differenceBlockLonger, differenceBlockShorter, shorter, longer)) {
                        differenceBlockLonger = mutableListOf()
                        differenceBlockShorter = mutableListOf()
                    }
                    println("$i: ${longer.first[i]}")
                }
            } else {
                printDifferenceBlocks(differenceBlockLonger, differenceBlockShorter, shorter, longer)
                println("$i: ${longer.first[i]}")
            }
        }
    }

    private fun printDifferenceBlocks(
        differenceBlockLonger: MutableList<String>, differenceBlockShorter: MutableList<String>,
        shorter: Pair<List<String>, String>, longer: Pair<List<String>, String>
    ): Boolean {
        if (differenceBlockLonger.size > 0 || differenceBlockShorter.size > 0) {
            println(">>>>>>>>>>>>> ${shorter.second} >>>>>>>>>>>>>>")
            differenceBlockShorter.forEach { println(it) }
            println(">>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>")
            println("<<<<<<<<<<<<<<< ${longer.second} <<<<<<<<<<<<<<<")
            differenceBlockLonger.forEach { println(it) }
            println("<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<")
            return true
        }
        return false
    }

}