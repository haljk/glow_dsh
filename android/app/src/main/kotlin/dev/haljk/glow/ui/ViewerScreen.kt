package dev.haljk.glow.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.haljk.glow.data.GalleryItem
import dev.haljk.glow.ui.theme.glow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

private enum class CompareMode(val label: String) {
    /** One full-size image at a time, flipped by swipe or tap. Default on a phone. */
    SWITCH("切换"),
    SIDE("并排"),
    WIPE("滑动"),
}

/** Modes offered when a reference image exists; side-by-side is opt-in on a phone. */
private val COMPARE_MODES = listOf(CompareMode.SWITCH, CompareMode.SIDE, CompareMode.WIPE)

/**
 * Full-screen viewer.
 *
 * Deliberately not a Scaffold: the chrome here is a fixed dark surface in both
 * themes, and the picture should get every pixel between the two bars. The
 * previous version used a light TopAppBar with white text on it, which made the
 * title and the only exit control literally invisible in light mode.
 */
@Composable
fun ViewerScreen(vm: GlowViewModel, item: GalleryItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resultFile = vm.fileOf(item)
    val refFile = vm.refFileOf(item, 0)
    val chrome = MaterialTheme.glow.viewerSurface
    val onChrome = MaterialTheme.glow.viewerContent
    val danger = MaterialTheme.glow.viewerDanger

    var mode by remember { mutableStateOf(if (refFile != null) CompareMode.SWITCH else CompareMode.SWITCH) }
    var fraction by remember { mutableFloatStateOf(0.5f) }
    var showReference by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var hintVisible by remember { mutableStateOf(true) }

    LaunchedEffect(mode, showReference) {
        hintVisible = true
        delay(3000)
        hintVisible = false
    }

    val requestWrite = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scope.launch { export(context, resultFile, vm::post) }
        else vm.post("没有存储权限，无法存到相册")
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(chrome)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // ---------------------------------------------------------------- chrome
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = onChrome,
                )
            }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    // Falling back to the pixel size put the same string on both
                    // lines; when the user typed nothing, the date is more useful.
                    item.note.ifBlank { relativeTime(item.createdAt) },
                    style = MaterialTheme.typography.titleMedium,
                    color = onChrome,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${item.displaySize} · ${item.elapsedLabel()}",
                    style = MaterialTheme.typography.labelMedium,
                    color = onChrome.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
            // Only the switch mode has a "which one am I looking at" question; in
            // side-by-side and wipe both panes are on screen at once, so the pill
            // did nothing there.
            if (refFile != null && mode == CompareMode.SWITCH) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(onChrome.copy(alpha = 0.12f))
                        .padding(2.dp),
                ) {
                    SwitchPill("原图", showReference, chrome, onChrome) { showReference = true }
                    SwitchPill("结果", !showReference, chrome, onChrome) { showReference = false }
                }
            }
        }

        // ------------------------------------------------------------------ image
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (mode) {
                CompareMode.SWITCH -> SwitchCompare(
                    referenceFile = refFile,
                    resultFile = resultFile,
                    showReference = showReference,
                    onToggle = { showReference = !showReference },
                )

                CompareMode.SIDE -> Row(
                    Modifier.fillMaxSize(),
                    // Two nearly identical pictures butted together are unreadable:
                    // the seam is what tells you there are two of them.
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    LabeledPane("原图", refFile, Modifier.weight(1f)) {
                        showReference = true
                        mode = CompareMode.SWITCH
                    }
                    LabeledPane("结果", resultFile, Modifier.weight(1f)) {
                        showReference = false
                        mode = CompareMode.SWITCH
                    }
                }

                CompareMode.WIPE -> WipeCompare(
                    referenceFile = refFile,
                    resultFile = resultFile,
                    fraction = fraction,
                    onFractionChange = { fraction = it },
                )
            }

            val hintText = when {
                refFile != null && mode == CompareMode.SWITCH -> "左右滑动切换原图与结果"
                refFile != null && mode == CompareMode.WIPE -> "拖动分界线对比"
                refFile == null -> "双指缩放 · 双击放大"
                else -> ""
            }
            val hintAlpha by animateFloatAsState(
                targetValue = if (hintVisible) 1f else 0f,
                animationSpec = tween(400),
                label = "hint",
            )
            if (hintText.isNotEmpty() && hintAlpha > 0.01f) {
                Text(
                    hintText,
                    color = onChrome.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .graphicsLayer { alpha = hintAlpha }
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.glow.scrim.copy(alpha = 0.45f))
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }

        // ------------------------------------------------------------- bottom bar
        if (refFile != null) {
            ModeSegments(current = mode, chrome = chrome, onChrome = onChrome) { mode = it }
        }
        HorizontalDivider(
            thickness = 1.dp,
            color = onChrome.copy(alpha = 0.10f),
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ViewAction("存到相册", onChrome) {
                val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    ) != PackageManager.PERMISSION_GRANTED
                if (needsPermission) {
                    requestWrite.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    scope.launch { export(context, resultFile, vm::post) }
                }
            }
            ViewAction(if (item.archived) "移出归档" else "归档", onChrome) {
                vm.archive(item, archived = !item.archived)
                onClose()
            }
            ViewAction("删除", danger) { confirmDelete = true }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这张？") },
            text = { Text("图片与它的参考图副本会一起删掉，不能恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(item)
                    onClose()
                }) { Text("删除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SwitchPill(
    label: String,
    selected: Boolean,
    chrome: Color,
    onChrome: Color,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = if (selected) chrome else onChrome.copy(alpha = 0.75f),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) onChrome else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

/** Reading "how" is navigation, not a task: it is styled as a selector, not buttons. */
@Composable
private fun ModeSegments(
    current: CompareMode,
    chrome: Color,
    onChrome: Color,
    onPick: (CompareMode) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(onChrome.copy(alpha = 0.08f))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        COMPARE_MODES.forEach { entry ->
            val selected = entry == current
            Text(
                entry.label,
                color = if (selected) chrome else onChrome.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) onChrome else Color.Transparent)
                    .clickable { onPick(entry) }
                    .padding(vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun RowScope.ViewAction(label: String, color: Color, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
        Text(label, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}


@Composable
private fun LabeledPane(
    label: String,
    file: File?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val bitmap = rememberFileBitmap(file, targetPx = 1400)
    Box(
        modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = label,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
        }
        Text(
            label,
            color = MaterialTheme.glow.onScrim.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.glow.scrim.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * One full-size image at a time, flipped left/right.
 *
 * A split view leaves each image under 200dp on a 411dp screen, which is useless
 * for judging whether a retouch kept the face. Pinch-zoom only applies when there
 * is nothing to switch to, so the two gestures never fight over the same drag.
 */
@Composable
private fun SwitchCompare(
    referenceFile: File?,
    resultFile: File?,
    showReference: Boolean,
    onToggle: () -> Unit,
) {
    if (referenceFile == null) {
        ZoomableImage(resultFile)
        return
    }
    val bitmap = rememberFileBitmap(if (showReference) referenceFile else resultFile, targetPx = 2048)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = { travelled = 0f },
                    onDragEnd = { if (abs(travelled) > 100f) onToggle() },
                    onHorizontalDrag = { _, delta -> travelled += delta },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = if (showReference) "原图" else "结果",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/**
 * Wipe comparison.
 *
 * The reference is drawn on top of the result and clipped to the left of the
 * divider with `clipRect`, so both images keep the same scale and position —
 * without that, each side would re-fit and the comparison would be meaningless.
 *
 * Width comes from `onSizeChanged` rather than `BoxWithConstraints`: wrapping
 * content in a subcomposition stopped touch handling from working elsewhere in
 * this app, and the drag gesture lives right here.
 */
@Composable
private fun WipeCompare(
    referenceFile: File?,
    resultFile: File?,
    fraction: Float,
    onFractionChange: (Float) -> Unit,
) {
    val reference = rememberFileBitmap(referenceFile, targetPx = 1800)
    val result = rememberFileBitmap(resultFile, targetPx = 1800)
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width }
            // Tap to place the divider, drag to sweep it. The tap path matters on a
            // phone: a 2dp handle is not a target, and the whole area is.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        val change = down.changes.firstOrNull() ?: continue
                        if (
                            (down.type == PointerEventType.Press || down.type == PointerEventType.Move) &&
                            widthPx > 0
                        ) {
                            onFractionChange((change.position.x / widthPx).coerceIn(0.02f, 0.98f))
                        }
                    }
                }
            },
    ) {
        DrawPane(result, "结果", Modifier.fillMaxSize(), Alignment.BottomEnd)
        DrawPane(
            bitmap = reference,
            label = "原图",
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(right = size.width * fraction) {
                        this@drawWithContent.drawContent()
                    }
                },
            labelSpot = Alignment.BottomStart,
        )
        if (widthPx > 0) {
            Box(
                Modifier
                    .offset(x = with(density) { (widthPx * fraction).toDp() } - 1.dp)
                    .width(2.dp)
                    .fillMaxSize()
                    .background(MaterialTheme.glow.onScrim.copy(alpha = 0.9f)),
            )
        }
    }
}

@Composable
private fun DrawPane(
    bitmap: ImageBitmap?,
    label: String?,
    modifier: Modifier,
    labelSpot: Alignment = Alignment.TopEnd,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
        if (label != null) {
            Text(
                label,
                color = MaterialTheme.glow.onScrim.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(labelSpot)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.glow.scrim.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun ZoomableImage(file: File?) {
    val bitmap = rememberFileBitmap(file, targetPx = 2048)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale <= 1f) Offset.Zero else offset + panChange
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "生成结果",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .transformable(state)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.05f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.2f
                                }
                            },
                        )
                    },
                contentScale = ContentScale.Fit,
            )
        }
    }
}

private suspend fun export(context: Context, file: File?, post: (String) -> Unit) {
    if (file == null || !file.isFile) {
        post("找不到这张图")
        return
    }
    val ok = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Glow")
                }
                val uri = context.contentResolver
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@runCatching false
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: return@runCatching false
                true
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Glow",
                )
                dir.mkdirs()
                file.copyTo(File(dir, file.name), overwrite = true)
                true
            }
        }.getOrDefault(false)
    }
    post(if (ok) "已存到相册的 Glow 文件夹" else "保存失败")
}
