package dev.haljk.glow.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.haljk.glow.core.Density
import dev.haljk.glow.core.Preset
import dev.haljk.glow.core.Preserve
import dev.haljk.glow.core.Role
import dev.haljk.glow.data.JobRegistry
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext

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

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5),
    ) { uris ->
        if (uris.isNotEmpty()) vm.addRefs(uris.map { it.toString() })
    }
    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(job?.status) {
        val snapshot = job ?: return@LaunchedEffect
        if (snapshot.status != JobRegistry.Status.RUNNING) vm.onJobFinished()
    }

    val selectedPreset: Preset? = vm.presetId?.let { id -> vm.presets.firstOrNull { it.id == id } }
    val busy = job?.status == JobRegistry.Status.RUNNING

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("阁楼", style = MaterialTheme.typography.headlineSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatusDot(
                                ok = vm.imageProbe?.ok,
                                label = when {
                                    vm.probing && vm.imageProbe == null -> "出图检查中…"
                                    vm.imageProbe?.ok == true -> "出图已连接"
                                    else -> "出图未连接"
                                },
                            )
                            StatusDot(
                                ok = vm.llmProbe?.ok,
                                label = when {
                                    vm.probing && vm.llmProbe == null -> "助手检查中…"
                                    vm.llmProbe?.ok == true -> "助手已连接"
                                    else -> "助手未连接"
                                },
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("设置")
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                // targetSdk 36 enforces edge-to-edge, so without this the primary
                // button sits under the gesture bar and loses part of its touch area.
                Column(
                    Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    val blocked = vm.submitBlockedReason()
                    if (blocked != null && !busy) {
                        Text(
                            blocked,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    PrimaryAction(
                        label = when {
                            busy -> "正在生成…"
                            vm.scenario == Scenario.TEXT -> "生成图片"
                            vm.scenario == Scenario.RETOUCH -> "开始精修"
                            else -> "开始合成"
                        },
                        enabled = true,
                        busy = busy,
                        onClick = { vm.submit() },
                    )
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("workbench-list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ScenarioRow(current = vm.scenario) { picked ->
                    vm.scenario = picked
                    if (picked == Scenario.COMBINE) vm.presetId = null
                }
            }

            if (job != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    JobBanner(job!!, onDismiss = { JobRegistry.clear() })
                }
            }

            // ---- reference images
            if (vm.scenario != Scenario.TEXT) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    GlowCard {
                        val limit = vm.maxRefs
                        SectionHeader(
                            title = if (vm.scenario == Scenario.RETOUCH) "① 选一张照片" else "① 放入参考图",
                            hint = if (vm.scenario == Scenario.RETOUCH)
                                "最多 $limit 张；第 1 张是要改的照片"
                            else
                                "最多 $limit 张，每张说明它的用途",
                            action = "添加",
                            onAction = {
                                pickImages.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
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

            // ---- preset (retouch only)
            if (vm.scenario == Scenario.RETOUCH) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    GlowCard {
                        SectionHeader(
                            title = "② 要做什么",
                            // Only when nothing is chosen: once a preset is active the
                            // card below already states its name and summary.
                            hint = if (selectedPreset == null) "可以跳过，下面直接写要求" else null,
                            action = if (selectedPreset == null) "选择" else "更换",
                            onAction = { showPresetSheet = true },
                        )
                        if (selectedPreset != null) {
                            val changes = Preserve.describeChanges(selectedPreset.mayChange)
                            // The label must be visible: after the sheet closes, the
                            // summary alone does not tell the user which preset is active.
                            Text(
                                "已选：${selectedPreset.label}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                selectedPreset.summary,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "会改动：" + changes.joinToString("、"),
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

            // ---- prompt / note
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
                        // The field's own placeholder carries the example, so repeating it
                        // here would say the same thing twice.
                        hint = null,
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
                        shape = RoundedCornerShape(14.dp),
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
                        Modifier.fillMaxWidth().padding(top = 6.dp),
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

            // ---- size
            item(span = { GridItemSpan(maxLineSpan) }) {
                GlowCard {
                    SectionHeader(
                        title = if (vm.scenario == Scenario.TEXT) "画面尺寸" else "输出清晰度",
                        hint = vm.resolvedSizeLabel(),
                    )
                    if (vm.scenario == Scenario.TEXT) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(vm.sizeChoices.size) { index ->
                                ChoiceChip(
                                    label = vm.sizeChoices[index].label,
                                    selected = index == vm.sizeChoiceIndex,
                                    onClick = { vm.sizeChoiceIndex = index },
                                )
                            }
                        }
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
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(vm.sizeChoices.size) { index ->
                                    ChoiceChip(
                                        label = vm.sizeChoices[index].label,
                                        selected = index == vm.sizeChoiceIndex,
                                        onClick = { vm.sizeChoiceIndex = index },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- archive entry
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth(),
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
                    EmptyHint("这里还是空的。第一次用，先点上面的「生成图片」。")
                }
            } else {
                items(vm.activeItems, key = { it.name }) { item ->
                    GalleryTile(item = item, file = vm.fileOf(item), onOpen = { vm.viewing = item })
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
        val text = vm.previewInstruction()
        AlertDialog(
            onDismissRequest = { showInstructionSheet = false },
            confirmButton = {
                TextButton(onClick = { showInstructionSheet = false }) { Text("知道了") }
            },
            title = { Text("会发送这段指令") },
            text = {
                Text(text, style = MaterialTheme.typography.bodyMedium)
            },
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
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
            Column(Modifier.width(96.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
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
                            .size(26.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .clickable { onRemove(slot.uri) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("×", color = Color.White, style = MaterialTheme.typography.labelLarge)
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
                    .width(96.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .clickable { onAdd() },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("＋", style = MaterialTheme.typography.headlineSmall)
                Text("添加照片", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

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
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("要做什么", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            presets.forEach { preset ->
                val selected = preset.id == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        )
                        .clickable { onPick(preset.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            preset.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        )
                        Text(
                            preset.summary,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selected) Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
            TextButton(onClick = { onPick(null) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("不用预设，直接写要求")
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
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(12.dp))
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
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("归档箱（${vm.archivedCount}）") },
                navigationIcon = {
                    TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
                },
            )
        },
    ) { padding ->
        if (vm.archivedItems.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
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
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
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
                            ) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
}

/** Exposed so a future settings screen can reset to the shipped defaults. */
fun defaultDensity(): Density = Density.NORMAL
