package com.manimdsl.runtime

import com.google.gson.Gson
import com.manimdsl.ExitStatus
import com.manimdsl.errorhandling.ErrorHandler.addRuntimeError
import com.manimdsl.frontend.*
import com.manimdsl.linearrepresentation.*
import com.manimdsl.runtime.datastructures.array.ArrayExecutor
import com.manimdsl.runtime.datastructures.binarytree.BinaryTreeExecutor
import com.manimdsl.runtime.datastructures.stack.StackExecutor
import com.manimdsl.runtime.utility.getBoundaries
import com.manimdsl.runtime.utility.makeConstructorNode
import com.manimdsl.runtime.utility.wrapCode
import com.manimdsl.runtime.utility.wrapString
import com.manimdsl.shapes.SubtitleBlockShape
import com.manimdsl.stylesheet.PositionProperties
import com.manimdsl.stylesheet.Stylesheet
import java.util.*

class VirtualMachine(
    private val program: ProgramNode,
    private val symbolTableVisitor: SymbolTableVisitor,
    private val statements: MutableMap<Int, StatementNode>,
    private val fileLines: List<String>,
    private val stylesheet: Stylesheet,
    private val returnBoundaries: Boolean = false
) {

    private val linearRepresentation = mutableListOf<ManimInstr>()
    private val variableNameGenerator = VariableNameGenerator(symbolTableVisitor)
    private val codeBlockVariable: String = variableNameGenerator.generateNameFromPrefix("code_block")
    private val codeTextVariable: String = variableNameGenerator.generateNameFromPrefix("code_text")
    private var subtitleBlockVariable: MObject = EmptyMObject
    private val pointerVariable: String = variableNameGenerator.generateNameFromPrefix("pointer")
    private val displayLine: MutableList<Int> = mutableListOf()
    private val displayCode: MutableList<String> = mutableListOf()
    private val dataStructureBoundaries = mutableMapOf<String, BoundaryShape>()
    private var acceptableNonStatements = setOf("}", "{")
    private val MAX_DISPLAYED_VARIABLES = 4
    private val ALLOCATED_STACKS = Runtime.getRuntime().freeMemory() / 1000000
    private val STEP_INTO_DEFAULT = stylesheet.getStepIntoIsDefault()
    private val MAX_NUMBER_OF_LOOPS = 10000
    private val SUBTITLE_DEFAULT_DURATION = 5
    private val hideCode = stylesheet.getHideCode()
    private val hideVariables = stylesheet.getHideVariables()
    private var animationSpeeds = ArrayDeque(listOf(1.0))

    init {
        if (stylesheet.getDisplayNewLinesInCode()) {
            acceptableNonStatements = acceptableNonStatements.plus("")
        }
        fileLines.indices.forEach {
            if (statements[it + 1] !is NoRenderAnimationNode &&
                (acceptableNonStatements.any { x -> fileLines[it].contains(x) } || statements[it + 1] is CodeNode)
            ) {
                if (fileLines[it].isEmpty()) {
                    if (stylesheet.getDisplayNewLinesInCode()) {
                        if (stylesheet.getSyntaxHighlighting()) {
                            displayCode.add(" ")
                        } else {
                            displayCode.add("")
                        }
                        displayLine.add(1 + (displayLine.lastOrNull() ?: 0))
                    }
                } else {
                    displayCode.add(fileLines[it])
                    displayLine.add(1 + (displayLine.lastOrNull() ?: 0))
                }
            } else {
                displayLine.add(displayLine.lastOrNull() ?: 0)
            }
        }
    }

    fun runProgram(): Pair<ExitStatus, List<ManimInstr>> {
        if (!hideCode) {
            if (!hideVariables) {
                linearRepresentation.add(
                    VariableBlock(
                        listOf(),
                        "variable_block",
                        "variable_vg",
                        runtime = animationSpeeds.first()
                    )
                )
            }
            linearRepresentation.add(
                CodeBlock(
                    wrapCode(displayCode),
                    codeBlockVariable,
                    codeTextVariable,
                    pointerVariable,
                    runtime = animationSpeeds.first(),
                    syntaxHighlightingOn = stylesheet.getSyntaxHighlighting(),
                    syntaxHighlightingStyle = stylesheet.getSyntaxHighlightingStyle(),
                    tabSpacing = stylesheet.getTabSpacing()
                )
            )
        }
        val variables = mutableMapOf<String, ExecValue>()
        val result = Frame(
            program.statements.first().lineNumber,
            fileLines.size,
            variables,
            hideCode = hideCode,
            updateVariableState = !(hideCode || hideVariables)
        ).runFrame()
        linearRepresentation.add(Sleep(1.0, runtime = animationSpeeds.first()))
        return if (result is RuntimeError) {
            addRuntimeError(result.value, result.lineNumber)
            Pair(ExitStatus.RUNTIME_ERROR, linearRepresentation)
        } else if (returnBoundaries || !stylesheet.userDefinedPositions()) {
            val (exitStatus, computedBoundaries) = Scene().compute(
                dataStructureBoundaries.toList(),
                hideCode,
                hideVariables
            )
            if (returnBoundaries) {
                val boundaries = mutableMapOf<String, Map<String, PositionProperties>>()
                val genericShapeIDs = mutableSetOf<String>()
                if (!stylesheet.getHideCode()) {
                    genericShapeIDs.add("_code")
                    if (!stylesheet.getHideVariables()) genericShapeIDs.add("_variables")
                }
                boundaries["auto"] = computedBoundaries.mapValues { it.value.positioning() }
                boundaries["stylesheet"] = stylesheet.getPositions()
                    .filter { it.key in dataStructureBoundaries.keys || genericShapeIDs.contains(it.key) }
                val gson = Gson()
                println(gson.toJson(boundaries))
            }
            if (exitStatus != ExitStatus.EXIT_SUCCESS) {
                return Pair(exitStatus, linearRepresentation)
            }
            val linearRepresentationWithBoundaries = linearRepresentation.map {
                if (it is ShapeWithBoundary) {
                    val boundaryShape = computedBoundaries[it.uid]!!
                    it.setNewBoundary(boundaryShape.corners(), boundaryShape.maxSize)
                }
                it
            }
            Pair(ExitStatus.EXIT_SUCCESS, linearRepresentationWithBoundaries)
        } else {
            linearRepresentation.forEach {
                if (it is ShapeWithBoundary) {
                    if (it is CodeBlock || it is VariableBlock || it is SubtitleBlock) {
                        val position = stylesheet.getPosition(it.uid)
                        if (position == null) {
                            addRuntimeError("Missing positional parameter for ${it.uid}", 1)
                            return Pair(ExitStatus.RUNTIME_ERROR, linearRepresentation)
                        }
                        it.setNewBoundary(position.calculateManimCoord(), -1)
                    }
                    it.setShape()
                }
            }
            Pair(ExitStatus.EXIT_SUCCESS, linearRepresentation)
        }
    }

    inner class Frame(
        private var pc: Int,
        private var finalLine: Int,
        private var variables: MutableMap<String, ExecValue>,
        val depth: Int = 1,
        private var showMoveToLine: Boolean = true,
        private var stepInto: Boolean = STEP_INTO_DEFAULT,
        private var leastRecentlyUpdatedQueue: LinkedList<Int> = LinkedList(),
        private var displayedDataMap: MutableMap<Int, Pair<String, ExecValue>> = mutableMapOf(),
        private val updateVariableState: Boolean = true,
        private val hideCode: Boolean = false,
        val functionNamePrefix: String = "",
        private val localDataStructure: MutableSet<String>? = null
    ) {
        private var previousStepIntoState = stepInto

        /** Data Structure Executors **/
        private val btExecutor = BinaryTreeExecutor(variables, linearRepresentation, this, stylesheet, animationSpeeds, dataStructureBoundaries, variableNameGenerator, codeTextVariable)
        private val arrExecutor = ArrayExecutor(variables, linearRepresentation, this, stylesheet, animationSpeeds, dataStructureBoundaries, variableNameGenerator, codeTextVariable)
        private val stackExecutor = StackExecutor(variables, linearRepresentation, this, stylesheet, animationSpeeds, dataStructureBoundaries, variableNameGenerator, codeTextVariable)

        fun getShowMoveToLine() = showMoveToLine

        fun getPc() = pc

        fun insertVariable(identifier: String, value: ExecValue) {
            if (shouldRenderInVariableState(value, functionNamePrefix + identifier)) {
                val index = displayedDataMap.filterValues { it.first == identifier }.keys
                if (index.isEmpty()) {
                    // not been visualised
                    // if there is space
                    if (displayedDataMap.size < MAX_DISPLAYED_VARIABLES) {
                        val newIndex = displayedDataMap.size
                        leastRecentlyUpdatedQueue.addLast(newIndex)
                        displayedDataMap[newIndex] = Pair(identifier, value)
                    } else {
                        // if there is no space
                        val oldest = leastRecentlyUpdatedQueue.removeFirst()
                        displayedDataMap[oldest] = Pair(identifier, value)
                        leastRecentlyUpdatedQueue.addLast(oldest)
                    }
                } else {
                    // being visualised
                    leastRecentlyUpdatedQueue.remove(index.first())
                    leastRecentlyUpdatedQueue.addLast(index.first())
                    displayedDataMap[index.first()] = Pair(identifier, value)
                }
            }
        }

        private fun shouldRenderInVariableState(value: ExecValue, identifier: String) =
            (value is BinaryTreeNodeValue && value.binaryTreeValue == null) ||
                value is PrimitiveValue ||
                (value is BinaryTreeValue && !stylesheet.renderDataStructure(identifier)) ||
                (value is ArrayValue && !stylesheet.renderDataStructure(identifier)) ||
                (value is StackValue && !stylesheet.renderDataStructure(identifier))

        fun removeVariable(identifier: String) {
            displayedDataMap = displayedDataMap.filter { (_, v) -> v.first != identifier }.toMutableMap()
        }

        fun convertToIdent(dataStructureVariable: MutableSet<String>?) {
            if (dataStructureVariable != null) {
                val idents = dataStructureVariable.map { (variables[it]!!.manimObject as DataStructureMObject).ident }
                dataStructureVariable.forEach {
                    variables[it] = EmptyValue
                }
                dataStructureVariable.clear()
                dataStructureVariable.addAll(idents)
            }
        }

        // instantiate new Frame and execute on scoping changes e.g. recursion
        fun runFrame(): ExecValue {
            if (depth > ALLOCATED_STACKS) {
                return RuntimeError(value = "Stack Overflow Error. Program failed to terminate.", lineNumber = pc)
            }

            if (updateVariableState) {
                variables.forEach { (identifier, execValue) -> insertVariable(identifier, execValue) }
                updateVariableState()
            }

            while (pc <= finalLine) {
                if (statements.containsKey(pc)) {
                    val statement = statements[pc]!!

                    if (statement is CodeNode) {
                        moveToLine()
                    }

                    val value = executeStatement(statement)
                    if (statement is ReturnNode || value !is EmptyValue) {
                        convertToIdent(localDataStructure)
                        return value
                    }
                }

                fetchNextStatement()
            }
            convertToIdent(localDataStructure)
            return EmptyValue
        }

        private fun getVariableState(): List<String> {
            return displayedDataMap.toSortedMap().map { wrapString("${it.value.first} = ${it.value.second}") }
        }

        private fun executeStatement(statement: StatementNode): ExecValue = when (statement) {
            is ReturnNode -> executeExpression(statement.expression)
            is FunctionNode -> {
                // just go onto next line, this is just a label
                EmptyValue
            }
            is SleepNode -> executeSleep(statement)
            is AssignmentNode -> executeAssignment(statement)
            is DeclarationNode -> executeAssignment(statement)
            is MethodCallNode -> executeMethodCall(statement, false, false)
            is FunctionCallNode -> executeFunctionCall(statement)
            is IfStatementNode -> executeIfStatement(statement)
            is WhileStatementNode -> executeWhileStatement(statement)
            is ForStatementNode -> executeForStatement(statement)
            is LoopStatementNode -> executeLoopStatement(statement)
            is InternalArrayMethodCallNode -> arrExecutor.executeInternalArrayMethodCall(statement)
            is StartSpeedChangeNode -> {
                val condition = executeExpression(statement.condition)
                val factor = executeExpression(statement.speedChange)
                if (condition is BoolValue && factor is DoubleValue) {
                    if (factor.value <= 0) {
                        RuntimeError("Non positive speed change provided", lineNumber = statement.lineNumber)
                    }
                    if (condition.value) {
                        animationSpeeds.addFirst(1.0 / factor.value)
                    } else {
                        animationSpeeds.addFirst(animationSpeeds.first)
                    }
                    EmptyValue
                } else if (condition is BoolValue) {
                    factor
                } else {
                    condition
                }
            }
            is StopSpeedChangeNode -> {
                animationSpeeds.removeFirst()
                EmptyValue
            }
            is StartCodeTrackingNode -> {
                val condition = executeExpression(statement.condition)
                if (condition is BoolValue) {
                    previousStepIntoState = stepInto
                    if (condition.value) {
                        stepInto = statement.isStepInto
                    }
                    EmptyValue
                } else {
                    condition
                }
            }
            is StopCodeTrackingNode -> {
                stepInto = previousStepIntoState
                EmptyValue
            }
            is SubtitleAnnotationNode -> {
                val condition = executeExpression(statement.condition) as BoolValue
                if (condition.value) {
                    if (statement.showOnce) statement.condition = BoolNode(statement.lineNumber, false)

                    val duration: Int = if (statement.duration != null) {
                        (executeExpression(statement.duration) as DoubleValue).value.toInt()
                    } else {
                        stylesheet.getSubtitleStyle().duration ?: SUBTITLE_DEFAULT_DURATION
                    }

                    updateSubtitle(statement.text, duration)
                    EmptyValue
                } else {
                    EmptyValue
                }
            }
            else -> EmptyValue
        }

        private fun updateSubtitle(text: String, duration: Int) {
            if (subtitleBlockVariable is EmptyMObject) {
                val dsUID = "_subtitle"
                dataStructureBoundaries[dsUID] = WideBoundary(maxSize = Int.MAX_VALUE)
                val position = stylesheet.getPosition(dsUID)
                val boundaries = if (position == null) emptyList() else getBoundaries(position)
                subtitleBlockVariable = SubtitleBlock(
                    variableNameGenerator,
                    runtime = animationSpeeds.first(),
                    boundary = boundaries,
                    textColor = stylesheet.getSubtitleStyle().textColor,
                    duration = duration
                )
                linearRepresentation.add(subtitleBlockVariable)
            }
            linearRepresentation.add(
                UpdateSubtitle(
                    subtitleBlockVariable.shape as SubtitleBlockShape,
                    wrapString(text, 30),
                    runtime = animationSpeeds.first()
                )
            )
        }

        private fun executeLoopStatement(statement: LoopStatementNode): ExecValue = when (statement) {
            is BreakNode -> {
                pc = statement.loopEndLineNumber
                BreakValue
            }
            is ContinueNode -> {
                pc = statement.loopStartLineNumber
                ContinueValue
            }
        }

        private fun executeSleep(statement: SleepNode): ExecValue {
            addSleep((executeExpression(statement.sleepTime) as DoubleValue).value)
            return EmptyValue
        }

        private fun addSleep(length: Double) {
            linearRepresentation.add(Sleep(length, runtime = animationSpeeds.first()))
        }

        private fun moveToLine(line: Int = pc) {
            if (showMoveToLine && !hideCode && !fileLines[line - 1].isEmpty()) {
                linearRepresentation.add(
                    MoveToLine(
                        displayLine[line - 1],
                        pointerVariable,
                        codeBlockVariable,
                        codeTextVariable,
                        runtime = animationSpeeds.first()
                    )
                )
            }
        }

        private fun executeFunctionCall(statement: FunctionCallNode): ExecValue {
            // create new stack frame with argument variables
            val executedArguments = mutableListOf<ExecValue>()
            statement.arguments.forEach {
                val executed = executeExpression(it)
                if (executed is RuntimeError)
                    return executed
                else
                    executedArguments.add(executed)
            }
            val argumentNames =
                (symbolTableVisitor.getData(statement.functionIdentifier) as FunctionData).parameters.map { it.identifier }
            val argumentVariables = (argumentNames zip executedArguments).toMap().toMutableMap()
            val functionNode = program.functions.find { it.identifier == statement.functionIdentifier }!!
            val finalStatementLine = functionNode.statements.last().lineNumber

            val localDataStructure = mutableSetOf<String>()

            // program counter will forward in loop, we have popped out of stack
            val returnValue = Frame(
                functionNode.lineNumber,
                finalStatementLine,
                argumentVariables,
                depth + 1,
                showMoveToLine = stepInto,
                stepInto = stepInto && previousStepIntoState, // In the case of nested stepInto/stepOver
                updateVariableState = updateVariableState,
                hideCode = hideCode,
                functionNamePrefix = "${functionNode.identifier}.",
                localDataStructure = localDataStructure
            ).runFrame()

            if (localDataStructure.isNotEmpty()) {
                linearRepresentation.add(CleanUpLocalDataStructures(localDataStructure, animationSpeeds.first()))
            }

            // to visualise popping back to assignment we can move pointer to the prior statement again
            if (stepInto) moveToLine()
            return returnValue
        }

        private fun fetchNextStatement() {
            ++pc
        }

        private fun executeAssignment(node: DeclarationOrAssignment): ExecValue {
            if (node.identifier is IdentifierNode && variables.containsKey(node.identifier.identifier)) {
                with(variables[node.identifier.identifier]?.manimObject) {
                    if (this is DataStructureMObject) {
                        linearRepresentation.add(CleanUpLocalDataStructures(setOf(this.ident), animationSpeeds.first()))
                    }
                }
            }

            val assignedValue = executeExpression(node.expression, identifier = node.identifier)
            return if (assignedValue is RuntimeError) {
                assignedValue
            } else {
                with(node.identifier) {
                    when (this) {
                        is BinaryTreeNodeAccess -> return btExecutor.executeTreeAssignment(this, assignedValue)
                        is ArrayElemNode -> return arrExecutor.executeArrayElemAssignment(this, assignedValue)
                        is IdentifierNode -> {
                            if (assignedValue is BinaryTreeNodeValue && assignedValue.binaryTreeValue != null) {
                                linearRepresentation.add(
                                    TreeNodeRestyle(
                                        assignedValue.manimObject.shape.ident,
                                        assignedValue.binaryTreeValue!!.animatedStyle!!,
                                        assignedValue.binaryTreeValue!!.animatedStyle!!.highlight,
                                        runtime = animationSpeeds.first(),
                                        render = stylesheet.renderDataStructure(functionNamePrefix + node.identifier)
                                    )
                                )
                                linearRepresentation.add(
                                    TreeNodeRestyle(
                                        assignedValue.manimObject.shape.ident,
                                        assignedValue.binaryTreeValue!!.style,
                                        runtime = animationSpeeds.first(),
                                        render = stylesheet.renderDataStructure(functionNamePrefix + node.identifier)
                                    )
                                )
                            }
                            if (localDataStructure != null && node is DeclarationNode && assignedValue.manimObject is DataStructureMObject) {
                                localDataStructure.add(node.identifier.identifier)
                            }
                            if (node.expression is FunctionCallNode && assignedValue.manimObject is DataStructureMObject) {
                                val constructor = makeConstructorNode(assignedValue, node.lineNumber)
                                val rhs = executeConstructor(constructor, node.identifier)
                                variables[node.identifier.identifier] = rhs
                            } else {
                                variables[node.identifier.identifier] = assignedValue
                            }

                            insertVariable(node.identifier.identifier, assignedValue)
                            updateVariableState()
                        }
                    }
                }
                EmptyValue
            }
        }

        fun updateVariableState() {
            if (showMoveToLine && !hideCode && !hideVariables)
                linearRepresentation.add(
                    UpdateVariableState(
                        getVariableState(),
                        "variable_block",
                        runtime = animationSpeeds.first()
                    )
                )
        }

        fun executeExpression(
            node: ExpressionNode,
            insideMethodCall: Boolean = false,
            identifier: AssignLHS = EmptyLHS,
        ): ExecValue = when (node) {
            is IdentifierNode -> variables[node.identifier]!!
            is NumberNode -> DoubleValue(node.double)
            is CharNode -> CharValue(node.value)
            is MethodCallNode -> executeMethodCall(node, insideMethodCall, true)
            is AddExpression -> executeBinaryOp(node) { x, y -> x + y }
            is SubtractExpression -> executeBinaryOp(node) { x, y -> x - y }
            is DivideExpression -> executeBinaryOp(node) { x, y -> x / y }
            is MultiplyExpression -> executeBinaryOp(node) { x, y -> x * y }
            is PlusExpression -> executeUnaryOp(node) { x -> x }
            is MinusExpression -> executeUnaryOp(node) { x -> DoubleValue(-(x as DoubleValue).value) }
            is BoolNode -> BoolValue(node.value)
            is AndExpression -> executeShortCircuitOp(
                node,
                false
            ) { x, y -> BoolValue((x as BoolValue).value && (y as BoolValue).value) }
            is OrExpression -> executeShortCircuitOp(
                node,
                true
            ) { x, y -> BoolValue((x as BoolValue).value || (y as BoolValue).value) }
            is EqExpression -> executeBinaryOp(node) { x, y -> BoolValue(x == y) }
            is NeqExpression -> executeBinaryOp(node) { x, y -> BoolValue(x != y) }
            is GtExpression -> executeBinaryOp(node) { x, y -> BoolValue(x > y) }
            is LtExpression -> executeBinaryOp(node) { x, y -> BoolValue(x < y) }
            is GeExpression -> executeBinaryOp(node) { x, y -> BoolValue(x >= y) }
            is LeExpression -> executeBinaryOp(node) { x, y -> BoolValue(x <= y) }
            is NotExpression -> executeUnaryOp(node) { x -> BoolValue(!x) }
            is ConstructorNode -> executeConstructor(node, identifier)
            is FunctionCallNode -> executeFunctionCall(node)
            is VoidNode -> VoidValue
            is ArrayElemNode -> arrExecutor.executeArrayElem(node, identifier)
            is BinaryTreeNodeElemAccessNode -> btExecutor.executeTreeAccess(
                variables[node.identifier]!! as BinaryTreeNodeValue,
                node
            ).second
            is BinaryTreeRootAccessNode -> btExecutor.executeRootAccess(node).second
            is NullNode -> NullValue
            is CastExpressionNode -> executeCastExpression(node)
            is InternalArrayMethodCallNode -> arrExecutor.executeInternalArrayMethodCall(node)
        }

        private fun executeCastExpression(node: CastExpressionNode): ExecValue {
            val exprValue = executeExpression(node.expr)

            return when (node.targetType) {
                is CharType -> CharValue((exprValue as DoubleAlias).toDouble().toChar())
                is NumberType -> DoubleValue((exprValue as DoubleAlias).toDouble())
                else -> throw UnsupportedOperationException("Not implemented yet")
            }
        }

        private fun executeMethodCall(
            node: MethodCallNode,
            insideMethodCall: Boolean,
            isExpression: Boolean
        ): ExecValue {
            return when (val ds = variables[node.instanceIdentifier]) {
                is StackValue -> {
                    return stackExecutor.executeStackMethodCall(node, ds, insideMethodCall, isExpression)
                }
                is ArrayValue -> {
                    return arrExecutor.executeArrayMethodCall(node, ds)
                }
                is Array2DValue -> {
                    return arrExecutor.execute2DArrayMethodCall(node, ds)
                }
                else -> EmptyValue
            }
        }

        private fun executeConstructor(node: ConstructorNode, assignLHS: AssignLHS): ExecValue {
            val dsUID = functionNamePrefix + assignLHS.identifier
            return when (node.type) {
                is StackType -> stackExecutor.executeConstructor(node, dsUID, assignLHS)
                is ArrayType -> arrExecutor.executeConstructor(node, dsUID, assignLHS)
                is TreeType, is NodeType -> btExecutor.executeConstructor(node, dsUID, assignLHS)
            }
        }

        private fun executeUnaryOp(node: UnaryExpression, op: (first: ExecValue) -> ExecValue): ExecValue {
            val subExpression = executeExpression(node.expr)
            return if (subExpression is RuntimeError) {
                subExpression
            } else {
                op(subExpression)
            }
        }

        // Used for and and or to short-circuit with first value
        private fun executeShortCircuitOp(
            node: BinaryExpression,
            shortCircuitValue: Boolean,
            op: (first: ExecValue, seconds: ExecValue) -> ExecValue
        ): ExecValue {

            val leftExpression = executeExpression(node.expr1)
            if (leftExpression is RuntimeError || leftExpression.value == shortCircuitValue) {
                return leftExpression
            }
            val rightExpression = executeExpression(node.expr2)

            if (rightExpression is RuntimeError) {
                return rightExpression
            }
            return op(
                leftExpression,
                rightExpression
            )
        }

        private fun executeBinaryOp(
            node: BinaryExpression,
            op: (first: ExecValue, seconds: ExecValue) -> ExecValue
        ): ExecValue {

            val leftExpression = executeExpression(node.expr1)
            if (leftExpression is RuntimeError) {
                return leftExpression
            }
            val rightExpression = executeExpression(node.expr2)

            if (rightExpression is RuntimeError) {
                return rightExpression
            }
            return op(
                leftExpression,
                rightExpression
            )
        }

        private fun executeWhileStatement(whileStatementNode: WhileStatementNode): ExecValue {
            if (showMoveToLine && !hideCode) addSleep(animationSpeeds.first() * 0.5)

            var conditionValue: ExecValue
            var execValue: ExecValue
            var loopCount = 0
            val prevShowMoveToLine = showMoveToLine

            while (loopCount < MAX_NUMBER_OF_LOOPS) {
                conditionValue = executeExpression(whileStatementNode.condition)
                if (conditionValue is RuntimeError) {
                    return conditionValue
                } else if (conditionValue is BoolValue) {
                    if (!conditionValue.value) {
                        showMoveToLine = prevShowMoveToLine
                        pc = whileStatementNode.endLineNumber
                        return EmptyValue
                    } else {
                        showMoveToLine = stepInto
                        pc = whileStatementNode.lineNumber
                    }
                }

                val localDataStructure = mutableSetOf<String>()

                execValue = Frame(
                    whileStatementNode.statements.first().lineNumber,
                    whileStatementNode.statements.last().lineNumber,
                    variables,
                    depth,
                    showMoveToLine = stepInto,
                    stepInto = stepInto && previousStepIntoState,
                    hideCode = hideCode,
                    localDataStructure = localDataStructure
                ).runFrame()

                when (execValue) {
                    is BreakValue -> {
                        showMoveToLine = prevShowMoveToLine
                        pc = whileStatementNode.endLineNumber
                        moveToLine()
                        return EmptyValue
                    }
                    is ContinueValue -> {
                        pc = whileStatementNode.lineNumber
                        moveToLine()
                        continue
                    }
                    !is EmptyValue -> {
                        return execValue
                    }
                }

                if (localDataStructure.isNotEmpty()) {
                    linearRepresentation.add(CleanUpLocalDataStructures(localDataStructure, animationSpeeds.first()))
                }
                pc = whileStatementNode.lineNumber
                moveToLine()
                loopCount++
            }

            return RuntimeError("Max number of loop executions exceeded", lineNumber = whileStatementNode.lineNumber)
        }

        private fun removeForLoopCounter(forStatementNode: ForStatementNode) {
            val identifier = forStatementNode.beginStatement.identifier.identifier
            val index = displayedDataMap.filterValues { it.first == identifier }.keys
            displayedDataMap.remove(index.first())
            variables.remove(identifier)
        }

        private fun executeForStatement(forStatementNode: ForStatementNode): ExecValue {

            var conditionValue: ExecValue
            var execValue: ExecValue
            var loopCount = 0
            val prevShowMoveToLine = showMoveToLine

            executeAssignment(forStatementNode.beginStatement)

            val start = executeExpression(forStatementNode.beginStatement.expression) as DoubleAlias
            val end = executeExpression(forStatementNode.endCondition) as DoubleAlias
            val lineNumber = forStatementNode.lineNumber

            val condition = if (start < end) {
                LtExpression(
                    lineNumber,
                    IdentifierNode(lineNumber, forStatementNode.beginStatement.identifier.identifier),
                    NumberNode(lineNumber, end.toDouble())
                )
            } else {
                GtExpression(
                    lineNumber,
                    IdentifierNode(lineNumber, forStatementNode.beginStatement.identifier.identifier),
                    NumberNode(lineNumber, end.toDouble())
                )
            }

            while (loopCount < MAX_NUMBER_OF_LOOPS) {
                conditionValue = executeExpression(condition)
                if (conditionValue is RuntimeError) {
                    return conditionValue
                } else if (conditionValue is BoolValue) {
                    if (!conditionValue.value) {
                        showMoveToLine = prevShowMoveToLine
                        removeForLoopCounter(forStatementNode)
                        pc = forStatementNode.endLineNumber
                        moveToLine()
                        return EmptyValue
                    } else {
                        showMoveToLine = stepInto
                        pc = forStatementNode.lineNumber
                    }
                }

                val localDataStructure = mutableSetOf<String>()

                execValue = Frame(
                    forStatementNode.statements.first().lineNumber,
                    forStatementNode.statements.last().lineNumber,
                    variables,
                    depth,
                    showMoveToLine = stepInto,
                    stepInto = stepInto && previousStepIntoState,
                    hideCode = hideCode,
                    displayedDataMap = displayedDataMap,
                    localDataStructure = localDataStructure
                ).runFrame()

                when (execValue) {
                    is BreakValue -> {
                        showMoveToLine = prevShowMoveToLine
                        removeForLoopCounter(forStatementNode)
                        pc = forStatementNode.endLineNumber
                        moveToLine()
                        return EmptyValue
                    }
                    is ContinueValue -> {
                        executeAssignment(forStatementNode.updateCounter)
                        pc = forStatementNode.lineNumber
                        moveToLine()
                        continue
                    }
                    !is EmptyValue -> {
                        return execValue
                    }
                }

                if (showMoveToLine && !hideCode) addSleep(animationSpeeds.first() * 0.5)
                executeAssignment(forStatementNode.updateCounter)
                if (localDataStructure.isNotEmpty()) {
                    linearRepresentation.add(CleanUpLocalDataStructures(localDataStructure, animationSpeeds.first()))
                }
                pc = forStatementNode.lineNumber
                moveToLine()
                loopCount++
            }

            return RuntimeError("Max number of loop executions exceeded", lineNumber = forStatementNode.lineNumber)
        }

        private fun executeIfStatement(ifStatementNode: IfStatementNode): ExecValue {
            if (showMoveToLine && !hideCode) addSleep(animationSpeeds.first() * 0.5)
            var conditionValue = executeExpression(ifStatementNode.condition)
            if (conditionValue is RuntimeError) {
                return conditionValue
            } else {
                conditionValue = conditionValue as BoolValue
            }
            // Set pc to end of if statement as branching is handled here
            pc = ifStatementNode.endLineNumber
            val localDataStructure = mutableSetOf<String>()

            // If
            if (conditionValue.value) {
                val execValue = Frame(
                    ifStatementNode.statements.first().lineNumber,
                    ifStatementNode.statements.last().lineNumber,
                    variables,
                    depth,
                    showMoveToLine = showMoveToLine,
                    stepInto = stepInto,
                    updateVariableState = updateVariableState,
                    hideCode = hideCode,
                    localDataStructure = localDataStructure
                ).runFrame()
                if (execValue is EmptyValue) {
                    pc = ifStatementNode.endLineNumber
                }
                if (localDataStructure.isNotEmpty()) {
                    linearRepresentation.add(CleanUpLocalDataStructures(localDataStructure, animationSpeeds.first()))
                }
                return execValue
            }

            // Elif
            for (elif in ifStatementNode.elifs) {
                moveToLine(elif.lineNumber)
                if (showMoveToLine && !hideCode) addSleep(animationSpeeds.first() * 0.5)
                // Add statement to code
                conditionValue = executeExpression(elif.condition) as BoolValue
                if (conditionValue.value) {
                    val execValue = Frame(
                        elif.statements.first().lineNumber,
                        elif.statements.last().lineNumber,
                        variables,
                        depth,
                        showMoveToLine = showMoveToLine,
                        stepInto = stepInto,
                        updateVariableState = updateVariableState,
                        hideCode = hideCode,
                        localDataStructure = localDataStructure
                    ).runFrame()
                    if (execValue is EmptyValue) {
                        pc = ifStatementNode.endLineNumber
                    }
                    if (localDataStructure.isNotEmpty()) {
                        linearRepresentation.add(
                            CleanUpLocalDataStructures(
                                localDataStructure,
                                animationSpeeds.first()
                            )
                        )
                    }
                    return execValue
                }
            }

            // Else
            if (ifStatementNode.elseBlock.statements.isNotEmpty()) {
                moveToLine(ifStatementNode.elseBlock.lineNumber)
                if (showMoveToLine && !hideCode) addSleep(animationSpeeds.first() * 0.5)
                val execValue = Frame(
                    ifStatementNode.elseBlock.statements.first().lineNumber,
                    ifStatementNode.elseBlock.statements.last().lineNumber,
                    variables,
                    depth,
                    showMoveToLine = showMoveToLine,
                    stepInto = stepInto,
                    updateVariableState = updateVariableState,
                    hideCode = hideCode,
                    localDataStructure = localDataStructure
                ).runFrame()
                if (execValue is EmptyValue) {
                    pc = ifStatementNode.endLineNumber
                }
                if (localDataStructure.isNotEmpty()) {
                    linearRepresentation.add(CleanUpLocalDataStructures(localDataStructure, animationSpeeds.first()))
                }
                return execValue
            }
            return EmptyValue
        }
    }
}
