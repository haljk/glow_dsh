package dev.haljk.glow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.haljk.glow.ui.GlowApp
import dev.haljk.glow.ui.GlowViewModel
import dev.haljk.glow.ui.theme.GlowTheme

class MainActivity : ComponentActivity() {

    // The default factory supplies both Application and SavedStateHandle, which is
    // what keeps the workbench state across the Activity destructions Android
    // performs while the foreground service keeps the process alive.
    private val vm: GlowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GlowTheme {
                // Probe again whenever the app comes back: a stale "未连接" from
                // before a screen lock would otherwise stay on screen.
                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    vm.probeServices()
                }
                GlowApp(vm)
            }
        }
    }
}
