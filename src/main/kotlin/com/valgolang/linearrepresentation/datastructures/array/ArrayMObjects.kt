package com.valgolang.linearrepresentation.datastructures.array

import com.valgolang.frontend.datastructures.DataStructureType
import com.valgolang.linearrepresentation.Color
import com.valgolang.linearrepresentation.DataStructureMObject
import com.valgolang.linearrepresentation.TextColor
import com.valgolang.runtime.ExecValue

/**
 * 1D array initialisation
 *
 * @property type
 * @property ident
 * @property render
 * @property text
 * @property values
 * @property color
 * @property textColor
 * @property creationString
 * @property runtime
 * @property showLabel
 * @property maxSize
 * @property boundaries
 * @property uid
 * @constructor Create empty Array structure
 */
data class ArrayStructure(
    override val type: DataStructureType,
    override val ident: String,
    override val render: Boolean,
    override var text: String,
    val values: Array<ExecValue>,
    val color: String? = null,
    val textColor: String? = null,
    var creationString: String? = null,
    override val runtime: Double = 1.0,
    val showLabel: Boolean? = null,
    var maxSize: Int = -1,
    private var boundaries: List<Pair<Double, Double>> = emptyList(),
    override val uid: String
) : DataStructureMObject(type, ident, uid, text, boundaries) {
    override val classPath: String = "python/array.py"
    override val className: String = "Array"
    override val pythonVariablePrefix: String = ""

    init {
        if (creationString == null) creationString = "FadeIn"
        color?.let { style.addStyleAttribute(Color(it)) }
        textColor?.let { style.addStyleAttribute(TextColor(it)) }
    }

    override fun getConstructor(): String {
        val arrayTitle = if (showLabel == null || showLabel) text else ""
        return "$ident = $className([${values.joinToString(",") { "\"${it}\"" }}], \"$arrayTitle\", [${
        boundaries.joinToString(
            ","
        )
        }]$style).build()"
    }

    override fun toPython(): List<String> {
        return listOf(
            "# Constructs a new $type \"$text\"",
            getConstructor(),
            if (render && (showLabel == null || showLabel)) "self.play($creationString($ident.title)${getRuntimeString()})" else "",
            if (values.isNotEmpty()) getInstructionString(
                "[$creationString(array_elem.all${getRuntimeString()}) for array_elem in $ident.array_elements]",
                true
            ) else ""
        )
    }

    override fun setNewBoundary(corners: List<Pair<Double, Double>>, newMaxSize: Int) {
        maxSize = newMaxSize
        boundaries = corners
    }
}

/**
 * 2D array initialisation
 *
 * @property type
 * @property ident
 * @property render
 * @property text
 * @property values
 * @property color
 * @property textColor
 * @property creationString
 * @property runtime
 * @property showLabel
 * @property maxSize
 * @property boundaries
 * @property uid
 * @constructor Create empty Array2d structure
 */
data class Array2DStructure(
    override val type: DataStructureType,
    override val ident: String,
    override val render: Boolean,
    override var text: String,
    val values: Array<Array<ExecValue>>,
    val color: String? = null,
    val textColor: String? = null,
    var creationString: String? = null,
    override val runtime: Double = 1.0,
    val showLabel: Boolean? = null,
    var maxSize: Int = -1,
    private var boundaries: List<Pair<Double, Double>> = emptyList(),
    override val uid: String
) : DataStructureMObject(type, ident, uid, text, boundaries) {
    override val classPath: String = "python/array.py"
    override val className: String = "Array2D"
    override val pythonVariablePrefix: String = ""

    init {
        if (creationString == null) creationString = "FadeIn"
        color?.let { style.addStyleAttribute(Color(it)) }
        textColor?.let { style.addStyleAttribute(TextColor(it)) }
    }

    override fun toPython(): List<String> {
        return listOf(
            "# Constructs a new $type \"$text\"",
            getConstructor(),
            if (render && (showLabel == null || showLabel)) "self.play($creationString($ident.title))" else "",
            getInstructionString("$ident.build(\"$creationString\")", true)
        )
    }

    override fun setNewBoundary(corners: List<Pair<Double, Double>>, newMaxSize: Int) {
        maxSize = newMaxSize
        boundaries = corners
    }

    override fun getConstructor(): String {
        val arrayTitle = if (showLabel == null || showLabel) text else ""
        return "$ident = $className([${
        values.map { array -> "[ ${array.map { "\"${it}\"" }.joinToString(",")}]" }.joinToString(",")
        }], \"$arrayTitle\", [${boundaries.joinToString(",")}]$style)"
    }
}
