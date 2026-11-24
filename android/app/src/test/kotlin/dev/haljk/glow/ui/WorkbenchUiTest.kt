package dev.haljk.glow.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import dev.haljk.glow.ui.theme.GlowTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real Compose interface on the JVM.
 *
 * The emulator needs a hypervisor driver that requires administrator rights, so
 * the screens are verified here instead: the tree is actually composed, measured
 * and laid out, and taps are dispatched through the same input pipeline the
 * device uses. This does not replace a device run, but it does prove the screens
 * build, that the copy is present, and that the interaction logic behaves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkbenchUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun model(): GlowViewModel = GlowViewModel(RuntimeEnvironment.getApplication())

    private fun launch(): GlowViewModel {
        val vm = model()
        compose.setContent { GlowTheme { GlowApp(vm) } }
        compose.waitForIdle()
        return vm
    }

    @Test
    fun `the three scenarios are offered on the first screen`() {
        launch()
        compose.onNodeWithText("一句话出图").assertIsDisplayed()
        compose.onNodeWithText("照片精修").assertIsDisplayed()
        compose.onNodeWithText("多图合成").assertIsDisplayed()
    }

    @Test
    fun `the primary action says what is still missing`() {
        launch()
        // Text mode with an empty description: the reason is shown before the tap,
        // so the button is never a silent dead end.
        compose.onNodeWithText("先写一句描述，或点「帮我写」").assertIsDisplayed()
        compose.onNodeWithText("生成图片").assertIsDisplayed()
    }

    @Test
    fun `switching to retouch asks for a photo`() {
        launch()
        compose.onNodeWithText("照片精修").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("① 选一张照片").assertIsDisplayed()
        compose.onNodeWithText("先选一张照片").assertIsDisplayed()
        compose.onNodeWithText("开始精修").assertIsDisplayed()
    }

    @Test
    fun `retouch offers the presets and states what each one changes`() {
        val vm = launch()
        compose.onNodeWithText("照片精修").performClick()
        compose.waitForIdle()

        vm.presetId = "light-change"
        compose.waitForIdle()

        // The preset card sits below the photo picker, so scroll it into view.
        compose.onNodeWithTag("workbench-list")
            .performScrollToNode(hasText("已选：换光线或时段"))

        compose.onNodeWithText("已选：换光线或时段").assertIsDisplayed()
        compose.onNodeWithText("会改动：光照方向与强度、整体色调").assertIsDisplayed()
    }

    @Test
    fun `settings screen exposes both service addresses`() {
        launch()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("模型服务").assertIsDisplayed()
        // The main area asks for one 电脑地址 now; the per-service addresses and the
        // mode switch live behind 高级, so the assertion has to open it first.
        compose.onNodeWithText("电脑地址").assertIsDisplayed()
        compose.onNodeWithText("高级").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("队列地址").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("同步").assertExists()
        compose.onNodeWithText("提示词服务地址").assertExists()
        compose.onNodeWithText("保存并生效").performScrollTo().assertIsDisplayed()
    }
}
