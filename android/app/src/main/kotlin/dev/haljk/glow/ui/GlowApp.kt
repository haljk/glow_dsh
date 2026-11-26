package dev.haljk.glow.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import dev.haljk.glow.core.AspectRatio
import dev.haljk.glow.core.Density
import dev.haljk.glow.core.Preset
import dev.haljk.glow.core.Preserve
import dev.haljk.glow.core.Role
import dev.haljk.glow.data.JobRegistry
import dev.haljk.glow.ui.theme.ContentMaxWidth
import dev.haljk.glow.ui.theme.glow

private enum class RootScreen { WORKBENCH, LIBRARY, JOBS, SETTINGS }

@Composable
fun GlowApp(vm: GlowViewModel) {
    var screen by remember { mutableStateOf(RootScreen.WORKBENCH) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(vm.message) {
        val text = vm.message ?: return@LaunchedEffect
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        vm.consumeMessage()
    }

    val viewing = vm.viewing
    if (viewing != null) {
        // Without this, system Back finishes the Activity instead of closing the
        // viewer — the user lands on the launcher and loses their place.
        BackHandler { vm.viewing = null }
        ViewerScreen(vm = vm, item = viewing, onClose = { vm.viewing = null })
        return
    }

    when (screen) {
        RootScreen.WORKBENCH -> WorkbenchScreen(
            vm = vm,
            snackbar = snackbar,
            onOpenSettings = { screen = RootScreen.SETTINGS },
            onOpenArchive = { screen = RootScreen.LIBRARY },
            onOpenItem = { name -> vm.activeItems.firstOrNull { it.name == name }?.let { vm.viewing = it } },
            onOpenJobs = { screen = RootScreen.JOBS },
        )

        RootScreen.JOBS -> {
            BackHandler { screen = RootScreen.WORKBENCH }
            JobQueueScreen(
                vm = vm,
                onBack = { screen = RootScreen.WORKBENCH },
                onOpenItem = { name ->
                    vm.activeItems.firstOrNull { it.name == name }?.let { vm.viewing = it }
                },
            )
        }

        RootScreen.LIBRARY -> {
            BackHandler { screen = RootScreen.WORKBENCH }
            LibraryScreen(
                vm = vm,
                snackbar = snackbar,
                onBack = { screen = RootScreen.WORKBENCH },
            )
        }

        RootScreen.SETTINGS -> {
            BackHandler { screen = RootScreen.WORKBENCH }
            SettingsScreen(
                vm = vm,
                onBack = { screen = RootScreen.WORKBENCH },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkbenchScreen(
    vm: GlowViewModel,
    snackbar: SnackbarHostState,
    onOpenSettings: () -> Unit,
    onOpenArchive: () -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenJobs: () -> Unit,
) {
    val job by JobRegistry.current.collectAsState()
    var showPresetSheet by remember { mutableStateOf(false) }
    var showRoleSheetFor by remember { mutableStateOf<String?>(null) }
    var showInstructionSheet by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showOutputSheet by remember { mutableStateOf(false) }

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5),
    ) { uris ->
        if (uris.isNotEmpty()) vm.addRefs(uris.map { it.toString() })
    }
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(job?.stage) {
        val snapshot = job ?: return@LaunchedEffect
        if (!snapshot.isActive) vm.onJobFinished()
    }

    val selectedPreset: Preset? = vm.presetId?.let { id -> vm.presets.firstOrNull { it.id == id } }
    val busy = job?.isActive == true
    val blocked = vm.submitBlockedReason()
    // 后台继续 hides the progress surface; a new job shows it again.
    var progressMinimized by remember(job?.id) { mutableStateOf(false) }
    val newestItemId = vm.activeItems.firstOrNull()?.name
    LaunchedEffect(job?.id, job?.stage) {
        if (job?.stage == JobRegistry.Stage.FAILED) {
            val reason = job?.message?.takeIf { it.isNotBlank() } ?: "这次没生成成功"
            snackbar.showSnackbar(message = reason, actionLabel = "重试", duration = SnackbarDuration.Long)
            JobRegistry.clear()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    TopAppBar(
                        modifier = Modifier.widthIn(max = ContentMaxWidth),
                        title = { Text("阁楼", style = MaterialTheme.typography.headlineSmall) },
                        actions = {
                            // The connection state used to sit on its own row under the bar, spending a
                            // full line on two words. It is a status, not content: it belongs next to the
                            // title, and tapping it is the shortcut into settings that the failing case
                            // needs anyway.
                            // 后台继续 collapses the bar, so a running job needs somewhere to live:
                            // this chip is that place, and it stays visible from any screen.
                            if (job?.isActive == true) {
                                JobChip(job!!, onOpenJobs)
                            }
                            else {
                                // 任务 used to be reachable only from the running chip, so the
                                // history vanished the moment nothing was in flight.
                                TextButton(
                                    onClick = onOpenJobs,
                                    modifier = Modifier.heightIn(min = 40.dp),
                                ) { Text("任务") }
                            }
                            ConnectionPill(
                                imageOk = vm.imageProbe?.ok,
                                llmOk = vm.llmProbe?.ok,
                                probing = vm.probing,
                                onClick = onOpenSettings,
                            )
                            IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = "设置",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                }
                // The grid scrolls underneath the bar; without a hairline the cut
                // reads as a rendering fault rather than as a boundary.
                HorizontalDivider(
                    thickness = 1.dp,
                        // Bar and page are 1.08:1 apart, so scrolled text was cut by
                        // an invisible edge; the outline role is the 3:1 boundary.
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                )
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().imePadding()) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        // Bar and page are 1.08:1 apart, so scrolled text was cut by
                        // an invisible edge; the outline role is the 3:1 boundary.
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    )
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Column(
                            Modifier
                                .widthIn(max = ContentMaxWidth)
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            when {
                                // Running: the bar becomes the progress surface.
                                busy && !progressMinimized -> JobProgressBar(
                                    job!!,
                                    onBackground = { progressMinimized = true },
                                    onCancel = {
                                        JobRegistry.requestCancel()
                                        vm.post("如果还在排队，会取消；已经开跑就停止等待，结果跑完会自动保存")
                                    },
                                )
                                job != null && job!!.stage == JobRegistry.Stage.UNRETRIEVED ->
                                    JobRetrieveBar(
                                        snapshot = job!!,
                                        onRetrieve = { vm.recoverPending() },
                                        onDismiss = { JobRegistry.clear() },
                                    )
                                job != null && job!!.stage == JobRegistry.Stage.DONE ->
                                    JobDoneBar(
                                        onView = newestItemId?.let { name -> { onOpenItem(name) } },
                                        onDismiss = { JobRegistry.clear() },
                                    )
                                else -> {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 20.dp),
                                        contentAlignment = Alignment.CenterStart,
                                    ) {
                                        if (blocked != null) {
                                            Text(
                                                blocked,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    PrimaryAction(
                                        label = when {
                                            vm.scenario == Scenario.TEXT -> "生成图片"
                                            vm.scenario == Scenario.RETOUCH -> "开始精修"
                                            else -> "开始合成"
                                        },
                                        enabled = blocked == null,
                                        busy = false,
                                        onClick = { vm.submit() },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            // Landscape puts ~411dp of height on screen: the cards and the status
            // strip have to give some back or the input falls below the fold.
            val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 520
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxHeight()
                    .testTag("workbench-list"),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!compact) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    ScenarioRow(current = vm.scenario, compact = compact) { picked ->
                        vm.scenario = picked
                        if (picked == Scenario.COMBINE) vm.presetId = null
                    }
                }


                if (vm.scenario != Scenario.TEXT) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        GlowCard {
                            val limit = vm.maxRefs
                            SectionHeader(
                                title = if (vm.scenario == Scenario.RETOUCH) "① 选一张照片" else "① 放入参考图",
                                hint = if (vm.scenario == Scenario.RETOUCH) {
                                    "最多 $limit 张；第 1 张是要改的照片"
                                } else {
                                    "最多 $limit 张，每张说明用途"
                                },
                                action = if (vm.refs.isEmpty()) null else "更换",
                                onAction = if (vm.refs.isEmpty()) null else {
                                    { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                                },
                            )
                            RefStrip(
                                refs = vm.refs,
                                onAdd = {
                                    pickImages.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                                onRemove = { vm.removeRef(it) },
                                onPickRole = { showRoleSheetFor = it },
                                showRoles = vm.scenario == Scenario.COMBINE,
                                rolesEditable = !vm.settings.rawInstruction,
                            )
                        }
                    }
                }

                if (vm.scenario == Scenario.RETOUCH) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        if (vm.settings.rawInstruction) {
                            // The preset card is hidden rather than left inert: a control
                            // that can be tapped and changes nothing is worse than absent.
                            GlowCard {
                                Text("直发模式：预设这次不参与", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "下面写清要改什么，写什么就发什么；「图 1」会自动换成出图服务认的写法。",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                        GlowCard {
                            SectionHeader(
                                title = "② 要做什么",
                                hint = if (selectedPreset == null) "可以跳过，下面直接写要求" else null,
                                action = if (selectedPreset == null) "选择" else "更换",
                                onAction = { showPresetSheet = true },
                            )
                            if (selectedPreset != null) {
                                Text(
                                    "已选：${selectedPreset.label}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    selectedPreset.summary,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "会改动：" + Preserve.describeChanges(selectedPreset.mayChange).joinToString("、"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                Text(
                                    selectedPreset.caution,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                TextButton(
                                    onClick = { vm.presetId = null },
                                    modifier = Modifier.heightIn(min = 44.dp),
                                ) { Text("不用预设") }
                            }
                        }
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    GlowCard {
                        val title = when (vm.scenario) {
                            Scenario.TEXT -> "写一句描述"
                            // 直发模式 hides the preset card, so the wording section becomes
                            // the second step instead of the third.
                            Scenario.RETOUCH ->
                                if (vm.settings.rawInstruction) "② 写完整指令" else "③ 补充要求"
                            Scenario.COMBINE -> "② 想怎么合"
                        }
                        val hint = when (vm.scenario) {
                            Scenario.TEXT -> "大白话就行，例如「雨后的旧书店，暖黄吊灯」"
                            Scenario.RETOUCH -> when {
                                vm.settings.rawInstruction -> "直发模式：要把要求写全，预设不参与"
                                selectedPreset != null -> "可以不写"
                                else -> "写清只改哪里，例如「背景换成浅灰 studio」"
                            }
                            Scenario.COMBINE -> "例如「把图 1 的人放进图 2 的咖啡馆」"
                        }
                        SectionHeader(
                            title = title,
                            action = "帮我写",
                            onAction = { vm.sharpenPrompt { vm.prompt = it } },
                        )
                        OutlinedTextField(
                            value = vm.prompt,
                            onValueChange = { vm.prompt = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 104.dp),
                            placeholder = { Text(hint, style = MaterialTheme.typography.bodyMedium) },
                            textStyle = MaterialTheme.typography.bodyLarge,
                            shape = RoundedCornerShape(12.dp),
                        )
                        if (vm.favorites.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            FavoritesRow(
                                favorites = vm.favorites,
                                onUse = { vm.prompt = it },
                                onRemove = { vm.removeFavorite(it) },
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            // Plain clickable text, not TextButton: the button's own
                            // horizontal padding pulled both links 13dp inside the
                            // field above them, and the left one lost its alignment
                            // with the field's left edge too.
                            Text(
                                "存为常用（${vm.favorites.size}/5）",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { vm.saveFavorite() }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                            )
                            Text(
                                "看会发送什么",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showInstructionSheet = true }
                                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                            )
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    GlowCard(onClick = { showOutputSheet = true }) {
                        // One row instead of a seven-chip grid: the chip labels ("超清横版"
                        // vs "电脑壁纸") were both 16:9 and neither said how big the result
                        // would be. Ratio and quality are two questions; ask them in a sheet.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "输出",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(vm.outputSummary(), style = MaterialTheme.typography.titleMedium)
                            }
                            Text(
                                vm.resolvedSizeLabel(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "以前做的（${vm.activeCount}）",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    // Same trap as the 帮我写 link: TextButton centres its label inside a
                    // 58dp minimum, which pulled this one 12dp off the 16dp rail.
                    Text(
                        "全部 ›",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenArchive() }
                            .padding(horizontal = 4.dp, vertical = 12.dp),
                    )
                    }
                }

                if (vm.activeItems.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                "这里还是空的。第一次用，先点上面的「生成图片」。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                } else {
                    // The home grid is a shelf, not the archive: show the newest few and
                    // send the rest to 全部作品, so the page does not grow with history.
                    items(vm.activeItems.take(8), key = { it.name }) { item ->
                        GalleryTile(
                            item = item,
                            file = vm.fileOf(item),
                            onOpen = { vm.openInSideBySide = false; vm.viewing = item },
                            onCompare = if (item.refFiles.isNotEmpty()) {
                                { vm.openInSideBySide = true; vm.viewing = item }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }

    if (showPresetSheet) {
        PresetSheet(
            presets = vm.presets,
            current = vm.presetId,
            onPick = { vm.presetId = it; showPresetSheet = false },
            onDismiss = { showPresetSheet = false },
        )
    }

    val roleTarget = showRoleSheetFor
    if (roleTarget != null) {
        RoleSheet(
            current = vm.refs.firstOrNull { it.uri == roleTarget }?.role ?: Role.SUBJECT,
            onPick = { vm.setRole(roleTarget, it); showRoleSheetFor = null },
            onDismiss = { showRoleSheetFor = null },
        )
    }

    if (showOutputSheet) {
        ModalBottomSheet(
            onDismissRequest = { showOutputSheet = false },
            // Sheets and dialogs inherited a violet container while cards are white;
            // one surface token everywhere keeps purple for state and the primary action.
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            OutputSheet(vm) { showOutputSheet = false }
        }
    }

    if (showInstructionSheet) {
        // A dialog clipped the text with no way to scroll it, and it showed the same
        // sentence twice (once as 你写的, once inside the payload). One scrollable block,
        // with the user's own sentence marked, answers the only question this panel has.
        ModalBottomSheet(
            onDismissRequest = { showInstructionSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
            ) {
                Text("会发送的指令", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (vm.settings.rawInstruction) {
                        // Says what it does and does what it says: the only edit left in this
                        // mode is the picture token, and hiding that would be the same kind of
                        // half-truth this panel exists to prevent.
                        "直发模式：只发你写的这段文字，模板一个字都不加。「图 1」这类写法会换成出图服务认的写法。"
                    } else {
                        "紫色是你写的那句，其余是应用补的约束。"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                val (typed, sent) = vm.previewParts()
                val accent = MaterialTheme.colorScheme.primary
                val accentBg = MaterialTheme.colorScheme.primaryContainer
                val marked = remember(typed, sent) {
                    buildAnnotatedString {
                        val needle = typed.takeIf { it.isNotBlank() && it != "（还没写）" }
                        if (needle == null) {
                            append(sent)
                        } else {
                            var cursor = 0
                            while (true) {
                                val at = sent.indexOf(needle, cursor)
                                if (at < 0) break
                                append(sent.substring(cursor, at))
                                withStyle(SpanStyle(color = accent, background = accentBg)) {
                                    append(needle)
                                }
                                cursor = at + needle.length
                            }
                            append(sent.substring(cursor))
                        }
                    }
                }
                Box(
                    Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        marked,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showInstructionSheet = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("知道了") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}


/**
 * Size options as fixed three-column rows.
 *
 * A FlowRow with weighted chips stretches the last row when it holds a single
 * chip ("小图试手 · 512²" spanned the full width), which looks like a button that
 * lost its siblings. Chunking keeps every chip the same size and the columns
 * aligned.
 */
@Composable
private fun OutputSheet(vm: GlowViewModel, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text("输出设置", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            "最终会是 ${vm.resolvedSizeLabel()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Text("比例", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        // Five ratios on one row: the old 3+2 split left 29% of the second row empty,
        // which reads as a layout fault rather than as a group. 跟随原图 gets its own
        // row when it applies — it is a different kind of answer from a fixed ratio.
        if (vm.scenario != Scenario.TEXT) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip(
                    label = "跟随原图",
                    secondLine = if (vm.autoSize) "当前" else null,
                    selected = vm.autoSize,
                    modifier = Modifier.weight(1f),
                    onClick = { vm.autoSize = true },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AspectRatio.entries.forEach { entry ->
                ChoiceChip(
                    label = entry.id,
                    secondLine = entry.label,
                    // In 文生图 autoSize stays true in state but means nothing, so
                    // "!autoSize" alone left every ratio unselected: the app row
                    // said 1:1 while the sheet showed no selection at all.
                    selected = (vm.scenario == Scenario.TEXT || !vm.autoSize) && vm.ratio == entry,
                    modifier = Modifier.weight(1f),
                    onClick = { vm.autoSize = false; vm.ratio = entry },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("清晰度", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        DensityChips(vm.quality) { vm.quality = it }
        if (vm.scenario == Scenario.TEXT) {
            Spacer(Modifier.height(16.dp))
            Text("张数", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 2).forEach { value ->
                    ChoiceChip(
                        label = "$value 张",
                        selected = vm.batchCount == value,
                        onClick = { vm.batchCount = value },
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("完成", style = MaterialTheme.typography.titleLarge) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun RefStrip(
    refs: List<RefSlot>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onPickRole: (String) -> Unit,
    showRoles: Boolean,
    /** 直发模式不拼角色句，角色表就不该还能点开——点了一点作用都没有。 */
    rolesEditable: Boolean = true,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(refs) { slot ->
            val bitmap = rememberUriBitmap(slot.uri, targetPx = 360)
            Column(Modifier.width(92.dp)) {
                val index = refs.indexOf(slot) + 1
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .then(if (rolesEditable) Modifier.clickable { onPickRole(slot.uri) } else Modifier),
                    ) {
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.glow.scrim.copy(alpha = 0.62f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$index",
                            color = MaterialTheme.glow.onScrim,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap,
                            contentDescription = "参考图",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    }
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(24.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.glow.scrim.copy(alpha = 0.55f))
                            .clickable { onRemove(slot.uri) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("×", color = MaterialTheme.glow.onScrim, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    if (!rolesEditable) "只当参考图用" else if (showRoles) slot.role.label else "作为：" + slot.role.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Column(
                Modifier
                    .width(92.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .clickable { onAdd() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("＋", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Text("照片", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Presets grouped by what the user is trying to do, not by row order. */
private val PRESET_GROUPS = listOf(
    "改外观" to listOf("bg-replace", "outfit-replace", "hair-change", "light-change"),
    "修瑕疵" to listOf("expression", "clean-up", "restore"),
    "出图用途" to listOf("cutout-white", "id-photo"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSheet(
    presets: List<Preset>,
    current: String?,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 560.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
        ) {
            item {
                Text("要做什么", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
            }
            PRESET_GROUPS.forEach { (groupTitle, ids) ->
                item {
                    Text(
                        groupTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    )
                }
                items(ids.size) { index ->
                    val preset = presets.firstOrNull { it.id == ids[index] } ?: return@items
                    val selected = preset.id == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onPick(preset.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                preset.summary,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                TextButton(onClick = { onPick(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("不用预设，直接写要求")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleSheet(current: Role, onPick: (Role) -> Unit, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("这张图当作什么", style = MaterialTheme.typography.titleMedium)
            Text(
                "说清用途，模型才知道哪张该保留、哪张该借用",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Role.entries.forEach { role ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (role == current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { onPick(role) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(role.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    if (role == current) Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(vm: GlowViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    // 归档箱 is not a separate place: it is the same list with a filter, so a result has
    // one home instead of two screens that both claim to show "what you made".
    var archivedOnly by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<Set<String>>(emptySet()) }
    val list = if (archivedOnly) vm.archivedItems else vm.activeItems
    val selecting = selected.isNotEmpty()

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                TopAppBar(
                    title = {
                        Text(
                            if (selecting) "已选 ${selected.size} 项" else "全部作品",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    },
                    navigationIcon = {
                        if (selecting) {
                            IconButton(
                                onClick = { selected = emptySet() },
                                modifier = Modifier.size(48.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "退出多选",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            BackIcon(onBack)
                        }
                    },
                    actions = {
                        if (selecting) {
                            TextButton(
                                onClick = { selected = list.map { it.name }.toSet() },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text("全选") }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                if (!selecting) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ChoiceChip(
                            label = "全部 ${vm.activeCount}",
                            selected = !archivedOnly,
                            onClick = { archivedOnly = false },
                        )
                        ChoiceChip(
                            label = "已归档 ${vm.archivedCount}",
                            selected = archivedOnly,
                            onClick = { archivedOnly = true },
                        )
                    }
                }
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                )
            }
        },
        bottomBar = {
            if (selecting) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                list.filter { it.name in selected }.forEach {
                                    vm.archive(it, archived = !archivedOnly)
                                }
                                selected = emptySet()
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) { Text(if (archivedOnly) "移出归档" else "归档") }
                        Button(
                            onClick = { pendingDelete = selected },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) { Text("删除") }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (archivedOnly) "没有归档的作品。\n在结果上点「归档」就会收进来，文件不会被删。"
                        else "还没有作品。回到工作台写一句描述就能生成第一张。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxHeight(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(list, key = { it.name }) { item ->
                        GalleryTile(
                            item = item,
                            file = vm.fileOf(item),
                            onOpen = {
                                if (selecting) {
                                    selected = if (item.name in selected) selected - item.name else selected + item.name
                                } else {
                                    vm.viewing = item
                                }
                            },
                            onLongPress = { selected = selected + item.name },
                            onCompare = if (item.refFiles.isNotEmpty() && !selecting) {
                                { vm.openInSideBySide = true; vm.viewing = item }
                            } else {
                                null
                            },
                            selected = item.name in selected,
                        )
                    }
                }
            }
        }
    }

    if (pendingDelete.isNotEmpty()) {
        val names = pendingDelete
        AlertDialog(
            onDismissRequest = { pendingDelete = emptySet() },
            title = { Text("删除 ${names.size} 项？") },
            text = {
                Text(
                    "这 ${names.size} 项的图片、描述与参考图副本会一起删掉，不能恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    list.filter { it.name in names }.forEach { vm.delete(it) }
                    pendingDelete = emptySet()
                    selected = emptySet()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = emptySet() }) { Text("取消") }
            },
        )
    }
}

@Composable
fun BackIcon(onBack: () -> Unit) {
    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
    }
}

/** Exposed so a future settings screen can reset to the shipped defaults. */
fun defaultDensity(): Density = Density.NORMAL

/** Compact "still running" chip in the app bar; tapping it opens the 任务 screen. */
@Composable
private fun JobChip(snapshot: JobRegistry.Snapshot, onClick: () -> Unit) {
    val label = when {
        snapshot.stage == JobRegistry.Stage.QUEUED && snapshot.queuePosition > 0 -> "排队 ${snapshot.queuePosition}"
        snapshot.percent > 0 -> "生成中 ${snapshot.percent}%"
        else -> "排队中"
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
        )
    }
}

/**
 * 任务 screen: the live job plus what already ran.
 *
 * 后台继续 used to be a one-way door — the bar collapsed and the only trace left was the
 * system notification. This page is the in-app answer to "what is running, and how did the
 * last few go".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JobQueueScreen(
    vm: GlowViewModel,
    onBack: () -> Unit,
    onOpenItem: (String) -> Unit,
) {
    val job by JobRegistry.current.collectAsState()
    // Read from disk, not from the in-memory history: the record outlives the
    // process, and it is the only thing that knows where the originals are.
    val stored = remember(job?.stage, job?.percent, job?.id) { vm.taskStore.list() }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                TopAppBar(
                    title = { Text("任务", style = MaterialTheme.typography.headlineSmall) },
                    navigationIcon = { BackIcon(onBack) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            val active = job
            if (active != null && active.isActive) {
                GlowCard {
                    Text(
                        when (active.stage) {
                            JobRegistry.Stage.QUEUED ->
                                if (active.queuePosition > 0) "排队中 · 第 ${active.queuePosition} 位" else "排队中"
                            JobRegistry.Stage.RUNNING ->
                                if (active.percent > 0) "正在生成 · ${active.percent}%" else "正在生成"
                            else -> "正在处理"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        active.summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (active.etaSeconds > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "约剩 ${active.etaSeconds / 60}:${(active.etaSeconds % 60).toString().padStart(2, '0')}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                JobRegistry.requestCancel()
                                vm.post("如果还在排队，会取消；已经开跑就停止等待，结果跑完会自动保存")
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) { Text("取消") }
                        Button(
                            onClick = onBack,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text("回工作台") }
                    }
                }
                Spacer(Modifier.height(16.dp))
            } else {
                Text(
                    "现在没有在跑的任务。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }

            if (stored.isNotEmpty()) {
                Text("最近的任务", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                stored.forEach { record ->
                    val done = record.status == dev.haljk.glow.data.TaskRecord.STATUS_DONE
                    val failed = record.status == dev.haljk.glow.data.TaskRecord.STATUS_FAILED
                    // The server still has this one; only our copy of the answer is
                    // missing. That is a "come back for it" state, not a failure.
                    val unretrieved = record.status == dev.haljk.glow.data.TaskRecord.STATUS_UNRETRIEVED
                    GlowCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    when {
                                        done -> "已完成"
                                        unretrieved -> "结果待取回"
                                        failed -> "没成功"
                                        else -> "进行中"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = when {
                                        failed -> MaterialTheme.colorScheme.error
                                        unretrieved -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                                Text(
                                    record.summary.ifBlank { record.note.ifBlank { "—" } },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                                if ((failed || unretrieved) && record.message.isNotBlank()) {
                                    Text(
                                        record.message,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (failed) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (record.referenceFiles.isNotEmpty()) {
                                    Text(
                                        "原图 ${record.referenceFiles.size} 张已随任务保存",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            // Open this record's own result. The old code opened whatever
                            // was newest in the gallery, which is a different picture as
                            // soon as two tasks finish.
                            val result = record.resultNames.firstOrNull()
                            if (done && result != null) {
                                TextButton(onClick = { onOpenItem(result) }) { Text("查看") }
                            } else if (unretrieved) {
                                TextButton(onClick = { vm.recoverTask(record) }) { Text("继续取回") }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                TextButton(onClick = { JobRegistry.forgetAll(); vm.taskStore.clear() }) { Text("清空记录") }
            }
        }
    }
}

/**
 * The bar for a task the server still owns but we could not finish collecting.
 *
 * Deliberately not styled as an error: nothing failed, the phone lost the connection
 * (or the process) at the wrong moment, and the task id is on disk. 继续取回 therefore
 * re-attaches to the very same server-side task instead of paying for a second生成.
 */
@Composable
private fun JobRetrieveBar(
    snapshot: JobRegistry.Snapshot,
    onRetrieve: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text("结果还在服务端，没取回来", style = MaterialTheme.typography.titleSmall)
        Text(
            snapshot.message.ifBlank { "任务还在服务端，随时可以取回" },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onRetrieve,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("继续取回") }
            Button(
                onClick = onDismiss,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text("以后再说") }
        }
    }
}
