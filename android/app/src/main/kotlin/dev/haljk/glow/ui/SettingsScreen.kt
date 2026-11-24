package dev.haljk.glow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.haljk.glow.data.ModelClient
import dev.haljk.glow.data.Settings
import dev.haljk.glow.ui.theme.ContentMaxWidth
import dev.haljk.glow.ui.theme.glow

/**
 * Model service settings.
 *
 * Organised by what the user is trying to do — connect, wait, store — instead of
 * by the order the fields exist in the backend. The model name and the service's
 * own model id are technical strings with no decision behind them, so they live
 * behind 高级 rather than in the first thing a non-technical user reads.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: GlowViewModel, onBack: () -> Unit) {
    val current = vm.settings
    var mode by remember(current) { mutableStateOf(current.imageApiMode) }
    var imageUrl by remember(current) { mutableStateOf(current.imageBaseUrl) }
    var syncUrl by remember(current) { mutableStateOf(current.imageSyncBaseUrl) }
    var llmUrl by remember(current) { mutableStateOf(current.llmBaseUrl) }
    var model by remember(current) { mutableStateOf(current.llmModel) }
    var imageTimeout by remember(current) { mutableStateOf(current.imageTimeoutSeconds.toString()) }
    var llmTimeout by remember(current) { mutableStateOf(current.llmTimeoutSeconds.toString()) }
    var keepRefs by remember(current) { mutableStateOf(current.keepReferenceCopies) }
    var advancedOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    // Saving used to look like nothing happened: the settings went to disk and the
    // probe dots, which were already "已连接", stayed "已连接". A line that answers
    // "did the save work, and can the phone reach the services" is the feedback.
    var savedAt by remember { mutableStateOf<Long?>(null) }
    var awaitingProbe by remember { mutableStateOf(false) }

    LaunchedEffect(vm.probing, vm.imageProbe, vm.llmProbe) {
        if (awaitingProbe && !vm.probing) {
            awaitingProbe = false
            savedAt = System.currentTimeMillis()
        }
    }

    fun draft() = Settings(
        imageApiMode = mode,
        imageBaseUrl = imageUrl,
        imageSyncBaseUrl = syncUrl,
        llmBaseUrl = llmUrl,
        llmModel = model,
        imageTimeoutSeconds = imageTimeout.toIntOrNull() ?: current.imageTimeoutSeconds,
        llmTimeoutSeconds = llmTimeout.toIntOrNull() ?: current.llmTimeoutSeconds,
        keepReferenceCopies = keepRefs,
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                TopAppBar(
                    title = { Text("模型服务", style = MaterialTheme.typography.headlineSmall) },
                    navigationIcon = { BackIcon(onBack) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    // 8dp past the gesture bar: scrolled to the end, the last button
                    // used to sit flush against the viewport edge with its rounded
                    // corner clipped by it.
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            ) {
                Text(
                    "阁楼自己不出图，它把活儿交给这两台服务。填你在手机上能访问到的地址。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )

                GroupTitle("连接")
                GlowCard {
                    Text(
                        "队列模式：提交任务后由服务排队，手机只负责轮询取图。同步模式退回旧接口，一次请求要等到出图。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChoiceChip(
                            label = "队列",
                            secondLine = "推荐",
                            selected = mode == Settings.MODE_QUEUE,
                            modifier = Modifier.weight(1f),
                            onClick = { mode = Settings.MODE_QUEUE },
                        )
                        ChoiceChip(
                            label = "同步",
                            secondLine = "旧接口",
                            selected = mode == Settings.MODE_SYNC,
                            modifier = Modifier.weight(1f),
                            onClick = { mode = Settings.MODE_SYNC },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Field(
                        if (mode == Settings.MODE_QUEUE) "队列地址" else "同步服务地址",
                        if (mode == Settings.MODE_QUEUE) imageUrl else syncUrl,
                        { if (mode == Settings.MODE_QUEUE) imageUrl = it else syncUrl = it },
                        if (mode == Settings.MODE_QUEUE) Settings.DEFAULT_IMAGE_URL else Settings.DEFAULT_SYNC_URL,
                    )
                    Spacer(Modifier.height(10.dp))
                    Field("提示词服务地址", llmUrl, { llmUrl = it }, Settings.DEFAULT_LLM_URL)
                    // The hint sits under the toggle, not at the far end of the same
                    // row: right-aligned and grey, it read as a second column of the
                    // layout rather than a description of the row above it.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { advancedOpen = !advancedOpen },
                    ) {
                        Text(
                            if (advancedOpen) "收起高级" else "高级",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                        if (!advancedOpen) {
                            Text(
                                "里面是另一个服务地址和模型名",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 10.dp),
                            )
                        }
                    }
                    if (advancedOpen) {
                        // Both addresses are kept so switching modes never loses the
                        // other one; the rollback target is one field, not a retype.
                        Field(
                            if (mode == Settings.MODE_QUEUE) "同步服务地址（回退用）" else "队列地址（备用）",
                            if (mode == Settings.MODE_QUEUE) syncUrl else imageUrl,
                            { if (mode == Settings.MODE_QUEUE) syncUrl = it else imageUrl = it },
                            if (mode == Settings.MODE_QUEUE) Settings.DEFAULT_SYNC_URL else Settings.DEFAULT_IMAGE_URL,
                        )
                        Spacer(Modifier.height(10.dp))
                        Field("模型名（一般不用改）", model, { model = it }, Settings.DEFAULT_LLM_MODEL)
                        Text(
                            "写作助手只认这一个模型名，写别的会被当成仓库名。",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                GroupTitle("等待时长")
                GlowCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field(
                            "出图最长（秒）",
                            imageTimeout,
                            { imageTimeout = it.filter { ch -> ch.isDigit() } },
                            "900",
                            numeric = true,
                            modifier = Modifier.weight(1f),
                        )
                        Field(
                            "助手最长（秒）",
                            llmTimeout,
                            { llmTimeout = it.filter { ch -> ch.isDigit() } },
                            "180",
                            numeric = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "2K 与多参考图精修要几分钟，不建议调小。",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                GroupTitle("本地存储")
                GlowCard {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { keepRefs = !keepRefs }
                            .padding(vertical = 4.dp),
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
                }

                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        awaitingProbe = true
                        savedAt = System.currentTimeMillis()
                        vm.saveSettings(draft())
                    },
                    enabled = !vm.probing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                    shape = RoundedCornerShape(14.dp),
                    // The default disabled pair measures 1.63:1, and this label is doing
                    // work here ("正在保存…"), not merely decorating a dead control.
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(
                        if (vm.probing) "正在保存…" else "保存并生效",
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                SaveFeedback(
                    vm = vm,
                    saved = savedAt != null,
                    probing = vm.probing,
                )

                // Order follows the reading: what happened (feedback), where it happened
                // (two status rows with their endpoints), then the manual action. Stacking
                // the button between them switched alignment twice in 59px.
                ProbeRow("出图服务", vm.imageProbe, vm.settings.imageEndpoint, vm.probing)
                ProbeRow("提示词服务", vm.llmProbe, vm.settings.llmEndpoint, vm.probing)

                // Greyed out, label unchanged: during a probe the progress belongs to the
                // feedback line and the two status rows, not to a second purple "testing"
                // that reads like another action.
                TextButton(
                    onClick = { vm.probeServices() },
                    enabled = !vm.probing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(top = 8.dp),
                ) { Text("测试连接") }

                Spacer(Modifier.height(20.dp))
                Text(
                    "恢复默认地址",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { confirmReset = true }
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                )

                Text(
                    "设置存在应用私有目录，不进仓库；换手机重填一次即可。",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                // Which build is on this phone. Without it, "did the new APK actually
                // install?" could only be answered with adb, and a stale install looked
                // exactly like a broken feature.
                Text(
                    "版本 " + rememberAppVersion().replace("-", " · "),
                    style = MaterialTheme.typography.labelMedium,
                    // outline measured 2.98:1 — too pale for 12sp text that exists to be
                    // read. 85% of onSurfaceVariant is 4.6:1 and still a shade lighter
                    // than the note above it.
                    // 92% lands at 4.49:1 — the 85% version measured 4.13, just under the
                    // usual floor for small text, and still 1.27x lighter than the note.
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                    // 24dp above separates it from the note; the column's own bottom
                    // inset keeps it ~32dp off the gesture bar instead of floating 91dp
                    // above the bottom of the screen.
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复默认地址？") },
            text = { Text("两个地址与等待时长都会回到出厂值（本机 127.0.0.1），手机上多半连不上。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    val defaults = Settings()
                    mode = defaults.imageApiMode
                    imageUrl = defaults.imageBaseUrl
                    syncUrl = defaults.imageSyncBaseUrl
                    llmUrl = defaults.llmBaseUrl
                    model = defaults.llmModel
                    imageTimeout = defaults.imageTimeoutSeconds.toString()
                    llmTimeout = defaults.llmTimeoutSeconds.toString()
                    keepRefs = defaults.keepReferenceCopies
                    vm.saveSettings(defaults)
                }) { Text("恢复默认", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    numeric: Boolean = false,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Uri,
        ),
    )
}

/** Version name straight from the package manager, so it always matches the APK. */
@Composable
private fun rememberAppVersion(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "未知"
    }
}

@Composable
private fun ProbeRow(
    label: String,
    probe: ModelClient.ProbeResult?,
    endpoint: String,
    probing: Boolean = false,
) {
    // While a probe is running the previous result is stale; showing a green dot next
    // to "已连接" contradicts the "正在测试" line right above it.
    val color = when {
        probing -> MaterialTheme.colorScheme.outline
        probe?.ok == true -> MaterialTheme.glow.success
        probe?.ok == false -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
    ) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                "$label · " + when {
                    probing -> "测试中…"
                    probe?.ok == true -> "已连接"
                    probe?.ok == false -> "连不上"
                    else -> "未测试"
                },
                style = MaterialTheme.typography.labelLarge,
            )
            // The address is the first thing to check, and it is the one fact the dot
            // cannot carry. A degraded-but-reachable service keeps its own words, since
            // "connected" would be a lie about the upstream.
            if (probe != null) {
                val detail = probe.detail
                val degraded = probe.ok && detail.isNotBlank() &&
                    !detail.startsWith("已连接") && !detail.startsWith("队列已连接")
                val second = when {
                    probing -> endpoint
                    degraded -> detail
                    probe.ok -> endpoint
                    else -> "$endpoint 无响应"
                }
                Text(
                    second,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * One line under the save button that answers both questions at once: was the file
 * written, and can this phone actually reach the services now.
 */
@Composable
private fun SaveFeedback(vm: GlowViewModel, saved: Boolean, probing: Boolean) {
    if (!saved) return
    val image = vm.imageProbe
    val llm = vm.llmProbe
    // The address lives on the status row below, where it is being diagnosed. Keeping it
    // here as well wrapped this line onto a second one ending in an orphan, and repeated
    // "连不上" three times in two lines.
    val (text, color) = when {
        probing -> "已保存，正在测试…" to MaterialTheme.colorScheme.onSurfaceVariant
        image?.ok == true && llm?.ok == true -> "已保存，两台服务都连上了" to MaterialTheme.glow.success
        image?.ok == false && llm?.ok == false ->
            "已保存，但两台服务都连不上" to MaterialTheme.colorScheme.error
        image?.ok == false -> "已保存，但出图服务连不上" to MaterialTheme.colorScheme.error
        llm?.ok == false -> "已保存，但提示词服务连不上" to MaterialTheme.colorScheme.error
        else -> "已保存" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    )
}
