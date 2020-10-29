package com.manimdsl.linearrepresentation

import com.manimdsl.executor.ExecValue
import com.manimdsl.shapes.Shape
import com.manimdsl.shapes.StyleableShape
import com.manimdsl.stylesheet.StylesheetProperty

interface ManimInstr {
    fun toPython(): List<String>
}

enum class Alignment(val angle: String) {
    HORIZONTAL("0"), VERTICAL("TAU/4")
}

/** Animation Functions **/

data class Sleep(val length: Double = 1.0) : ManimInstr {
    override fun toPython(): List<String> {
        return listOf("self.wait($length)")
    }
}

data class MoveToLine(val lineNumber: Int, val pointerName: String, val codeBlockName: String) : ManimInstr {
    override fun toPython(): List<String> {
        return listOf(
            "self.move_arrow_to_line($lineNumber, $pointerName, $codeBlockName)"
        )
    }
}

data class MoveObject(
    val shape: Shape,
    val moveToShape: Shape,
    val objectSide: ObjectSide,
    val offset: Int = 0,
    val fadeOut: Boolean = false
) :
    ManimInstr {
    override fun toPython(): List<String> {
        val instructions =
            mutableListOf("self.move_relative_to_obj($shape, $moveToShape, ${objectSide.addOffset(offset)})")
        if (fadeOut) {
            instructions.add("self.play(FadeOut($shape))")
        }
        return instructions
    }
}

data class StackPushObject(
    val shape: Shape,
    val dataStructureIdentifier: String,
    val newStyle: StylesheetProperty,
    val isPushPop: Boolean = false
) : ManimInstr {
    override fun toPython(): List<String> {
        val colorString = if (newStyle.borderColor != null) ", color=${newStyle.borderColor}" else ""
        val textColorString = if (newStyle.textColor != null) ", text_color=${newStyle.textColor}" else ""
        val methodName = if(isPushPop) "push_existing" else "push"
        return listOf(
            "[self.play(*animation) for animation in $dataStructureIdentifier.$methodName(${shape.ident}$colorString$textColorString)]",
            "$dataStructureIdentifier.add($shape)"
        )
    }
}

data class StackPopObject(
    val shape: Shape,
    val dataStructureIdentifier: String,
    val newStyle: StylesheetProperty,
    val insideMethodCall: Boolean
) : ManimInstr {
    override fun toPython(): List<String> {
        val colorString = if (newStyle.borderColor != null) ", color=${newStyle.borderColor}" else ""
        val textColorString = if (newStyle.textColor != null) ", text_color=${newStyle.textColor}" else ""
        return listOf("[self.play(*animation) for animation in $dataStructureIdentifier.pop(${shape.ident}$colorString$textColorString, fade_out=${(!insideMethodCall).toString().capitalize()})]")


    }
}

data class RestyleObject(
    val shape: Shape,
    val newStyle: StylesheetProperty,
) : ManimInstr {
    override fun toPython(): List<String> {
        return if (shape is StyleableShape) {
            shape.restyle(newStyle)
        } else emptyList()
    }
}

data class VariableState(val variableStates: Map<String, ExecValue>) : ManimInstr {
    override fun toPython(): List<String> {
        TODO("Not yet implemented")
    }
}




