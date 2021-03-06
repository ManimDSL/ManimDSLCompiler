package com.valgolang

import org.junit.jupiter.api.Test
import picocli.CommandLine

internal class DSLCommandLineArgumentsTest {

    class CommandLineTest : DSLCommandLineArguments() {
        override fun call(): Int {
            val compileTest = listOf(file, output, python, manim, manimArguments, stylesheet, boundaries)
            return 0
        }
    }

    @Test
    fun call() {
        val args = arrayOf("test.val -p -f -o=test.mp4 -q=low --preview --progress_bars")
        CommandLine(CommandLineTest()).execute(*args)
    }
}
