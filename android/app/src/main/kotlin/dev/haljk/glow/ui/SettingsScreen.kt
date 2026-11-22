package dev.haljk.glow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.haljk.glow.data.Settings

/**
 * Model service settings.
 *
 * The app ships with loopback defaults, so nothing private lives in the build.
 * A phone needs the machine's reachable address, which is why this screen exists
 * and why it can test the connection before saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: GlowViewModel, onBack: () -> Unit) {
    val current = vm.settings
    var imageUrl by remember(current) { mutableStateOf(current.imageBaseUrl) }
    var llmUrl by remember(current) { mutableStateOf(current.llmBaseUrl) }
    var model by remember(current) { mutableStateOf(current.llmModel) }
    var imageTimeout by remember(current) { mutableStateOf(current.imageTimeoutSeconds.toString()) }
    var llmTimeout by remember(current) { mutableStateOf(current.llmTimeoutSeconds.toString()) }
    var keepRefs by remember(current) { mutableStateOf(current.keepReferenceCopies) }

    fun draft() = Settings(
        imageBaseUrl = imageUrl,
        llmBaseUrl = llmUrl,
        llmModel = model,
        imageTimeoutSeconds = imageTimeout.toIntOrNull() ?: current.imageTimeoutSeconds,
        llmTimeoutSeconds = llmTimeout.toIntOrNull() ?: current.llmTimeoutSeconds,
        keepReferenceCopies = keepRefs,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("模型服务") },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "阁楼自己不出图，它把活儿交给这两台服务。填你在手机上能访问到的地址。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Field("出图服务地址", imageUrl, { imageUrl = it }, placeholder = Settings.DEFAULT_IMAGE_URL)
            Field("提示词服务地址", llmUrl, { llmUrl = it }, placeholder = Settings.DEFAULT_LLM_URL)
            Field("模型名", model, { model = it }, placeholder = Settings.DEFAULT_LLM_MODEL)
            Field(
                "单次出图最长等待（秒）",
                imageTimeout,
                { imageTimeout = it.filter { ch -> ch.isDigit() } },
                placeholder = "900",
                numeric = true,
            )
            Text(
                "2K 与多参考图精修要几分钟，不建议调小。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Field(
                "写作助手最长等待（秒）",
                llmTimeout,
                { llmTimeout = it.filter { ch -> ch.isDigit() } },
                placeholder = "180",
                numeric = true,
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { keepRefs = !keepRefs }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("保留参考图副本", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "关掉可以省磁盘，但「对比原图」就看不了了",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = keepRefs, onCheckedChange = { keepRefs = it })
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.saveSettings(draft()) },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("保存并生效", fontWeight = FontWeight.SemiBold) }
                TextButton(
                    onClick = {
                        val defaults = Settings()
                        imageUrl = defaults.imageBaseUrl
                        llmUrl = defaults.llmBaseUrl
                        model = defaults.llmModel
                        imageTimeout = defaults.imageTimeoutSeconds.toString()
                        llmTimeout = defaults.llmTimeoutSeconds.toString()
                        keepRefs = defaults.keepReferenceCopies
                        vm.saveSettings(defaults)
                    },
                    modifier = Modifier.heightIn(min = 52.dp),
                ) { Text("恢复默认") }
            }

            TextButton(
                onClick = { vm.probeServices() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text(if (vm.probing) "正在测试…" else "测试连接") }

            ProbeRow("出图服务", vm.imageProbe?.ok, vm.imageProbe?.detail.orEmpty())
            ProbeRow("提示词服务", vm.llmProbe?.ok, vm.llmProbe?.detail.orEmpty())

            Spacer(Modifier.height(8.dp))
            Text(
                "设置存在应用私有目录里，不进任何仓库。换手机重新填一次即可。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    numeric: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Uri,
        ),
    )
}

@Composable
private fun ProbeRow(label: String, ok: Boolean?, detail: String) {
    val color = when (ok) {
        true -> Color(0xFF34A853)
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
