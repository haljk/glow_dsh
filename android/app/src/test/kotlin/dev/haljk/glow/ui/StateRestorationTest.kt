package dev.haljk.glow.ui

import androidx.lifecycle.SavedStateHandle
import dev.haljk.glow.core.Density
import dev.haljk.glow.core.Role
import dev.haljk.glow.data.JobRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Regression test for the defect seen on a real device.
 *
 * Android destroys a backgrounded Activity while the foreground service keeps the
 * process alive. The job banner kept counting up, but the page had reset to the
 * default tab because the ViewModel — unlike the process-scoped job registry — was
 * rebuilt from nothing. Everything the user had typed or picked must survive that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StateRestorationTest {

    private fun app() = RuntimeEnvironment.getApplication()

    @Test
    fun `workbench state survives an Activity recreation`() {
        val handle = SavedStateHandle()

        // First instance: the user sets up a retouch.
        val first = GlowViewModel(app(), handle)
        first.scenario = Scenario.RETOUCH
        first.prompt = "把背景换成浅灰 studio"
        first.presetId = "bg-replace"
        first.density = Density.SHARP
        first.sizeChoiceIndex = 3
        first.autoSize = false
        first.seedText = "12345"
        first.batchCount = 2
        first.addRefs(listOf("content://media/external/images/media/25"))

        // The Activity is destroyed and rebuilt with the saved state; the process
        // (and therefore JobRegistry) never died.
        val second = GlowViewModel(app(), handle)

        assertEquals(Scenario.RETOUCH, second.scenario)
        assertEquals("把背景换成浅灰 studio", second.prompt)
        assertEquals("bg-replace", second.presetId)
        assertEquals(Density.SHARP, second.density)
        assertEquals(3, second.sizeChoiceIndex)
        assertEquals(false, second.autoSize)
        assertEquals("12345", second.seedText)
        assertEquals(2, second.batchCount)
        assertEquals(1, second.refs.size)
        assertEquals("content://media/external/images/media/25", second.refs[0].uri)
        assertEquals(Role.SUBJECT, second.refs[0].role)
    }

    @Test
    fun `an empty handle starts from the documented defaults`() {
        val vm = GlowViewModel(app(), SavedStateHandle())
        assertEquals(Scenario.TEXT, vm.scenario)
        assertEquals("", vm.prompt)
        assertEquals(null, vm.presetId)
        assertEquals(Density.NORMAL, vm.density)
        assertTrue(vm.autoSize)
        assertEquals(1, vm.batchCount)
        assertTrue(vm.refs.isEmpty())
    }

    @Test
    fun `reference list round-trips through the bundle format`() {
        val handle = SavedStateHandle()
        val first = GlowViewModel(app(), handle)
        first.addRefs(listOf("content://a", "content://b"))
        first.setRole("content://b", Role.BACKGROUND)

        val second = GlowViewModel(app(), handle)
        assertEquals(2, second.refs.size)
        assertEquals(Role.SUBJECT, second.refs[0].role)
        assertEquals(Role.BACKGROUND, second.refs[1].role)
    }

    @Test
    fun `a recreated view model does not forget that a job is running`() {
        // The exact device report: banner alive, page reset, and the submit button
        // wrongly available again.
        val handle = SavedStateHandle()
        val first = GlowViewModel(app(), handle)
        first.scenario = Scenario.COMBINE
        first.prompt = "把图 1 的人放进图 2 的咖啡馆"

        JobRegistry.start("job-1", "生成 · 测试", 1)
        try {
            val second = GlowViewModel(app(), handle)
            assertEquals(Scenario.COMBINE, second.scenario)
            assertEquals("把图 1 的人放进图 2 的咖啡馆", second.prompt)
            assertTrue("must not report idle while a job is running", second.submitting)
        } finally {
            JobRegistry.clear()
        }
    }
}
