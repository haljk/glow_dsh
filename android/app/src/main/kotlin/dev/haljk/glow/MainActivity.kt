package dev.haljk.glow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.haljk.glow.ui.GlowApp
import dev.haljk.glow.ui.GlowViewModel
import dev.haljk.glow.ui.theme.GlowTheme

class MainActivity : ComponentActivity() {

    private val vm: GlowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlowTheme {
                GlowApp(vm)
            }
        }
    }
}
