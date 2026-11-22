package dev.haljk.glow.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import dev.haljk.glow.core.Density
import dev.haljk.glow.core.Preset
import dev.haljk.glow.core.Preserve
import dev.haljk.glow.core.Role
import dev.haljk.glow.data.JobRegistry
import dev.haljk.glow.ui.theme.ContentMaxWidth
import dev.haljk.glow.ui.theme.glow

private enum class RootScreen { WORKBENCH, ARCHIVE, SETTINGS }

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
            onOpenArchive = { screen = RootScreen.ARCHIVE },
        )

        RootScreen.ARCHIVE -> {
            BackHandler { screen = RootScreen.WORKBENCH }
            ArchiveScreen(
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
) {
    val job by JobRegistry.current.collectAsState()
    var showPresetSheet by remember { mutableStateOf(false) }
    var showRoleSheetFor by remember { mutableStateOf<String?>(null) }
    var showInstructionSheet by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }

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
    LaunchedEffect(job?.status) {
        val snapshot = job ?: return@LaunchedEffect
        if (snapshot.status != JobRegistry.Status.RUNNING) vm.onJobFinished()
    }

    val selectedPreset: Preset? = vm.presetId?.let { id -> vm.presets.firstOrNull { it.id == id } }
    val busy = job?.status == JobRegistry.Status.RUNNING
    val blocked = vm.submitBlockedReason()

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
                            TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("设置")
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
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                )
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().imePadding()) {
                    // Same hairline treatment as the top bar, so scrolled content
                    // dissolves into the bar instead of being cut by it.
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                    )
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        Column(
                            Modifier
                                .widthIn(max = ContentMaxWidth)
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            // A fixed slot: showing and hiding this line must not move
                            // the button the thumb is already aiming at.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 20.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (blocked != null && !busy) {
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
                                    busy -> "正在生成"
                                    vm.scenario == Scenario.TEXT -> "生成图片"
                                    vm.scenario == Scenario.RETOUCH -> "开始精修"
                                    else -> "开始合成"
                                },
                                enabled = blocked == null || busy,
                                busy = busy,
                                onClick = { if (!busy) vm.submit() },
                            )
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
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!compact) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        StatusStrip(vm)
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    ScenarioRow(current = vm.scenario, compact = compact) { picked ->
                        vm.scenario = picked
                        if (picked == Scenario.COMBINE) vm.presetId = null
                    }
                }

                if (job != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        JobBanner(
                            job!!,
                            onDismiss = { JobRegistry.clear() },
                            onGiveUp = {
                                JobRegistry.clear()
                                vm.post("服务端仍会跑完，结果会进「以前做的」")
                            },
                        )
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
                            )
                        }
                    }
                }

                if (vm.scenario == Scenario.RETOUCH) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
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

                item(span = { GridItemSpan(maxLineSpan) }) {
                    GlowCard {
                        val title = when (vm.scenario) {
                            Scenario.TEXT -> "写一句描述"
                            Scenario.RETOUCH -> "③ 补充要求"
                            Scenario.COMBINE -> "② 想怎么合"
                        }
                        val hint = when (vm.scenario) {
                            Scenario.TEXT -> "大白话就行，例如「雨后的旧书店，暖黄吊灯」"
                            Scenario.RETOUCH -> if (selectedPreset != null) "可以不写" else "写清只改哪里，例如「背景换成浅灰 studio」"
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
                            TextButton(
                                onClick = { vm.saveFavorite() },
                                modifier = Modifier.heightIn(min = 44.dp),
                            ) { Text("存为常用（${vm.favorites.size}/5）") }
                            TextButton(
                                onClick = { showInstructionSheet = true },
                                modifier = Modifier.heightIn(min = 44.dp),
                            ) { Text("看会发送什么") }
                        }
                    }
                }

                item(span = { GridItemSpan(maxLineSpan) }) {
                    GlowCard {
                        SectionHeader(
                            title = if (vm.scenario == Scenario.TEXT) "画面尺寸" else "输出清晰度",
                            hint = vm.resolvedSizeLabel(),
                        )
                        if (vm.scenario == Scenario.TEXT) {
                            SizeFlow(vm)
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("张数", style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.width(10.dp))
                                listOf(1, 2).forEach { value ->
                                    ChoiceChip(
                                        label = "$value 张",
                                        selected = vm.batchCount == value,
                                        modifier = Modifier.padding(end = 8.dp),
                                        onClick = { vm.batchCount = value },
                                    )
                                }
                            }
                        } else {
                            DensityChips(vm.density) { vm.density = it }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ChoiceChip(
                                    label = "跟随原图比例",
                                    selected = vm.autoSize,
                                    onClick = { vm.autoSize = true },
                                )
                                Spacer(Modifier.width(8.dp))
                                ChoiceChip(
                                    label = "自己指定",
                                    selected = !vm.autoSize,
                                    onClick = { vm.autoSize = false },
                                )
                            }
                            if (!vm.autoSize) {
                                Spacer(Modifier.height(8.dp))
                                SizeFlow(vm)
                            }
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
                        TextButton(onClick = onOpenArchive, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("归档箱（${vm.archivedCount}）")
                        }
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
                    items(vm.activeItems, key = { it.name }) { item ->
                        GalleryTile(item = item, file = vm.fileOf(item), onOpen = { vm.viewing = item })
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

    if (showInstructionSheet) {
        AlertDialog(
            onDismissRequest = { showInstructionSheet = false },
            confirmButton = {
                TextButton(onClick = { showInstructionSheet = false }) { Text("知道了") }
            },
            title = { Text("会发送这段指令") },
            text = { Text(vm.previewInstruction(), style = MaterialTheme.typography.bodyMedium) },
        )
    }
}

/** One line, and only when it has something to say: two green "connected" chips
 *  in the top bar spent the most valuable strip of the screen on good news. */
@Composable
private fun StatusStrip(vm: GlowViewModel) {
    val imageOk = vm.imageProbe?.ok
    val llmOk = vm.llmProbe?.ok
    val trouble = vm.imageProbe?.ok == false || vm.llmProbe?.ok == false
    Row(
        Modifier.fillMaxWidth().padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            vm.probing && vm.imageProbe == null -> StatusDot(null, "正在检查服务…")
            trouble -> {
                StatusDot(false, if (imageOk == false) "出图服务未连接" else "写作助手未连接")
                Spacer(Modifier.width(8.dp))
                Text(
                    "点右上角设置检查地址",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            else -> StatusDot(true, "服务已连接")
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
private fun SizeFlow(vm: GlowViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.sizeChoices.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEachIndexed { offset, choice ->
                    val index = vm.sizeChoices.indexOf(choice)
                    val parts = choice.label.split(' ')
                    ChoiceChip(
                        label = parts.first(),
                        secondLine = parts.drop(1).joinToString(" ").ifBlank { null },
                        selected = index == vm.sizeChoiceIndex,
                        modifier = Modifier.weight(1f),
                        onClick = { vm.sizeChoiceIndex = index },
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun RefStrip(
    refs: List<RefSlot>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onPickRole: (String) -> Unit,
    showRoles: Boolean,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(refs) { slot ->
            val bitmap = rememberUriBitmap(slot.uri, targetPx = 360)
            Column(Modifier.width(92.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onPickRole(slot.uri) },
                ) {
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
                    if (showRoles) slot.role.label else "作为：" + slot.role.label,
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
private fun ArchiveScreen(vm: GlowViewModel, snackbar: SnackbarHostState, onBack: () -> Unit) {
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.fillMaxWidth()) {
                TopAppBar(
                    title = { Text("归档箱（${vm.archivedCount}）", style = MaterialTheme.typography.headlineSmall) },
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
            if (vm.archivedItems.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "归档箱是空的。\n在结果上点「归档」就会收到这里，文件不会被删。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxHeight(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(vm.archivedItems, key = { it.name }) { item ->
                        Column {
                            GalleryTile(item = item, file = vm.fileOf(item), onOpen = { vm.viewing = item })
                            Row {
                                TextButton(
                                    onClick = { vm.archive(item, archived = false) },
                                    modifier = Modifier.heightIn(min = 44.dp),
                                ) { Text("移出") }
                                TextButton(
                                    onClick = { vm.delete(item) },
                                    modifier = Modifier.heightIn(min = 44.dp),
                                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
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
