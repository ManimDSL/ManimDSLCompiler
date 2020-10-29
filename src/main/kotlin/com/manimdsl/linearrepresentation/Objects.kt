package com.manimdsl.linearrepresentation

import com.manimdsl.executor.ExecValue
import com.manimdsl.frontend.DataStructureType
import com.manimdsl.shapes.*

/** Objects **/

interface MObject : ManimInstr {
    val shape: Shape
}

/** Positioning **/
interface Position
object RelativeToMoveIdent : Position

data class Coord(val x: Double, val y: Double) : Position {
    override fun toString(): String {
        return "$x, $y"
    }
}

enum class ObjectSide(var coord: Coord) {
    ABOVE(Coord(0.0, 0.25)),
    BELOW(Coord(0.0, -0.25)),
    LEFT(Coord(-0.25, 0.0)),
    RIGHT(Coord(0.25, 0.0));

    fun addOffset(offset: Int): Coord {
        if (this == ABOVE) {
            return Coord(this.coord.x, this.coord.y + offset)
        }
        return coord
    }

    override fun toString(): String {
        return coord.toString()
    }
}

/** MObjects **/
data class CodeBlock(
    val lines: List<String>,
    val ident: String,
    val codeTextName: String,
    val pointerName: String,
    val textColor: String? = null,
) : MObject {
    override val shape: Shape = CodeBlockShape(ident, lines, textColor)

    override fun toPython(): List<String> {
        return listOf(
            "# Building code visualisation pane",
            shape.getConstructor(),
            "$codeTextName = $ident.build()",
            "$codeTextName.move_to(np.array([-4.5, 0, 0]))",
            "self.play(FadeIn($codeTextName))",
            "# Constructing current line pointer",
            "$pointerName = ArrowTip(color=YELLOW).scale(0.7).flip(TOP)",
        )
    }
}

sealed class DataStructureMObject(
    open val type: DataStructureType,
    open val ident: String,
    private var boundaries: List<Pair<Int, Int>> = emptyList()
) : MObject {

    abstract fun setNewBoundary(corners: List<Pair<Int, Int>>, newMaxSize: Int)

}

data class InitManimStack(
    override val type: DataStructureType,
    override val ident: String,
    val position: Position,
    val alignment: Alignment,
    val text: String,
    val moveToShape: Shape? = null,
    val color: String? = null,
    val textColor: String? = null,
    private var boundary: List<Pair<Int, Int>> = emptyList(),
    private var maxSize: Int = -1
) : DataStructureMObject(type, ident, boundary) {
    override var shape: Shape = NullShape

    override fun toPython(): List<String> {
        val python =
            mutableListOf("# Constructing new ${type} \"${text}\"", shape.getConstructor())
        python.add("self.play($ident.create_init(\"$text\"))")
        return python
    }

    override fun setNewBoundary(corners: List<Pair<Int, Int>>, newMaxSize: Int) {
        maxSize = newMaxSize
        boundary = corners
        shape = InitManimStackShape(ident, text, boundary, alignment, color, textColor)
    }
}

data class ArrayStructure(
    override val type: DataStructureType,
    override val ident: String,
    val text: String,
    val values: Array<ExecValue>,
    val color: String? = null,
    val textColor: String? = null,
    var maxSize: Int = -1,
    private var boundaries: List<Pair<Int, Int>> = emptyList()
) : DataStructureMObject(type, ident, boundaries) {
    override var shape: Shape = NullShape

    override fun toPython(): List<String> {
        return listOf(
            "# Constructing new $type \"$text\"",
            shape.getConstructor(),
            "self.play(ShowCreation($ident.title))",
            "self.play(*[ShowCreation(array_elem.group) for array_elem in $ident.array_elements])"
        )
    }

    override fun setNewBoundary(corners: List<Pair<Int, Int>>, newMaxSize: Int) {
        maxSize = newMaxSize
        boundaries = corners
        shape = ArrayShape(ident, values, text, boundaries, color, textColor)
    }
}

data class NewMObject(override val shape: Shape, val codeBlockVariable: String) : MObject {
    override fun toPython(): List<String> {
        return listOf(
            "# Constructs a new ${shape.className} with value ${shape.text}",
            shape.getConstructor(),
        )
    }
}

object EmptyMObject : MObject {
    override val shape: Shape = NullShape
    override fun toPython(): List<String> = emptyList()
}