package com.manimdsl

import picocli.CommandLine
import picocli.CommandLine.*
import java.io.File
import java.util.concurrent.Callable
import kotlin.system.exitProcess

private fun compile(filename: String, outputVideoFile:String, generatePython: Boolean, manimOptions: List<String>) {
    val file = File(filename)
    if (!file.isFile) {
        // File argument was not valid
        println("Please enter a valid file name: $filename not found")
        exitProcess(1)
    }

    println("Compiling...")
    val parser = ManimDSLParser(file.inputStream())
    val (syntaxErrorStatus, program) = parser.parseFile()

    // Error handling
    if (syntaxErrorStatus != ExitStatus.EXIT_SUCCESS) {
        exitProcess(syntaxErrorStatus.code)
    }

    val (semanticErrorStatus, abstractSyntaxTree, symbolTable, lineNodeMap) = parser.convertToAst(program)
    // Error handling
    if (semanticErrorStatus != ExitStatus.EXIT_SUCCESS) {
        exitProcess(semanticErrorStatus.code)
    }

    val manimInstructions = VirtualMachine(abstractSyntaxTree, symbolTable, lineNodeMap, file.readLines()).runProgram()

    val writer = ManimProjectWriter(ManimWriter(manimInstructions).build())

    if (generatePython) println("Writing file to ${outputVideoFile.removeSuffix(".mp4") + ".py"}")
    val outputFile = writer.createPythonFile(if (generatePython) outputVideoFile.removeSuffix(".mp4") + ".py" else null)

    println("Generating animation...")
    val exitCode = writer.generateAnimation(outputFile, manimOptions, outputVideoFile)

    if (exitCode != 0) {
        println("Animation could not be generated")
        exitProcess(1)
    }

    println("Animation Complete!")
}

enum class AnimationQuality {
    LOW,
    HIGH;

    override fun toString(): String {
        return this.name.toLowerCase()
    }
}

@Command(
    name = "manimdsl",
    mixinStandardHelpOptions = true,
    version = ["manimdsl 1.0"],
    description = ["ManimDSL compiler to produce manim animations"]
)
class DSLCommandLineArguments : Callable<Int> {

    private val manimArguments = mutableListOf<String>()

    @Parameters(index = "0", description = ["The manimdsl file to compile and animate"])
    lateinit var file: String

    @Option(names = ["-o", "--output"], description = ["The animated mp4 file location (default: \${DEFAULT-VALUE})"])
    var output: String = "out.mp4"

    @Option(names = ["-p", "--python"], description = ["Output generated python & manim code (optional)"])
    var python: Boolean = false

    @Option(
        names = ["-q", "--quality"],
        defaultValue = "low",
        description = ["Quality of animation. [\${COMPLETION-CANDIDATES}] (default: \${DEFAULT-VALUE})"]
    )
    fun quality(quality: AnimationQuality = AnimationQuality.LOW) {
        when (quality) {
            AnimationQuality.LOW -> manimArguments.add("-l")
            AnimationQuality.HIGH -> manimArguments.add("--high_quality")
        }
    }

    override fun call(): Int {
        compile(file, output, python, manimArguments)
        return 0
    }
}

fun main(args: Array<String>): Unit = exitProcess(CommandLine(DSLCommandLineArguments()).execute(*args))
