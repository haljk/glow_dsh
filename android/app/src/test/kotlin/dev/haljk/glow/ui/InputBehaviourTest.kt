package dev.haljk.glow.ui

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.haljk.glow.ui.theme.GlowTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Input behaviour, verified without a device.
 *
 * The emulator was ambiguous here: a tap sometimes landed on the settings button
 * and sometimes did nothing, which is also what a stale uiautomator dump looks
 * like. These tests answer the question directly — can the field be focused, take
 * text, and does the primary action light up once there is something to send.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class InputBehaviourTest {

    @get:Rule
    val compose = createComposeRule()

    private fun launch(): GlowViewModel {
        val vm = GlowViewModel(RuntimeEnvironment.getApplication())
        compose.setContent { GlowTheme { GlowApp(vm) } }
        compose.waitForIdle()
        return vm
    }

    @Test
    fun `the prompt field takes focus and text`() {
        launch()
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        field.assertIsFocused()
        field.performTextInput("雨后的旧书店")
        compose.onNodeWithText("雨后的旧书店").assertExists()
    }

    @Test
    fun `typing enables the primary action`() {
        val vm = launch()
        // Disabled while there is nothing to send.
        compose.onNodeWithText("先写一句描述，或点「帮我写」").assertExists()

        compose.onNode(hasSetTextAction()).performTextInput("一盏暖光台灯")
        compose.waitForIdle()

        // The hint clears, which only happens when the action becomes available.
        compose.onNodeWithText("先写一句描述，或点「帮我写」").assertDoesNotExist()
        org.junit.Assert.assertEquals("一盏暖光台灯", vm.prompt)
    }

    @Test
    fun `the add-photo tile is a real target in retouch mode`() {
        launch()
        compose.onNodeWithText("照片精修").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("照片").assertExists()
        compose.onNodeWithText("① 选一张照片").assertExists()
    }
}
