package dev.haljk.glow.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import dev.haljk.glow.ui.theme.GlowTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Dumps the composed semantics tree of each screen.
 *
 * `captureToImage` needs a real draw pass, which Robolectric cannot provide, so
 * the evidence written here is the rendered node tree: every element that is
 * actually on screen, in order, with its label. It is what a human would read
 * off a screenshot, minus the pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiTreeDumpTest {

    @get:Rule
    val compose = createComposeRule()

    private val outDir = File("build/ui-tree").also { it.mkdirs() }

    private fun dump(name: String) {
        compose.waitForIdle()
        val tree = compose.onRoot().printToString(maxDepth = 100)
        val file = File(outDir, "$name.txt")
        file.writeText(tree)
        assertTrue("empty tree for $name", tree.length > 200)
    }

    private fun launch(): GlowViewModel {
        val vm = GlowViewModel(RuntimeEnvironment.getApplication())
        compose.setContent { GlowTheme { GlowApp(vm) } }
        compose.waitForIdle()
        return vm
    }

    @Test
    fun `text mode tree`() {
        launch()
        dump("01-workbench-text")
    }

    @Test
    fun `retouch tree with a preset`() {
        val vm = launch()
        compose.onNodeWithText("照片精修").performClick()
        compose.waitForIdle()
        vm.presetId = "bg-replace"
        compose.waitForIdle()
        dump("02-workbench-retouch")
    }

    @Test
    fun `combine tree`() {
        launch()
        compose.onNodeWithText("多图合成").performClick()
        compose.waitForIdle()
        dump("03-workbench-combine")
    }

    @Test
    fun `settings tree`() {
        launch()
        compose.onNodeWithText("设置").performClick()
        compose.waitForIdle()
        dump("04-settings")
    }
}
