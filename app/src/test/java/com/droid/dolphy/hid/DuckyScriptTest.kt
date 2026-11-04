package com.droid.dolphy.hid

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckyScriptTest {

    private class Recorder : HidController {
        val events = mutableListOf<String>()

        /** Key presses only, in order, without modifiers and without the cleanup reset. */
        val keys: List<String>
            get() = events.filter { it.startsWith("down:") }.map { it.removePrefix("down:") }

        override fun keyDown(key: String) {
            events += "down:$key"
        }

        override fun keyUp(key: String) {
            events += "up:$key"
        }

        override fun modifierDown(key: String) {
            events += "modDown:$key"
        }

        override fun modifierUp(key: String) {
            events += "modUp:$key"
        }

        override fun releaseAllKeyboard() {
            events += "releaseAll"
        }

        override fun mouseMove(deltaX: Int, deltaY: Int) {
            events += "mouse:$deltaX,$deltaY"
        }

        override fun mouseDown(button: Int) {
            events += "mouseDown:$button"
        }

        override fun mouseUp(button: Int) {
            events += "mouseUp:$button"
        }

        override fun mouseClick(button: Int) {
            events += "click:$button"
        }

        override fun releaseAllMouseButtons() {
            events += "releaseMouse"
        }

        override fun mouseWheel(delta: Int, horizontal: Boolean) {
            events += "wheel:$delta"
        }

        override fun mediaDown(key: String) {
            events += "mediaDown:$key"
        }

        override fun mediaUp(key: String) {
            events += "mediaUp:$key"
        }
    }

    private fun run(source: String): Pair<Recorder, DuckyRunResult> {
        val recorder = Recorder()
        val program = DuckyScript.parse(source)
        assertTrue("unexpected errors: ${program.errors}", program.isValid)
        val result = runBlocking { DuckyRunner(recorder).run(program) }
        return recorder to result
    }

    @Test
    fun `string types upper and lower case with shift`() {
        val (recorder, _) = run("STRING aB")
        assertEquals(listOf("down:A", "up:A", "modDown:MOD_LSHIFT", "down:B", "up:B", "modUp:MOD_LSHIFT"), recorder.events.filterNot { it == "releaseAll" })
    }

    @Test
    fun `symbols map to shifted us layout keys`() {
        val (recorder, _) = run("STRING a-b_c!")
        assertEquals(listOf("A", "MINUS", "B", "MINUS", "C", "1"), recorder.keys)
        assertTrue(recorder.events.contains("modDown:MOD_LSHIFT"))
    }

    @Test
    fun `stringln appends enter`() {
        val (recorder, _) = run("STRINGLN hi")
        assertEquals(listOf("H", "I", "ENTER"), recorder.keys)
    }

    @Test
    fun `string block joins lines without newlines`() {
        val (recorder, _) = run("STRING\nab\ncd\nEND_STRING")
        assertEquals(listOf("A", "B", "C", "D"), recorder.keys)
    }

    @Test
    fun `string block interpolates variables`() {
        val (recorder, _) = run("VAR ${'$'}n = 42\nSTRING value=${'$'}n")
        assertEquals(listOf("V", "A", "L", "U", "E", "EQUAL", "4", "2"), recorder.keys)
    }

    @Test
    fun `repeat executes the previous command n times in total`() {
        val (recorder, _) = run("ENTER\nREPEAT 2")
        assertEquals(listOf("ENTER", "ENTER"), recorder.keys)
    }

    @Test
    fun `while loop iterates until the condition fails`() {
        val (recorder, _) = run(
            """
            VAR ${'$'}n = 0
            WHILE ${'$'}n < 3
            ENTER
            VAR ${'$'}n = ${'$'}n + 1
            END_WHILE
            """.trimIndent(),
        )
        assertEquals(listOf("ENTER", "ENTER", "ENTER"), recorder.keys)
    }

    @Test
    fun `if else selects one branch`() {
        val (recorder, _) = run(
            """
            VAR ${'$'}n = 5
            IF ${'$'}n > 3
            STRING yes
            ELSE
            STRING no
            END_IF
            """.trimIndent(),
        )
        assertEquals(listOf("Y", "E", "S"), recorder.keys)
    }

    @Test
    fun `function call runs its body`() {
        val (recorder, _) = run(
            """
            FUNCTION greet()
            STRING ok
            END_FUNCTION
            greet()
            """.trimIndent(),
        )
        assertEquals(listOf("O", "K"), recorder.keys)
    }

    @Test
    fun `hold and release keep modifiers down`() {
        val (recorder, _) = run("HOLD SHIFT\nENTER\nRELEASE SHIFT")
        assertEquals(
            listOf("modDown:MOD_LSHIFT", "down:ENTER", "up:ENTER", "modUp:MOD_LSHIFT"),
            recorder.events.filterNot { it == "releaseAll" },
        )
    }

    @Test
    fun `media commands use consumer control`() {
        val (recorder, _) = run("MEDIA_VOLUMEUP")
        assertEquals(listOf("mediaDown:VOLUMEUP", "mediaUp:VOLUMEUP"), recorder.events.filterNot { it == "releaseAll" })
    }

    @Test
    fun `stop payload halts execution`() {
        val (recorder, result) = run("STOP_PAYLOAD\nSTRING after")
        assertTrue(result.stopped)
        assertTrue(recorder.keys.isEmpty())
    }

    @Test
    fun `device only commands warn but do not fail the payload`() {
        val program = DuckyScript.parse("ATTACKMODE HID\nLED_R\nSTRING x")
        assertTrue(program.isValid)
        assertEquals(2, program.warnings.size)
        assertEquals(2, program.nodes.count { it is DuckyNode.Noop })
        assertEquals(1, program.nodes.count { it !is DuckyNode.Noop })
    }

    @Test
    fun `mouse move and click are forwarded`() {
        val (recorder, _) = run("MOUSE_MOVE 3 4\nMOUSE_CLICK LEFT")
        assertTrue(recorder.events.contains("mouse:3,4"))
        assertTrue(recorder.events.contains("click:0"))
    }

    @Test
    fun `default delay is applied after keystrokes without changing output`() {
        val (recorder, _) = run("DEFAULT_DELAY 22\nSTRING ab")
        assertEquals(listOf("A", "B"), recorder.keys)
    }

    @Test
    fun `unknown keys are reported as warnings`() {
        val program = DuckyScript.parse("NOTAKEY")
        assertFalse(program.warnings.isEmpty())
    }
}
