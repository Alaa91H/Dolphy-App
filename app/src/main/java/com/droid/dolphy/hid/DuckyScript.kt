package com.droid.dolphy.hid

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.pow
import kotlin.random.Random

/**
 * DuckyScript 3.0 compiler and interpreter.
 *
 * The compiler turns payload source into a small node tree; the [DuckyRunner]
 * executes that tree against any [HidController], so the same payload runs over
 * the Bluetooth HID session and over the rooted USB gadget.
 *
 * Implemented: comments (`REM`, `REM_BLOCK`), `DEFAULT_DELAY`, `DELAY`, `STRING`,
 * `STRINGLN`, string blocks, key and modifier combos, `INJECT_MOD`, `HOLD`,
 * `RELEASE`, `REPEAT`, `DEFINE`, `VAR`, arithmetic/comparison/logical/bitwise
 * expressions, `IF`/`ELSE`, `WHILE`, `FUNCTION`/`RETURN`, `RANDOM_*` keystrokes,
 * `MEDIA_*` consumer keys, `MOUSE_*` moves and clicks, and payload control
 * (`STOP_PAYLOAD`, `RESTART_PAYLOAD`, `RESET`).
 *
 * Device-only commands (`ATTACKMODE`, `LED_*`, `BUTTON_*`, `WAIT_FOR_*`,
 * `HIDE_PAYLOAD`, `MATCH`, `SAVEKEYS`, `JITTER`) are accepted as no-ops with a
 * warning so unmodified Hak5 and Flipper payloads still compile.
 */
data class DuckyDiagnostic(
    val line: Int,
    val message: String,
    val fatal: Boolean = false,
)

data class DuckyProgram(
    val nodes: List<DuckyNode>,
    val constants: Map<String, Int> = emptyMap(),
    val functions: Map<String, List<DuckyNode>> = emptyMap(),
    val diagnostics: List<DuckyDiagnostic> = emptyList(),
) {
    val errors: List<DuckyDiagnostic> get() = diagnostics.filter { it.fatal }
    val warnings: List<DuckyDiagnostic> get() = diagnostics.filterNot { it.fatal }
    val isValid: Boolean get() = errors.isEmpty()
    val stepCount: Int get() = countSteps(nodes) + functions.values.sumOf(::countSteps)

    private fun countSteps(list: List<DuckyNode>): Int = list.sumOf { node ->
        when (node) {
            is DuckyNode.Repeat -> 1 + countSteps(node.body)
            is DuckyNode.If -> 1 + countSteps(node.then) + countSteps(node.otherwise)
            is DuckyNode.While -> 1 + countSteps(node.body)
            else -> 1
        }
    }
}

/** Arithmetic, comparison, logical and bitwise expressions used by variables and conditions. */
sealed interface DuckyExpr {
    fun eval(env: DuckyEnvironment): Int

    data class Const(val value: Int) : DuckyExpr {
        override fun eval(env: DuckyEnvironment): Int = value
    }

    data class Var(val name: String) : DuckyExpr {
        override fun eval(env: DuckyEnvironment): Int = env[name] ?: 0
    }

    data class Unary(val op: String, val operand: DuckyExpr) : DuckyExpr {
        override fun eval(env: DuckyEnvironment): Int {
            val value = operand.eval(env)
            return when (op) {
                "!" -> if (value == 0) 1 else 0
                "-" -> -value
                "~" -> value.inv()
                else -> value
            }
        }
    }

    data class Binary(val op: String, val left: DuckyExpr, val right: DuckyExpr) : DuckyExpr {
        override fun eval(env: DuckyEnvironment): Int {
            val l = left.eval(env)
            if (op == "&&") return bool(l != 0 && right.eval(env) != 0)
            if (op == "||") return bool(l != 0 || right.eval(env) != 0)
            return combine(l, right.eval(env))
        }

        private fun combine(l: Int, r: Int): Int =
            if (op in COMPARISONS) compare(l, r) else arithmetic(l, r)

        private fun compare(l: Int, r: Int): Int = when (op) {
            "==" -> bool(l == r)
            "!=" -> bool(l != r)
            "<" -> bool(l < r)
            "<=" -> bool(l <= r)
            ">" -> bool(l > r)
            ">=" -> bool(l >= r)
            else -> 0
        }

        private fun arithmetic(l: Int, r: Int): Int = when (op) {
            "+" -> l + r
            "-" -> l - r
            "*" -> l * r
            "/" -> if (r == 0) 0 else l / r
            "%" -> if (r == 0) 0 else l % r
            "^" -> l.toDouble().pow(r.toDouble()).toInt()
            "&" -> l and r
            "|" -> l or r
            "<<" -> l shl r
            ">>" -> l shr r
            else -> 0
        }

        private fun bool(value: Boolean): Int = if (value) 1 else 0

        private companion object {
            val COMPARISONS = setOf("==", "!=", "<", "<=", ">", ">=")
        }
    }
}

/** Variables live for one run; constants are resolved by the compiler. */
class DuckyEnvironment(private val constants: Map<String, Int> = emptyMap()) {
    private val variables = LinkedHashMap<String, Int>()

    operator fun get(name: String): Int? = variables[name] ?: constants[name]

    operator fun set(name: String, value: Int) {
        variables[name] = value.coerceIn(0, 0xFFFF)
    }

    fun snapshot(): Map<String, Int> = LinkedHashMap(variables)
}

sealed interface DuckyNode {
    data class Delay(val millis: DuckyExpr) : DuckyNode
    data class SetDefaultDelay(val millis: DuckyExpr) : DuckyNode
    data class Text(val text: String, val submit: Boolean = false) : DuckyNode
    data class Keys(val modifiers: List<String>, val key: String?) : DuckyNode
    data class Hold(val keys: List<String>) : DuckyNode
    data class Release(val keys: List<String>) : DuckyNode
    data class Repeat(val times: DuckyExpr, val body: List<DuckyNode>) : DuckyNode
    data class If(val condition: DuckyExpr, val then: List<DuckyNode>, val otherwise: List<DuckyNode>) : DuckyNode
    data class While(val condition: DuckyExpr, val body: List<DuckyNode>) : DuckyNode
    data class Call(val name: String) : DuckyNode
    data class Return(val value: DuckyExpr?) : DuckyNode
    data class Assign(val name: String, val value: DuckyExpr) : DuckyNode
    data class Media(val key: String) : DuckyNode
    data class MouseMove(val deltaX: DuckyExpr, val deltaY: DuckyExpr) : DuckyNode
    data class MouseClick(val button: Int) : DuckyNode
    data class MouseScroll(val delta: DuckyExpr) : DuckyNode
    data class RandomKeystrokes(val charset: String) : DuckyNode
    data object Reset : DuckyNode
    data object Stop : DuckyNode
    data object Restart : DuckyNode
    data class Noop(val command: String) : DuckyNode
}

data class DuckyRunResult(
    val executed: Int,
    val elapsedMs: Long,
    val stopped: Boolean,
    val restarts: Int,
    val variables: Map<String, Int>,
)

/** Compiles payload source into an executable [DuckyProgram]. */
object DuckyScript {

    private val DEVICE_ONLY = setOf(
        "ATTACKMODE", "SAVE_ATTACKMODE", "RESTORE_ATTACKMODE",
        "BUTTON_DEF", "WAIT_FOR_BUTTON_PRESS", "DISABLE_BUTTON", "ENABLE_BUTTON",
        "LED_OFF", "LED_R", "LED_G", "LED_B", "LED_RGB",
        "HIDE_PAYLOAD", "RESTORE_PAYLOAD",
        "WAIT_FOR_CAPS_ON", "WAIT_FOR_CAPS_OFF", "WAIT_FOR_CAPS_CHANGE",
        "WAIT_FOR_NUM_ON", "WAIT_FOR_NUM_OFF", "WAIT_FOR_NUM_CHANGE",
        "WAIT_FOR_SCROLL_ON", "WAIT_FOR_SCROLL_OFF", "WAIT_FOR_SCROLL_CHANGE",
        "SAVE_HOST_KEYBOARD_LOCK_STATE", "RESTORE_HOST_KEYBOARD_LOCK_STATE",
        "MATCH", "SAVEKEYS", "JITTER",
    )

    private val MEDIA_ALIASES = mapOf(
        "VOLUMEUP" to "VOLUMEUP",
        "VOLUMEDOWN" to "VOLUMEDOWN",
        "MUTE" to "VOLUMEMUTE",
        "PLAYPAUSE" to "PLAYPAUSE",
        "MEDIA_PLAY_PAUSE" to "PLAYPAUSE",
    )

    private val RANDOM_SETS = mapOf(
        "RANDOM_LOWERCASE_LETTER" to "abcdefghijklmnopqrstuvwxyz",
        "RANDOM_UPPERCASE_LETTER" to "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        "RANDOM_LETTER" to "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
        "RANDOM_NUMBER" to "0123456789",
        "RANDOM_SPECIAL" to "!@#$%^&*()",
        "RANDOM_CHAR" to "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()",
    )

    /** Modifier names, cursor keys and AOSP key names accepted by payloads. */
    internal val TOKEN_ALIASES: Map<String, String> = mapOf(
        "CTRL" to "MOD_LCTRL",
        "CONTROL" to "MOD_LCTRL",
        "SHIFT" to "MOD_LSHIFT",
        "ALT" to "MOD_LALT",
        "ALTGR" to "MOD_RALT",
        "GUI" to "MOD_LMETA",
        "WINDOWS" to "MOD_LMETA",
        "WIN" to "MOD_LMETA",
        "COMMAND" to "MOD_LMETA",
        "MENU" to "APPLICATION",
        "APP" to "APPLICATION",
        "APPLICATION" to "APPLICATION",
        "ENTER" to "ENTER",
        "RETURN" to "ENTER",
        "ESC" to "ESC",
        "ESCAPE" to "ESC",
        "UPARROW" to "UP",
        "UP" to "UP",
        "DOWNARROW" to "DOWN",
        "DOWN" to "DOWN",
        "LEFTARROW" to "LEFT",
        "LEFT" to "LEFT",
        "RIGHTARROW" to "RIGHT",
        "RIGHT" to "RIGHT",
        "DEL" to "DELETE",
        "DELETE" to "DELETE",
        "PAGEUP" to "PAGEUP",
        "PAGE_UP" to "PAGEUP",
        "PAGEDOWN" to "PAGEDOWN",
        "PAGE_DOWN" to "PAGEDOWN",
        "CAPS_LOCK" to "CAPSLOCK",
        "CAPSLOCK" to "CAPSLOCK",
        "NUM_LOCK" to "NUMLOCK",
        "NUMLOCK" to "NUMLOCK",
        "SCROLL_LOCK" to "SCROLLLOCK",
        "SCROLLLOCK" to "SCROLLLOCK",
        "PRINTSCREEN" to "SYSRQ",
        "PRINT_SCREEN" to "SYSRQ",
        "BREAK" to "PAUSE",
        "PAUSE" to "PAUSE",
        "SPACE" to "SPACE",
    )

    private val CALL_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*\\s*\\(\\s*\\)$")

    private val STRAY_KEYWORDS = setOf("ELSE", "END_IF", "END_WHILE", "END_FUNCTION", "END_REM")

    fun parse(source: String): DuckyProgram = Parser(source).parse()

    /** Resolves a single payload token (modifier, key name or letter) to a HID key. */
    internal fun mapToken(token: String): String {
        TOKEN_ALIASES[token]?.let { return it }
        return when {
            token.length == 1 && token[0].isLetterOrDigit() -> token
            token in keyCodes -> token
            else -> ""
        }
    }

    private fun commandOf(text: String): String =
        text.trim().substringBefore(' ').substringBefore('\t').uppercase()

    private fun argumentOf(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val separator = trimmed.indexOfFirst { it == ' ' || it == '\t' }
        return if (separator < 0) "" else trimmed.substring(separator + 1)
    }

    private class Line(val number: Int, val text: String)

    private class Block(val nodes: List<DuckyNode>, val terminator: String?)

    private class Parser(source: String) {
        private val lines: List<Line> = source
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .mapIndexed { index, text -> Line(index + 1, text.trimEnd()) }

        private var index = 0
        private val constants = LinkedHashMap<String, Int>()
        private val functions = LinkedHashMap<String, List<DuckyNode>>()
        private val diagnostics = mutableListOf<DuckyDiagnostic>()

        fun parse(): DuckyProgram {
            val block = parseBlock(emptySet())
            return DuckyProgram(block.nodes, constants.toMap(), functions.toMap(), diagnostics.toList())
        }

        private fun parseBlock(stopKeywords: Set<String>): Block {
            val nodes = mutableListOf<DuckyNode>()
            while (index < lines.size) {
                val line = lines[index]
                val command = commandOf(line.text)
                if (command in stopKeywords) {
                    index++
                    return Block(nodes, command)
                }
                index++
                when (command) {
                    "", "REM" -> Unit
                    "REM_BLOCK" -> skipUntil("END_REM", line.number)
                    "IF" -> nodes += parseIf(line)
                    "WHILE" -> nodes += parseWhile(line)
                    "FUNCTION" -> parseFunction(line)
                    "REPEAT" -> repeatPrevious(nodes, line)
                    else -> {
                        if (command in STRAY_KEYWORDS) {
                            warning(line.number, "Unexpected $command")
                        } else {
                            parseSimple(line)?.let { nodes += it }
                        }
                    }
                }
            }
            return Block(nodes, null)
        }

        private fun parseWhile(line: Line): DuckyNode {
            val condition = expression(argumentOf(line.text), line.number)
            val body = parseBlock(setOf("END_WHILE"))
            if (body.terminator == null) warning(line.number, "WHILE without END_WHILE")
            return DuckyNode.While(condition, body.nodes)
        }

        private fun parseFunction(line: Line) {
            val name = functionName(argumentOf(line.text))
            if (name.isBlank()) warning(line.number, "FUNCTION without a name")
            val body = parseBlock(setOf("END_FUNCTION"))
            if (body.terminator == null) warning(line.number, "FUNCTION without END_FUNCTION")
            if (name.isNotBlank()) functions[name] = body.nodes
        }

        private fun repeatPrevious(nodes: MutableList<DuckyNode>, line: Line) {
            val times = expression(argumentOf(line.text).ifBlank { "1" }, line.number)
            val target = nodes.removeLastOrNull()
            if (target == null) {
                warning(line.number, "REPEAT without a previous command")
            } else {
                nodes += DuckyNode.Repeat(times, listOf(target))
            }
        }

        private fun parseIf(header: Line): DuckyNode {
            val conditionText = argumentOf(header.text).removeSuffix("THEN").removeSuffix("then").trim()
            val condition = expression(conditionText, header.number)
            val thenBlock = parseBlock(setOf("ELSE", "END_IF"))
            if (thenBlock.terminator == null) warning(header.number, "IF without END_IF")
            val otherwise = if (thenBlock.terminator == "ELSE") parseElse() else emptyList()
            return DuckyNode.If(condition, thenBlock.nodes, otherwise)
        }

        private fun parseElse(): List<DuckyNode> {
            val next = lines.getOrNull(index) ?: return emptyList()
            if (commandOf(next.text) == "IF") {
                index++
                return listOf(parseIf(next))
            }
            return parseBlock(setOf("END_IF")).nodes
        }

        private fun skipUntil(keyword: String, startLine: Int) {
            while (index < lines.size) {
                val current = commandOf(lines[index].text)
                index++
                if (current == keyword) return
            }
            warning(startLine, "REM_BLOCK without END_REM")
        }

        private fun parseSimple(line: Line): DuckyNode? {
            val text = line.text.trim()
            if (text.isEmpty() || text.startsWith("//") || text.startsWith("#")) return null
            val command = commandOf(text)
            val argument = argumentOf(text).trim()
            return when (command) {
                "REM" -> null
                "STOP_PAYLOAD" -> DuckyNode.Stop
                "RESTART_PAYLOAD" -> DuckyNode.Restart
                "RESET" -> DuckyNode.Reset
                "DEFINE" -> defineConstant(argument, line)
                "VAR" -> assignVariable(argument, line)
                "INJECT_MOD" -> injectMod(argument, line)
                else -> typedCommand(command, argument, text, line)
            }
        }

        private fun typedCommand(command: String, argument: String, text: String, line: Line): DuckyNode? = when {
            command == "DEFAULT_DELAY" || command == "DEFAULTDELAY" ->
                DuckyNode.SetDefaultDelay(expression(argument, line.number))
            command == "DELAY" -> DuckyNode.Delay(expression(argument.ifBlank { "0" }, line.number))
            command == "STRING" || command == "STRINGLN" -> parseStringBlock(command, argument, line)
            command == "HOLD" -> DuckyNode.Hold(keysOf(argument, line.number))
            command == "RELEASE" -> DuckyNode.Release(keysOf(argument, line.number))
            else -> extendedCommand(command, argument, text, line)
        }

        private fun extendedCommand(command: String, argument: String, text: String, line: Line): DuckyNode? = when {
            command.startsWith("MEDIA_") -> mediaCommand(command, line)
            command in MEDIA_ALIASES -> DuckyNode.Media(MEDIA_ALIASES.getValue(command))
            command == "MOUSE_MOVE" -> mouseMove(argument, line)
            command == "MOUSE_CLICK" -> DuckyNode.MouseClick(buttonOf(argument))
            command == "MOUSE_SCROLL" || command == "MOUSE_WHEEL" ->
                DuckyNode.MouseScroll(expression(argument.ifBlank { "0" }, line.number))
            command in RANDOM_SETS -> DuckyNode.RandomKeystrokes(RANDOM_SETS.getValue(command))
            CALL_PATTERN.matches(text) -> DuckyNode.Call(functionName(text))
            command in DEVICE_ONLY -> deviceOnly(command, line)
            else -> keyNodeOf(text, line.number)
        }

        private fun mediaCommand(command: String, line: Line): DuckyNode? {
            val key = command.removePrefix("MEDIA_")
            if (key in consumerControlCodes) return DuckyNode.Media(key)
            warning(line.number, "$command is not a media key")
            return null
        }

        private fun mouseMove(argument: String, line: Line): DuckyNode? {
            val parts = argument.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.size != 2) {
                warning(line.number, "MOUSE_MOVE needs X and Y")
                return null
            }
            return DuckyNode.MouseMove(expression(parts[0], line.number), expression(parts[1], line.number))
        }

        private fun deviceOnly(command: String, line: Line): DuckyNode {
            warning(line.number, "$command is device specific and is ignored")
            return DuckyNode.Noop(command)
        }

        private fun defineConstant(argument: String, line: Line): DuckyNode? {
            val name = argument.substringBefore(' ').trim()
            val value = argument.substringAfter(' ', "").trim()
            val resolved = resolveConstant(value)
            when {
                name.isBlank() -> warning(line.number, "DEFINE without a name")
                resolved == null -> warning(line.number, "DEFINE $name has an unsupported value")
                else -> constants[name] = resolved
            }
            return null
        }

        private fun assignVariable(argument: String, line: Line): DuckyNode? {
            val name = argument.substringBefore(' ').trim().removePrefix("$")
            val assignment = argument.substringAfter(' ', "").trim()
            val value = assignment.substringAfter('=', "").trim()
            if (name.isBlank() || !argument.contains('=')) {
                warning(line.number, "VAR needs a name and an assignment")
                return null
            }
            return DuckyNode.Assign(name, expression(value, line.number))
        }

        private fun injectMod(argument: String, line: Line): DuckyNode? {
            val key = mapToken(argument.uppercase())
            if (key.isBlank()) {
                warning(line.number, "INJECT_MOD ${argument.uppercase()} is not a key")
                return null
            }
            return DuckyNode.Keys(emptyList(), key)
        }

        private fun parseStringBlock(command: String, argument: String, line: Line): DuckyNode? {
            if (argument.isNotBlank()) {
                return DuckyNode.Text(argument, submit = command == "STRINGLN")
            }
            val endKeyword = if (command == "STRINGLN") "END_STRINGLN" else "END_STRING"
            val collected = StringBuilder()
            var closed = false
            while (index < lines.size) {
                val current = lines[index]
                index++
                if (commandOf(current.text) == endKeyword) {
                    closed = true
                    break
                }
                if (collected.isNotEmpty() && command == "STRINGLN") collected.append('\n')
                collected.append(current.text.trimStart('\t').removePrefix("\t"))
            }
            if (!closed) warning(line.number, "$command block without $endKeyword")
            if (collected.isEmpty()) return null
            return DuckyNode.Text(collected.toString(), submit = command == "STRINGLN")
        }

        private fun keyNodeOf(text: String, lineNumber: Int): DuckyNode? {
            val tokens = text.split(Regex("\\s+"))
                .flatMap { it.split('+', '-') }
                .filter { it.isNotBlank() }
            val modifiers = mutableListOf<String>()
            var key: String? = null
            tokens.forEach { token ->
                val mapped = mapToken(token.uppercase())
                when {
                    mapped.startsWith("MOD_") -> modifiers += mapped
                    mapped.isNotBlank() -> key = mapped
                    else -> warning(lineNumber, "Unknown key '$token'")
                }
            }
            return if (key == null && modifiers.isEmpty()) null else DuckyNode.Keys(modifiers, key)
        }

        private fun keysOf(argument: String, lineNumber: Int): List<String> =
            argument.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .mapNotNull { token ->
                    val mapped = mapToken(token.uppercase())
                    if (mapped.isBlank()) {
                        warning(lineNumber, "Unknown key '$token'")
                        null
                    } else {
                        mapped
                    }
                }

        private fun buttonOf(argument: String): Int = when (argument.trim().uppercase()) {
            "RIGHT" -> 1
            "MIDDLE", "MIDDLE_CLICK" -> 2
            else -> 0
        }

        private fun expression(raw: String, lineNumber: Int): DuckyExpr =
            runCatching { ExpressionParser(ExpressionTokenizer(raw)).parse() }.getOrElse { error ->
                warning(lineNumber, "Cannot evaluate '${raw.trim()}': ${error.message}")
                DuckyExpr.Const(0)
            }

        private fun resolveConstant(raw: String): Int? {
            val trimmed = raw.trim()
            trimmed.toIntOrNull()?.let { return it.coerceIn(0, 0xFFFF) }
            return constants[trimmed]
        }

        private fun functionName(raw: String): String =
            raw.trim().substringBefore('(').trim().replace(Regex("[^A-Za-z0-9_]"), "")

        private fun warning(line: Int, message: String) {
            diagnostics += DuckyDiagnostic(line, message, fatal = false)
        }
    }
}

/** Builds an expression tree from DuckyScript operator syntax. */
private class ExpressionTokenizer(raw: String) {
    private val tokens = mutableListOf<String>()
    private var position = 0

    init {
        var cursor = 0
        while (cursor < raw.length) {
            cursor = when {
                raw[cursor].isWhitespace() -> cursor + 1
                raw[cursor].isDigit() -> readNumber(raw, cursor)
                raw[cursor] == '$' -> readVariable(raw, cursor)
                raw[cursor].isLetter() || raw[cursor] == '_' -> readIdentifier(raw, cursor)
                else -> readOperator(raw, cursor)
            }
        }
    }

    fun next(): String? = if (position < tokens.size) tokens[position++] else null

    fun peek(): String? = tokens.getOrNull(position)

    private fun readNumber(source: String, start: Int): Int {
        var cursor = start
        while (cursor < source.length && source[cursor].isDigit()) cursor++
        tokens += source.substring(start, cursor)
        return cursor
    }

    private fun readVariable(source: String, start: Int): Int {
        var cursor = start + 1
        while (cursor < source.length && (source[cursor].isLetterOrDigit() || source[cursor] == '_')) cursor++
        tokens += source.substring(start, cursor)
        return cursor
    }

    private fun readIdentifier(source: String, start: Int): Int {
        var cursor = start
        while (cursor < source.length && (source[cursor].isLetterOrDigit() || source[cursor] == '_')) cursor++
        // Bare identifiers are treated as constants with a value of zero.
        tokens += "0"
        return cursor
    }

    private fun readOperator(source: String, start: Int): Int {
        val pair = source.substring(start, minOf(start + 2, source.length))
        if (pair in TWO_CHAR_OPERATORS) {
            tokens += pair
            return start + 2
        }
        tokens += source[start].toString()
        return start + 1
    }

    private companion object {
        val TWO_CHAR_OPERATORS = setOf("==", "!=", "<=", ">=", "<<", ">>", "&&", "||")
    }
}

private class ExpressionParser(private val tokenizer: ExpressionTokenizer) {
    private val comparisons = setOf("==", "!=", "<", "<=", ">", ">=")
    private val multiplicative = setOf("*", "/", "%", "^")

    fun parse(): DuckyExpr = parseOr()

    private fun parseOr(): DuckyExpr {
        var left = parseAnd()
        var token = tokenizer.peek()
        while (token == "||") {
            tokenizer.next()
            left = DuckyExpr.Binary("||", left, parseAnd())
            token = tokenizer.peek()
        }
        return left
    }

    private fun parseAnd(): DuckyExpr {
        var left = parseBitOr()
        var token = tokenizer.peek()
        while (token == "&&") {
            tokenizer.next()
            left = DuckyExpr.Binary("&&", left, parseBitOr())
            token = tokenizer.peek()
        }
        return left
    }

    private fun parseBitOr(): DuckyExpr {
        var left = parseBitAnd()
        var token = tokenizer.peek()
        while (token == "|") {
            tokenizer.next()
            left = DuckyExpr.Binary("|", left, parseBitAnd())
            token = tokenizer.peek()
        }
        return left
    }

    private fun parseBitAnd(): DuckyExpr {
        var left = parseComparison()
        var token = tokenizer.peek()
        while (token == "&") {
            tokenizer.next()
            left = DuckyExpr.Binary("&", left, parseComparison())
            token = tokenizer.peek()
        }
        return left
    }

    private fun parseComparison(): DuckyExpr {
        var left = parseShift()
        var token = tokenizer.peek()
        while (token != null && token in comparisons) {
            tokenizer.next()
            left = DuckyExpr.Binary(token, left, parseShift())
            token = tokenizer.peek()
        }
        return left
    }

    private fun parseShift(): DuckyExpr {
        var left = parseAdditive()
        var token = tokenizer.peek()
        while (token == "<<" || token == ">>") {
            tokenizer.next()
            left = DuckyExpr.Binary(token, left, parseAdditive())
            token = tokenizer.peek()
        }
        return left
    }

    private fun parseAdditive(): DuckyExpr {
        var left = parseMultiplicative()
        var token = tokenizer.peek()
        while (token == "+" || token == "-") {
            tokenizer.next()
            left = DuckyExpr.Binary(token, left, parseMultiplicative())
            token = tokenizer.peek()
        }
        return left
    }

    private fun parseMultiplicative(): DuckyExpr {
        var left = parseUnary()
        var token = tokenizer.peek()
        while (token != null && token in multiplicative) {
            tokenizer.next()
            left = DuckyExpr.Binary(token, left, parseUnary())
            token = tokenizer.peek()
        }
        return left
    }

    private fun parseUnary(): DuckyExpr {
        val token = tokenizer.peek()
        if (token == "!" || token == "-" || token == "~") {
            tokenizer.next()
            return DuckyExpr.Unary(token, parseUnary())
        }
        return parsePrimary()
    }

    private fun parsePrimary(): DuckyExpr {
        val token = tokenizer.next() ?: return DuckyExpr.Const(0)
        return when {
            token == "(" -> {
                val inner = parseOr()
                if (tokenizer.peek() == ")") tokenizer.next()
                inner
            }
            token.startsWith("$") -> DuckyExpr.Var(token.removePrefix("$"))
            token.toIntOrNull() != null -> DuckyExpr.Const(token.toInt())
            else -> DuckyExpr.Const(0)
        }
    }
}

/** A key press expressed as an optional shift modifier plus a HID key name. */
private data class KeyStroke(val shift: Boolean, val key: String)

private val CHAR_STROKES: Map<Char, KeyStroke> = buildMap {
    put(' ', KeyStroke(false, "SPACE"))
    put('\t', KeyStroke(false, "TAB"))
    put('\n', KeyStroke(false, "ENTER"))
    put('-', KeyStroke(false, "MINUS"))
    put('_', KeyStroke(true, "MINUS"))
    put('=', KeyStroke(false, "EQUAL"))
    put('+', KeyStroke(true, "EQUAL"))
    put('[', KeyStroke(false, "LEFTBRACE"))
    put('{', KeyStroke(true, "LEFTBRACE"))
    put(']', KeyStroke(false, "RIGHTBRACE"))
    put('}', KeyStroke(true, "RIGHTBRACE"))
    put('\\', KeyStroke(false, "BACKSLASH"))
    put('|', KeyStroke(true, "BACKSLASH"))
    put(';', KeyStroke(false, "SEMICOLON"))
    put(':', KeyStroke(true, "SEMICOLON"))
    put('\'', KeyStroke(false, "APOSTROPHE"))
    put('"', KeyStroke(true, "APOSTROPHE"))
    put('`', KeyStroke(false, "GRAVE"))
    put('~', KeyStroke(true, "GRAVE"))
    put(',', KeyStroke(false, "COMMA"))
    put('<', KeyStroke(true, "COMMA"))
    put('.', KeyStroke(false, "DOT"))
    put('>', KeyStroke(true, "DOT"))
    put('/', KeyStroke(false, "SLASH"))
    put('?', KeyStroke(true, "SLASH"))
    put('!', KeyStroke(true, "1"))
    put('@', KeyStroke(true, "2"))
    put('#', KeyStroke(true, "3"))
    put('$', KeyStroke(true, "4"))
    put('%', KeyStroke(true, "5"))
    put('^', KeyStroke(true, "6"))
    put('&', KeyStroke(true, "7"))
    put('*', KeyStroke(true, "8"))
    put('(', KeyStroke(true, "9"))
    put(')', KeyStroke(true, "0"))
}

/**
 * Executes a compiled payload against a [HidController].
 *
 * Cancellation is cooperative: cancelling the calling coroutine stops the run
 * at the next instruction and always releases held keys and modifiers.
 */
class DuckyRunner(
    private val controller: HidController,
    private val typingDelayMs: Long = 12,
    private val keyHoldMs: Long = 14,
    private val maxInstructions: Int = 200_000,
) {
    private sealed interface Flow {
        data object Normal : Flow
        data object Stop : Flow
        data object Restart : Flow
        data class Returned(val value: Int) : Flow
    }

    private val environment = DuckyEnvironment()
    private val heldKeys = LinkedHashSet<String>()
    private var functions: Map<String, List<DuckyNode>> = emptyMap()
    private var defaultDelayMs: Long = 0
    private var executed = 0
    private var budget = 0
    private var stopped = false
    private var logSink: (String) -> Unit = {}

    suspend fun run(program: DuckyProgram, onLog: (String) -> Unit = {}): DuckyRunResult {
        val started = System.currentTimeMillis()
        logSink = onLog
        functions = program.functions
        budget = maxInstructions
        executed = 0
        stopped = false
        var restarts = 0
        try {
            restarts = runUntilFinished(program.nodes)
        } finally {
            releaseEverything()
        }
        return DuckyRunResult(
            executed = executed,
            elapsedMs = System.currentTimeMillis() - started,
            stopped = stopped,
            restarts = restarts,
            variables = environment.snapshot(),
        )
    }

    private suspend fun runUntilFinished(nodes: List<DuckyNode>): Int {
        var restarts = 0
        var done = false
        while (!done) {
            when (val flow = executeBlock(nodes)) {
                is Flow.Restart -> {
                    restarts++
                    if (restarts > MAX_RESTARTS) {
                        onLogSink("RESTART_PAYLOAD limit reached")
                        done = true
                    } else {
                        releaseEverything()
                        onLogSink("Restarting payload ($restarts)")
                    }
                }
                Flow.Stop -> {
                    stopped = true
                    done = true
                }
                else -> done = true
            }
        }
        return restarts
    }

    private suspend fun executeBlock(nodes: List<DuckyNode>): Flow {
        for (node in nodes) {
            currentCoroutineContext().ensureActive()
            if (budget-- <= 0) {
                onLogSink("Instruction budget exhausted")
                stopped = true
                return Flow.Stop
            }
            executed++
            val flow = executeNode(node)
            if (flow != null) return flow
        }
        return Flow.Normal
    }

    private suspend fun executeNode(node: DuckyNode): Flow? = when (node) {
        is DuckyNode.Repeat, is DuckyNode.If, is DuckyNode.While, is DuckyNode.Call,
        is DuckyNode.Return, DuckyNode.Stop, DuckyNode.Restart -> executeControl(node)
        is DuckyNode.MouseMove, is DuckyNode.MouseClick, is DuckyNode.MouseScroll -> {
            executePointer(node)
            null
        }
        else -> {
            executeInput(node)
            null
        }
    }

    private suspend fun executeControl(node: DuckyNode): Flow = when (node) {
        is DuckyNode.Repeat -> repeatFlow(node)
        is DuckyNode.If -> ifFlow(node)
        is DuckyNode.While -> whileFlow(node)
        is DuckyNode.Call -> callFlow(node)
        is DuckyNode.Return -> Flow.Returned(node.value?.eval(environment) ?: 0)
        DuckyNode.Stop -> Flow.Stop
        else -> Flow.Restart
    }

    private fun executePointer(node: DuckyNode) {
        when (node) {
            is DuckyNode.MouseMove -> controller.mouseMove(
                node.deltaX.eval(environment),
                node.deltaY.eval(environment),
            )
            is DuckyNode.MouseClick -> controller.mouseClick(node.button)
            is DuckyNode.MouseScroll -> controller.mouseWheel(node.delta.eval(environment))
            else -> Unit
        }
    }

    private suspend fun executeInput(node: DuckyNode) {
        when (node) {
            is DuckyNode.Delay -> pause(node.millis.eval(environment))
            is DuckyNode.SetDefaultDelay ->
                defaultDelayMs = node.millis.eval(environment).coerceIn(0, 60_000).toLong()
            is DuckyNode.Text -> typeTextNode(node)
            is DuckyNode.Keys -> pressKeys(node)
            is DuckyNode.Hold -> holdKeys(node.keys)
            is DuckyNode.Release -> releaseKeys(node.keys)
            is DuckyNode.Media -> tapMedia(node.key)
            is DuckyNode.RandomKeystrokes -> randomKeystroke(node.charset)
            is DuckyNode.Assign -> environment[node.name] = node.value.eval(environment)
            DuckyNode.Reset -> releaseEverything()
            else -> Unit
        }
    }

    private suspend fun repeatFlow(node: DuckyNode.Repeat): Flow {
        val times = node.times.eval(environment).coerceIn(0, 10_000)
        for (iteration in 0 until times) {
            val flow = executeBlock(node.body)
            if (flow !is Flow.Normal) return flow
        }
        return Flow.Normal
    }

    private suspend fun ifFlow(node: DuckyNode.If): Flow {
        val branch = if (node.condition.eval(environment) != 0) node.then else node.otherwise
        return executeBlock(branch)
    }

    private suspend fun whileFlow(node: DuckyNode.While): Flow {
        var iterations = 0
        while (node.condition.eval(environment) != 0) {
            if (iterations++ > MAX_LOOP_ITERATIONS) {
                onLogSink("WHILE loop limit reached")
                break
            }
            val flow = executeBlock(node.body)
            if (flow !is Flow.Normal) return flow
        }
        return Flow.Normal
    }

    private suspend fun callFlow(node: DuckyNode.Call): Flow {
        val body = functions[node.name]
        if (body == null) {
            onLogSink("Unknown function ${node.name}()")
            return Flow.Normal
        }
        val flow = executeBlock(body)
        return if (flow is Flow.Returned) Flow.Normal else flow
    }

    private suspend fun typeTextNode(node: DuckyNode.Text) {
        type(node.text)
        if (node.submit) {
            tap("ENTER", emptyList())
            applyDefaultDelay()
        }
    }

    private suspend fun holdKeys(keys: List<String>) {
        keys.forEach { key ->
            if (key.startsWith("MOD_")) controller.modifierDown(key) else controller.keyDown(key)
            heldKeys += key
        }
    }

    private fun releaseKeys(keys: List<String>) {
        keys.forEach { key ->
            runCatching {
                if (key.startsWith("MOD_")) controller.modifierUp(key) else controller.keyUp(key)
            }
            heldKeys -= key
        }
    }

    private suspend fun tapMedia(key: String) {
        controller.mediaDown(key)
        delay(keyHoldMs)
        controller.mediaUp(key)
        applyDefaultDelay()
    }

    private suspend fun randomKeystroke(charset: String) {
        if (charset.isEmpty()) return
        typeChar(charset[Random.nextInt(charset.length)].toString())
        applyDefaultDelay()
    }

    private suspend fun pause(millis: Int) {
        delay(millis.coerceIn(1, 3_600_000).toLong())
    }

    private suspend fun type(raw: String) {
        for (char in interpolate(raw)) {
            if (char == '\n') {
                tap("ENTER", emptyList())
            } else {
                typeChar(char.toString())
            }
            applyDefaultDelay()
        }
    }

    private suspend fun typeChar(char: String) {
        val stroke = strokeFor(char) ?: return
        tap(stroke.key, if (stroke.shift) SHIFT_MODIFIERS else emptyList())
    }

    private suspend fun pressKeys(node: DuckyNode.Keys) {
        val key = node.key
        if (key == null) {
            node.modifiers.forEach { controller.modifierDown(it) }
            delay(keyHoldMs)
            node.modifiers.reversed().forEach { controller.modifierUp(it) }
            applyDefaultDelay()
            return
        }
        tap(key, node.modifiers)
        applyDefaultDelay()
    }

    private suspend fun tap(key: String, modifiers: List<String>) {
        val usable = modifiers.filter { it in keyCodes }
        usable.forEach { controller.modifierDown(it) }
        controller.keyDown(key)
        delay(keyHoldMs)
        controller.keyUp(key)
        usable.reversed().forEach { controller.modifierUp(it) }
        delay(typingDelayMs)
    }

    private suspend fun applyDefaultDelay() {
        if (defaultDelayMs > 0) delay(defaultDelayMs)
    }

    private fun releaseEverything() {
        heldKeys.forEach { key ->
            runCatching {
                if (key.startsWith("MOD_")) controller.modifierUp(key) else controller.keyUp(key)
            }
        }
        heldKeys.clear()
        runCatching { controller.releaseAllKeyboard() }
    }

    private fun interpolate(raw: String): String {
        if (!raw.contains('$')) return raw
        val out = StringBuilder()
        var cursor = 0
        while (cursor < raw.length) {
            val char = raw[cursor]
            val end = if (char == '$') variableEnd(raw, cursor) else cursor
            val name = if (char == '$') raw.substring(cursor + 1, end) else ""
            val value = if (name.isEmpty()) null else environment[name]
            if (value == null) {
                out.append(char)
                cursor++
            } else {
                out.append(value)
                cursor = end
            }
        }
        return out.toString()
    }

    private fun variableEnd(raw: String, dollar: Int): Int {
        var end = dollar + 1
        while (end < raw.length && (raw[end].isLetterOrDigit() || raw[end] == '_')) end++
        return end
    }

    private fun strokeFor(char: String): KeyStroke? {
        if (char.length != 1) return null
        val symbol = char[0]
        CHAR_STROKES[symbol]?.let { return it }
        return when {
            symbol in 'a'..'z' -> KeyStroke(false, symbol.uppercaseChar().toString())
            symbol in 'A'..'Z' -> KeyStroke(true, symbol.toString())
            symbol in '0'..'9' -> KeyStroke(false, symbol.toString())
            else -> null
        }
    }

    private fun onLogSink(message: String) {
        runCatching { logSink(message) }
    }

    private companion object {
        const val MAX_RESTARTS = 3
        const val MAX_LOOP_ITERATIONS = 10_000
        val SHIFT_MODIFIERS = listOf("MOD_LSHIFT")
    }
}
