package dev.haljk.glow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直发原文 must reach the service exactly as typed.
 *
 * The switch promises "send what I wrote", so an added `；`, a closing `。` or one
 * extra clause silently breaks that promise and is invisible in the interface.
 */
class RawInstructionTest {

    private val typed = "把背景换成落地窗，光线偏暖，人物不要动"

    @Test
    fun `raw mode sends the wording byte for byte`() {
        val result = InstructionComposer.compose(
            EditInstructionRequest(
                refs = listOf(ReferenceSpec(Role.SUBJECT), ReferenceSpec(Role.BACKGROUND)),
                presetId = "bg-replace",
                note = typed,
                raw = true,
            ),
        )

        assertEquals(typed, result.prompt)
        assertTrue("raw prompt gained a 分号", !result.prompt.contains("；"))
        assertTrue("raw prompt gained a 句号", !result.prompt.endsWith("。"))
        assertEquals("the preset sentence must not travel with a raw prompt", "", result.presetId)
        assertEquals(2, result.references)
        assertEquals(listOf(Role.SUBJECT, Role.BACKGROUND), result.roles)
    }

    @Test
    fun `raw mode rejects a blank instruction`() {
        val error = assertThrows(InstructionRejected::class.java) {
            InstructionComposer.compose(
                EditInstructionRequest(
                    refs = listOf(ReferenceSpec(Role.SUBJECT)),
                    note = "   ",
                    raw = true,
                ),
            )
        }
        assertEquals("直发模式需要自己写下要改什么", error.message)
    }

    @Test
    fun `raw mode still needs a reference image`() {
        assertThrows(InstructionRejected::class.java) {
            InstructionComposer.compose(EditInstructionRequest(emptyList(), note = typed, raw = true))
        }
    }

    @Test
    fun `raw mode refuses more references than the service takes`() {
        val tooMany = List(InstructionComposer.MAX_REFERENCES + 1) { ReferenceSpec(Role.SUBJECT) }
        assertThrows(InstructionRejected::class.java) {
            InstructionComposer.compose(EditInstructionRequest(tooMany, note = typed, raw = true))
        }
    }

    @Test
    fun `raw mode does not look up the preset`() {
        // The panel keeps the last chosen preset after the switch is flipped; a raw send
        // must not fail because that leftover id is unknown.
        val result = InstructionComposer.compose(
            EditInstructionRequest(
                refs = listOf(ReferenceSpec(Role.SUBJECT)),
                presetId = "no-such-preset",
                note = typed,
                raw = true,
            ),
        )
        assertEquals(typed, result.prompt)
    }

    @Test
    fun `preset mode keeps composing the full instruction`() {
        val prompt = InstructionComposer.compose(
            EditInstructionRequest(
                refs = listOf(ReferenceSpec(Role.SUBJECT)),
                presetId = "hair-change",
                note = typed,
            ),
        ).prompt

        assertTrue("the preset sentence is missing", prompt.contains(Presets.HAIR_CHANGE.instruction))
        assertTrue("the parts are no longer joined with 分号", prompt.contains("；"))
        assertTrue("the instruction must not end with 分号", !prompt.endsWith("；"))
        assertTrue("the closing 句号 is missing", prompt.endsWith("。"))
        assertTrue("the protection clause is missing", prompt.contains("其他一切保持不变"))
    }
}
