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
            val r = right.eval(env)
            return when (op) {
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
                "==" -> bool(l == r)
                "!=" -> bool(l != r)
                "<" -> bool(l < r)
                "<=" -> bool(l <= r)
                ">" -> bool(l > r)
                ">=" -> bool(l >= r)
                else -> 0
            }
        }

        private fun bool(value: Boolean): Int = if (value) 1 else 0
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

    fun parse(source: String): DuckyProgram = Parser(source).parse()

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
                when (command) {
                    "" -> index++
                    "REM_BLOCK" -> {
                        index++
                        skipUntil("END_REM", line.number)
                    }
                    "IF" -> {
                        index++
                        nodes += parseIf(line)
                    }
                    "WHILE" -> {
                        index++
                        val condition = expression(argumentOf(line.text), line.number)
                        val body = parseBlock(setOf("END_WHILE"))
                        if (body.terminator == null) {
                            warning(line.number, "WHILE without END_WHILE")
                        }
                        nodes += DuckyNode.While(condition, body.nodes)
                    }
                    "FUNCTION" -> {
                        index++
                        val name = functionName(argumentOf(line.text)).ifBlank {
                            warning(line.number, "FUNCTION without a name")
                            ""
                        }
                        val body = parseBlock(setOf("END_FUNCTION"))
                        if (body.terminator == null) {
                            warning(line.number, "FUNCTION without END_FUNCTION")
                        }
                        if (name.isNotBlank()) {
                            functions[name] = body.nodes
                        }
                    }
                    "ELSE", "END_IF", "END_WHILE", "END_FUNCTION", "END_REM" -> {
                        index++
                        warning(line.number, "Unexpected $command")
                    }
                    "REPEAT" -> {
                        index++
                        val times = expression(argumentOf(line.text).ifBlank { "1" }, line.number)
                        val target = nodes.removeLastOrNull()
                        if (target == null) {
                            warning(line.number, "REPEAT without a previous command")
                        } else {
                            nodes += DuckyNode.Repeat(times, listOf(target))
                        }
                    }
                    else -> {
                        index++
                        parseSimple(line)?.let { nodes += it }
                    }
                }
            }
            return Block(nodes, null)
        }

        private fun parseIf(header: Line): DuckyNode {
            val conditionText = argumentOf(header.text).removeSuffix("THEN").removeSuffix("then").trim()
            val condition = expression(conditionText, header.number)
            val thenBlock = parseBlock(setOf("ELSE", "END_IF"))
            var otherwise: List<DuckyNode> = emptyList()
            if (thenBlock.terminator == "ELSE") {
                val next = lines.getOrNull(index)
                val nextCommand = next?.let { commandOf(it.text) }.orEmpty()
                if (nextCommand == "IF" && next != null) {
                    index++
                    otherwise = listOf(parseIf(next))
                } else {
                    otherwise = parseBlock(setOf("END_IF")).nodes
                }
            } else if (thenBlock.terminator == null) {
                warning(header.number, "IF without END_IF")
            }
            return DuckyNode.If(condition, thenBlock.nodes, otherwise)
        }

        private fun skipUntil(keyword: String, startLine: Int) {
            while (index < lines.size) {
                if (commandOf(lines[index].text) == keyword) {
                    index++
                    return
                }
                index++
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

                "DEFAULT_DELAY", "DEFAULTDELAY" -> DuckyNode.SetDefaultDelay(
                    expression(argument, line.number),
                )

                "DELAY" -> DuckyNode.Delay(expression(argument.ifBlank { "0" }, line.number))

                "STRING", "STRINGLN" -> parseStringBlock(command, argument, line)

                "HOLD" -> DuckyNode.Hold(keysOf(argument, line.number))
                "RELEASE" -> DuckyNode.Release(keysOf(argument, line.number))

                "STOP_PAYLOAD" -> DuckyNode.Stop
                "RESTART_PAYLOAD" -> DuckyNode.Restart
                "RESET" -> DuckyNode.Reset

                "DEFINE" -> {
                    val name = argument.substringBefore(' ').trim()
                    val value = argument.substringAfter(' ', "").trim()
                    val resolved = resolveConstant(value)
                    if (name.isBlank()) {
                        warning(line.number, "DEFINE without a name")
                    } else if (resolved == null) {
                        warning(line.number, "DEFINE $name has an unsupported value")
                    } else {
                        constants[name] = resolved
                    }
                    null
                }

                "VAR" -> {
                    val name = argument.substringBefore(' ').trim().removePrefix("$")
                    val assignment = argument.substringAfter(' ', "").trim()
                    val value = assignment.substringAfter('=', "").trim()
                    if (name.isBlank() || !argument.contains('=')) {
                        warning(line.number, "VAR needs a name and an assignment")
                        null
                    } else {
                        DuckyNode.Assign(name, expression(value, line.number))
                    }
                }

                "INJECT_MOD" -> {
                    val key = mapToken(argument.uppercase())
                    if (key.isBlank()) {
                        warning(line.number, "INJECT_MOD ${argument.uppercase()} is not a key")
                        null
                    } else {
                        DuckyNode.Keys(emptyList(), key)
                    }
                }

                else -> when {
                    command.startsWith("MEDIA_") -> {
                        val key = command.removePrefix("MEDIA_")
                        if (key in consumerControlCodes) DuckyNode.Media(key) else {
                            warning(line.number, "$command is not a media key")
                            null
                        }
                    }

                    command in MEDIA_ALIASES -> DuckyNode.Media(MEDIA_ALIASES.getValue(command))

                    command == "MOUSE_MOVE" -> {
                        val parts = argument.split(Regex("\\s+")).filter { it.isNotBlank() }
                        if (parts.size != 2) {
                            warning(line.number, "MOUSE_MOVE needs X and Y")
                            null
                        } else {
                            DuckyNode.MouseMove(expression(parts[0], line.number), expression(parts[1], line.number))
                        }
                    }

                    command == "MOUSE_CLICK" -> DuckyNode.MouseClick(buttonOf(argument))
                    command == "MOUSE_SCROLL" || command == "MOUSE_WHEEL" ->
                        DuckyNode.MouseScroll(expression(argument.ifBlank { "0" }, line.number))

                    command in RANDOM_SETS -> DuckyNode.RandomKeystrokes(RANDOM_SETS.getValue(command))

                    CALL_PATTERN.matches(text) -> DuckyNode.Call(functionName(text))

                    command in DEVICE_ONLY -> {
                        warning(line.number, "$command is device specific and is ignored")
                        DuckyNode.Noop(command)
                    }

                    else -> keyNodeOf(text, null, line.number)
                }
            }
        }

        private fun parseStringBlock(command: String, argument: String, line: Line): DuckyNode? {
            if (argument.isNotBlank()) {
                return DuckyNode.Text(argument, submit = command == "STRINGLN")
            }
            val endKeyword = if (command == "STRINGLN") "END_STRINGLN" else "END_STRING"
            val collected = StringBuilder()
            var opened = false
            while (index < lines.size) {
                val current = lines[index]
                if (commandOf(current.text) == endKeyword) {
                    index++
                    opened = true
                    break
                }
                if (collected.isNotEmpty()) {
                    if (command == "STRINGLN") collected.append('\n')
                }
                collected.append(current.text.trimStart('\t').removePrefix("\t"))
                index++
            }
            if (!opened) {
                warning(line.number, "$command block without $endKeyword")
            }
            return if (collected.isEmpty()) null else DuckyNode.Text(collected.toString(), submit = command == "STRINGLN")
        }

        private fun keyNodeOf(text: String, forced: String?, lineNumber: Int): DuckyNode? {
            val tokens = text.split(Regex("\\s+"))
                .flatMap { it.split('+', '-') }
                .filter { it.isNotBlank() }
            val modifiers = mutableListOf<String>()
            var key: String? = forced
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

        private fun expression(raw: String, lineNumber: Int): DuckyExpr {
            val tokens = ExpressionTokenizer(raw, lineNumber)
            val parsed = try {
                ExpressionParser(tokens).parseAll()
            } catch (t: Throwable) {
                warning(lineNumber, "Cannot evaluate '${raw.trim()}': ${t.message}")
                DuckyExpr.Const(0)
            }
            return parsed
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

    private fun commandOf(text: String): String =
        text.trim().substringBefore(' ').substringBefore('\t').uppercase()

    private fun argumentOf(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""
        val space = trimmed.indexOfFirst { it == ' ' || it == '\t' }
        return if (space < 0) "" else trimmed.substring(space + 1)
    }

    private val MEDIA_ALIASES: Map<String, String> = mapOf(
        "VOLUMEUP" to "VOLUMEUP",
        "VOLUMEDOWN" to "VOLUMEDOWN",
        "MUTE" to "VOLUMEMUTE",
        "PLAYPAUSE" to "PLAYPAUSE",
        "MEDIA_PLAY_PAUSE" to "PLAYPAUSE",
    )

    private val CALL_PATTERN = Regex("^[A-Za-z_][A-Za-z0-9_]*\\s*\\(\\s*\\)$")

    private val RANDOM_SETS: Map<String, String> = mapOf(
        "RANDOM_LOWERCASE_LETTER" to "abcdefghijklmnopqrstuvwxyz",
        "RANDOM_UPPERCASE_LETTER" to "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
        "RANDOM_LETTER" to "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
        "RANDOM_NUMBER" to "0123456789",
        "RANDOM_SPECIAL" to "!@#$%^&*()",
        "RANDOM_CHAR" to "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()",
    )

    internal fun mapToken(token: String): String = when (token) {
        "CTRL", "CONTROL" -> "MOD_LCTRL"
        "SHIFT" -> "MOD_LSHIFT"
        "ALT" -> "MOD_LALT"
        "ALTGR" -> "MOD_RALT"
        "GUI", "WINDOWS", "WIN", "COMMAND" -> "MOD_LMETA"
        "MENU", "APP", "APPLICATION" -> "APPLICATION"
        "ENTER", "RETURN" -> "ENTER"
        "ESC", "ESCAPE" -> "ESC"
        "UPARROW", "UP" -> "UP"
        "DOWNARROW", "DOWN" -> "DOWN"
        "LEFTARROW", "LEFT" -> "LEFT"
        "RIGHTARROW", "RIGHT" -> "RIGHT"
        "DEL", "DELETE" -> "DELETE"
        "PAGEUP", "PAGE_UP" -> "PAGEUP"
        "PAGEDOWN", "PAGE_DOWN" -> "PAGEDOWN"
        "CAPS_LOCK", "CAPSLOCK" -> "CAPSLOCK"
        "NUM_LOCK", "NUMLOCK" -> "NUMLOCK"
        "SCROLL_LOCK", "SCROLLLOCK" -> "SCROLLLOCK"
        "PRINTSCREEN", "PRINT_SCREEN" -> "SYSRQ"
        "BREAK", "PAUSE" -> "PAUSE"
        "SPACE" -> "SPACE"
        else -> when {
            token.length == 1 && (token[0].isLetterOrDigit()) -> token
            token in keyCodes -> token
            else -> ""
        }
    }

    internal fun modifierNames(): Set<String> = keyCodes.keys.filter { it.startsWith("MOD_") }.toSet()
}

/** Builds an expression tree from DuckyScript operator syntax. */
private class ExpressionTokenizer(private val raw: String, private val line: Int) {
    private val tokens = mutableListOf<String>()

    private var position = 0

    init {
        tokenize(raw)
    }

    fun next(): String? = if (position < tokens.size) tokens[position++] else null

    fun peek(): String? = tokens.getOrNull(position)

    private fun tokenize(source: String) {
        var i = 0
        while (i < source.length) {
            val ch = source[i]
            when {
                ch.isWhitespace() -> i++
                ch.isDigit() -> {
                    val start = i
                    while (i < source.length && source[i].isDigit()) i++
                    tokens += source.substring(start, i)
                }
                ch == '$' -> {
                    val start = i
                    i++
                    while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                    tokens += source.substring(start, i)
                }
                ch == '(' || ch == ')' || ch == '!' || ch == '-' || ch == '~' -> {
                    tokens += ch.toString()
                    i++
                }
                ch == '+' || ch == '*' || ch == '/' || ch == '%' || ch == '^' -> {
                    tokens += ch.toString()
                    i++
                }
                ch == '<' || ch == '>' || ch == '=' || ch == '&' || ch == '|' -> {
                    val two = source.substring(i, minOf(i + 2, source.length))
                    when (two) {
                        "==", "!=", "<=", ">=", "<<", ">>", "&&", "||" -> {
                            tokens += two
                            i += 2
                        }
                        else -> {
                            tokens += ch.toString()
                            i++
                        }
                    }
                }
                else -> {
                    // Bare identifiers are treated as constants with a value of zero.
                    val start = i
                    while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                    if (i == start) i++ else tokens += "0"
                }
            }
        }
    }
}

private class ExpressionParser(private val tokenizer: ExpressionTokenizer) {

    fun parseAll(): DuckyExpr {
        val expr = parseOr()
        return expr
    }

    private fun parseOr(): DuckyExpr {
        var left = parseAnd()
        while (tokenizer.peek() == "||") {
            tokenizer.next()
            left = DuckyExpr.Binary("||", left, parseAnd())
        }
        return left
    }

    private fun parseAnd(): DuckyExpr {
        var left = parseBitOr()
        while (tokenizer.peek() == "&&") {
            tokenizer.next()
            left = DuckyExpr.Binary("&&", left, parseBitOr())
        }
        return left
    }

    private fun parseBitOr(): DuckyExpr {
        var left = parseBitAnd()
        while (tokenizer.peek() == "|") {
            tokenizer.next()
            left = DuckyExpr.Binary("|", left, parseBitAnd())
        }
        return left
    }

    private fun parseBitAnd(): DuckyExpr {
        var left = parseComparison()
        while (tokenizer.peek() == "&") {
            tokenizer.next()
            left = DuckyExpr.Binary("&", left, parseComparison())
        }
        return left
    }

    private fun parseComparison(): DuckyExpr {
        var left = parseShift()
        while (true) {
            val op = tokenizer.peek() ?: break
            if (op !in COMPARISONS) break
            tokenizer.next()
            left = DuckyExpr.Binary(op, left, parseShift())
        }
        return left
    }

    private fun parseShift(): DuckyExpr {
        var left = parseAdditive()
        while (true) {
            val op = tokenizer.peek() ?: break
            if (op != "<<" && op != ">>") break
            tokenizer.next()
            left = DuckyExpr.Binary(op, left, parseAdditive())
        }
        return left
    }

    private fun parseAdditive(): DuckyExpr {
        var left = parseMultiplicative()
        while (true) {
            val op = tokenizer.peek() ?: break
            if (op != "+" && op != "-") break
            tokenizer.next()
            left = DuckyExpr.Binary(op, left, parseMultiplicative())
        }
        return left
    }

    private fun parseMultiplicative(): DuckyExpr {
        var left = parseUnary()
        while (true) {
            val op = tokenizer.peek() ?: break
            if (op !in setOf("*", "/", "%", "^")) break
            tokenizer.next()
            left = DuckyExpr.Binary(op, left, parseUnary())
        }
        return left
    }

    private fun parseUnary(): DuckyExpr {
        val op = tokenizer.peek() ?: return DuckyExpr.Const(0)
        if (op == "!" || op == "-" || op == "~") {
            tokenizer.next()
            return DuckyExpr.Unary(op, parseUnary())
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

    companion object {
        private val COMPARISONS = setOf("==", "!=", "<", "<=", ">", ">=")
    }
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
    private var functions: Map<String, List<DuckyNode>> = emptyMap()
    private var defaultDelayMs: Long = 0
    private var executed = 0
    private var budget = 0
    private var stopped = false
    private val heldKeys = LinkedHashSet<String>()

    suspend fun run(program: DuckyProgram, onLog: (String) -> Unit = {}): DuckyRunResult {
        val started = System.currentTimeMillis()
        logSink = onLog
        functions = program.functions
        budget = maxInstructions
        executed = 0
        stopped = false
        var restarts = 0
        try {
            while (true) {
                val flow = try {
                    executeBlock(program.nodes)
                } catch (t: SecurityException) {
                    onLog("HID write rejected: ${t.message}")
                    Flow.Stop
                }
                when (flow) {
                    is Flow.Restart -> {
                        restarts++
                        if (restarts > MAX_RESTARTS) {
                            onLog("RESTART_PAYLOAD limit reached")
                            break
                        }
                        releaseEverything()
                        onLog("Restarting payload ($restarts)")
                    }
                    else -> break
                }
            }
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

    private suspend fun executeBlock(nodes: List<DuckyNode>): Flow {
        val env = environment
        for (node in nodes) {
            currentCoroutineContext().ensureActive()
            if (budget-- <= 0) {
                onLogSink("Instruction budget exhausted")
                stopped = true
                return Flow.Stop
            }
            executed++
            when (node) {
                is DuckyNode.Delay -> delay(node.millis.eval(env).coerceIn(1, 3_600_000).toLong())
                is DuckyNode.SetDefaultDelay -> defaultDelayMs = node.millis.eval(env).coerceIn(0, 60_000).toLong()
                is DuckyNode.Text -> {
                    type(node.text)
                    if (node.submit) {
                        tap("ENTER", emptyList())
                        applyDefaultDelay()
                    }
                }
                is DuckyNode.Keys -> pressKeys(node)
                is DuckyNode.Hold -> node.keys.forEach { key ->
                    if (key.startsWith("MOD_")) controller.modifierDown(key) else controller.keyDown(key)
                    heldKeys += key
                }
                is DuckyNode.Release -> node.keys.forEach { key ->
                    if (key.startsWith("MOD_")) controller.modifierUp(key) else controller.keyUp(key)
                    heldKeys -= key
                }
                is DuckyNode.Repeat -> {
                    val times = node.times.eval(env).coerceIn(0, 10_000)
                    repeat(times) {
                        val flow = executeBlock(node.body)
                        if (flow !is Flow.Normal) return flow
                    }
                }
                is DuckyNode.If -> {
                    val branch = if (node.condition.eval(env) != 0) node.then else node.otherwise
                    val flow = executeBlock(branch)
                    if (flow !is Flow.Normal) return flow
                }
                is DuckyNode.While -> {
                    var iterations = 0
                    while (node.condition.eval(env) != 0) {
                        if (iterations++ > MAX_LOOP_ITERATIONS) {
                            onLogSink("WHILE loop limit reached")
                            break
                        }
                        val flow = executeBlock(node.body)
                        if (flow !is Flow.Normal) return flow
                    }
                }
                is DuckyNode.Call -> {
                    val body = functions[node.name]
                    if (body == null) {
                        onLogSink("Unknown function ${node.name}()")
                    } else {
                        val flow = executeBlock(body)
                        if (flow is Flow.Returned) continue
                        if (flow !is Flow.Normal) return flow
                    }
                }
                is DuckyNode.Return -> return Flow.Returned(node.value?.eval(env) ?: 0)
                is DuckyNode.Assign -> environment[node.name] = node.value.eval(env)
                is DuckyNode.Media -> {
                    controller.mediaDown(node.key)
                    delay(keyHoldMs)
                    controller.mediaUp(node.key)
                    applyDefaultDelay()
                }
                is DuckyNode.MouseMove -> controller.mouseMove(node.deltaX.eval(env), node.deltaY.eval(env))
                is DuckyNode.MouseClick -> controller.mouseClick(node.button)
                is DuckyNode.MouseScroll -> controller.mouseWheel(node.delta.eval(env))
                is DuckyNode.RandomKeystrokes -> {
                    val charset = node.charset
                    if (charset.isNotEmpty()) {
                        typeChar(charset[Random.nextInt(charset.length)].toString())
                        applyDefaultDelay()
                    }
                }
                DuckyNode.Reset -> releaseEverything()
                DuckyNode.Stop -> {
                    stopped = true
                    return Flow.Stop
                }
                DuckyNode.Restart -> return Flow.Restart
                is DuckyNode.Noop -> Unit
            }
        }
        return Flow.Normal
    }

    private var logSink: (String) -> Unit = {}

    private fun onLogSink(message: String) {
        runCatching { logSink(message) }
    }

    private suspend fun type(raw: String) {
        val text = interpolate(raw)
        for (ch in text) {
            if (ch == '\n') {
                tap("ENTER", emptyList())
            } else {
                typeChar(ch.toString())
            }
            applyDefaultDelay()
        }
    }

    private suspend fun typeChar(char: String) {
        val mapping = charMapping(char) ?: return
        tap(mapping.second, mapping.first)
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
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '$') {
                var end = i + 1
                while (end < raw.length && (raw[end].isLetterOrDigit() || raw[end] == '_')) end++
                val name = raw.substring(i + 1, end)
                val value = environment[name]
                if (value != null && name.isNotEmpty()) {
                    out.append(value)
                    i = end
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun charMapping(char: String): Pair<List<String>, String>? {
        val shift = listOf("MOD_LSHIFT")
        return when (char) {
            " " -> emptyList<String>() to "SPACE"
            "\t" -> emptyList<String>() to "TAB"
            "\n" -> emptyList<String>() to "ENTER"
            "-" -> emptyList<String>() to "MINUS"
            "_" -> shift to "MINUS"
            "=" -> emptyList<String>() to "EQUAL"
            "+" -> shift to "EQUAL"
            "[" -> emptyList<String>() to "LEFTBRACE"
            "{" -> shift to "LEFTBRACE"
            "]" -> emptyList<String>() to "RIGHTBRACE"
            "}" -> shift to "RIGHTBRACE"
            "\\" -> emptyList<String>() to "BACKSLASH"
            "|" -> shift to "BACKSLASH"
            ";" -> emptyList<String>() to "SEMICOLON"
            ":" -> shift to "SEMICOLON"
            "'" -> emptyList<String>() to "APOSTROPHE"
            "\"" -> shift to "APOSTROPHE"
            "`" -> emptyList<String>() to "GRAVE"
            "~" -> shift to "GRAVE"
            "," -> emptyList<String>() to "COMMA"
            "<" -> shift to "COMMA"
            "." -> emptyList<String>() to "DOT"
            ">" -> shift to "DOT"
            "/" -> emptyList<String>() to "SLASH"
            "?" -> shift to "SLASH"
            "!" -> shift to "1"
            "@" -> shift to "2"
            "#" -> shift to "3"
            "$" -> shift to "4"
            "%" -> shift to "5"
            "^" -> shift to "6"
            "&" -> shift to "7"
            "*" -> shift to "8"
            "(" -> shift to "9"
            ")" -> shift to "0"
            else -> when {
                char.length == 1 && char[0] in 'a'..'z' -> emptyList<String>() to char.uppercase()
                char.length == 1 && char[0] in 'A'..'Z' -> shift to char.uppercase()
                char.length == 1 && char[0] in '0'..'9' -> emptyList<String>() to char
                else -> null
            }
        }
    }

    companion object {
        private const val MAX_RESTARTS = 3
        private const val MAX_LOOP_ITERATIONS = 10_000
    }
}
