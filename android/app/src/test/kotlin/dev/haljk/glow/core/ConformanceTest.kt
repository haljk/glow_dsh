package dev.haljk.glow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the Android port reproduces the shipped Node implementation exactly.
 *
 * The vectors were recorded from the running plugin, not written by hand, so a
 * green test means "same behaviour", not "same as what I remember writing".
 */
class ConformanceTest {

    @Test
    fun `instruction composition matches the recorded implementation`() {
        assertTrue("no compose vectors loaded", ConformanceVectors.compose.isNotEmpty())
        for (case in ConformanceVectors.compose) {
            val result = InstructionComposer.compose(
                EditInstructionRequest(
                    refs = case.roles.map { ReferenceSpec(Role.fromId(it)) },
                    presetId = case.presetId,
                    note = case.note,
                ),
            )
            assertEquals("prompt for '${case.name}'", case.expectedPrompt, result.prompt)
            assertEquals("presetId for '${case.name}'", case.expectedPresetId, result.presetId)
            assertEquals("reference count for '${case.name}'", case.expectedReferences, result.references)
            assertEquals(
                "role order for '${case.name}'",
                case.expectedRoles,
                result.roles.map { it.id },
            )
        }
    }

    @Test
    fun `size fitting matches the recorded implementation`() {
        assertTrue("no fitting vectors loaded", ConformanceVectors.fitting.isNotEmpty())
        for (case in ConformanceVectors.fitting) {
            val size = SizeFit.fit(case.sourceWidth, case.sourceHeight, case.budget)
                ?: throw AssertionError("fit returned null for '${case.name}'")
            assertEquals("size text for '${case.name}'", case.expectedText, size.text)
            assertEquals("ratio for '${case.name}'", case.expectedRatio, size.ratio)
            assertEquals("pixels for '${case.name}'", case.expectedPixels, size.pixels)
        }
    }

    @Test
    fun `fitted sizes are always legal for the service`() {
        // Every edge must be a multiple of 32 inside 256..2048, whatever the source.
        val sources = listOf(
            1024 to 1024, 1920 to 1080, 6000 to 4000, 4000 to 3000,
            3024 to 4032, 1080 to 1920, 4000 to 800, 800 to 4000,
            100 to 9000, 9000 to 100, 1 to 1,
        )
        for ((width, height) in sources) {
            for (density in Density.entries) {
                val size = SizeFit.fit(width, height, density.budget)!!
                for (edge in listOf(size.width, size.height)) {
                    assertEquals("edge multiple of 32 for ${width}x$height/${density.id}", 0, edge % 32)
                    assertTrue("edge within range for ${width}x$height/${density.id}", edge in 256..2048)
                }
            }
        }
    }

    @Test
    fun `invalid requests are rejected with a reason`() {
        assertTrue("no reject vectors loaded", ConformanceVectors.rejects.isNotEmpty())
        for (case in ConformanceVectors.rejects) {
            assertThrows(
                "expected rejection for '${case.name}'",
                InstructionRejected::class.java,
            ) {
                InstructionComposer.compose(
                    EditInstructionRequest(
                        refs = case.roles.map { ReferenceSpec(Role.fromId(it)) },
                        presetId = case.presetId,
                        note = case.note,
                    ),
                )
            }
        }
    }

    @Test
    fun `a preset never freezes what it is allowed to change`() {
        // The defect this guards against shipped once: "change the lighting" sat
        // next to "lighting stays the same". Both halves come from mayChange now.
        for (preset in Presets.ALL) {
            val prompt = InstructionComposer.compose(
                EditInstructionRequest(
                    refs = listOf(ReferenceSpec(Role.SUBJECT)),
                    presetId = preset.id,
                    note = "",
                ),
            ).prompt
            val frozen = prompt.substringAfter("其他一切保持不变：")
            for (changed in Preserve.describeChanges(preset.mayChange)) {
                assertTrue(
                    "preset '${preset.id}' claims to change '$changed' but freezes it too",
                    !frozen.contains(changed),
                )
            }
        }
    }

    @Test
    fun `the subject sentence never promises to keep what the preset changes`() {
        val expression = InstructionComposer.compose(
            EditInstructionRequest(listOf(ReferenceSpec(Role.SUBJECT)), "expression", ""),
        ).prompt
        val subjectSentence = expression.split("；")[1]
        assertTrue("subject sentence must not freeze the expression", !subjectSentence.contains("表情"))

        val hair = InstructionComposer.compose(
            EditInstructionRequest(listOf(ReferenceSpec(Role.SUBJECT)), "hair-change", ""),
        ).prompt
        assertTrue("subject sentence must not freeze the hair", !hair.split("；")[1].contains("发型"))
    }

    @Test
    fun `reference numbering follows submit order`() {
        val forward = InstructionComposer.compose(
            EditInstructionRequest(listOf(ReferenceSpec(Role.SUBJECT), ReferenceSpec(Role.BACKGROUND))),
        ).prompt
        assertTrue(forward.contains("以 <Picture 1> 为主体"))
        assertTrue(forward.contains("把背景替换为 <Picture 2> 中的场景"))

        val flipped = InstructionComposer.compose(
            EditInstructionRequest(listOf(ReferenceSpec(Role.BACKGROUND), ReferenceSpec(Role.SUBJECT))),
        ).prompt
        assertTrue(flipped.contains("把背景替换为 <Picture 1> 中的场景"))
        assertTrue(flipped.contains("以 <Picture 2> 为主体"))
    }
}
