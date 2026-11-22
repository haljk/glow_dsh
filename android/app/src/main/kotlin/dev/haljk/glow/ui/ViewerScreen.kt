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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.haljk.glow.data.GalleryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

private enum class CompareMode(val label: String) {
    /** One full-size image at a time, flipped by swipe or tap. Default on a phone. */
    SWITCH("切换"),
    SIDE("并排"),
    WIPE("滑动"),
    SINGLE("单看"),
}

/** Modes offered when a reference image exists; side-by-side is opt-in on a phone. */
private val COMPARE_MODES = listOf(CompareMode.SWITCH, CompareMode.SIDE, CompareMode.WIPE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: GlowViewModel, item: GalleryItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resultFile = vm.fileOf(item)
    val refFile = vm.refFileOf(item, 0)
    // On a 411dp-wide phone, side-by-side gives each image under 200dp — too small
    // to judge detail, so switching is the default and the split views are optional.
    var mode by remember { mutableStateOf(if (refFile != null) CompareMode.SWITCH else CompareMode.SINGLE) }
    var fraction by remember { mutableFloatStateOf(0.5f) }
    var showReference by remember { mutableStateOf(false) }

    val requestWrite = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scope.launch { export(context, resultFile, vm::post) }
        else vm.post("没有存储权限，无法存到相册")
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            item.note.ifBlank { item.displaySize },
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                item.displaySize,
                                item.elapsedLabel().ifBlank { null },
                                item.seed.takeIf { it != 0L }?.let { "种子 $it" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("返回", color = Color.White)
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = Color(0xFF141317)) {
                Column(
                    Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    if (refFile != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            COMPARE_MODES.forEach { entry ->
                                val selected = entry == mode
                                TextButton(
                                    onClick = { mode = entry },
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 44.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent,
                                        ),
                                ) {
                                    Text(
                                        entry.label,
                                        color = if (selected) Color.White else Color.White.copy(alpha = 0.65f),
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ViewerAction("存到相册") {
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
                        ViewerAction(if (item.archived) "移出归档" else "归档") {
                            vm.archive(item, archived = !item.archived)
                            onClose()
                        }
                        ViewerAction("删除") {
                            vm.delete(item)
                            onClose()
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
        ) {
            when (mode) {
                CompareMode.SINGLE, CompareMode.SWITCH -> SwitchCompare(
                    referenceFile = refFile,
                    resultFile = resultFile,
                    showReference = showReference,
                    onToggle = { showReference = !showReference },
                )

                CompareMode.SIDE -> Row(Modifier.fillMaxSize()) {
                    LabeledPane("原图", refFile, Modifier.weight(1f))
                    LabeledPane("结果", resultFile, Modifier.weight(1f))
                }

                CompareMode.WIPE -> WipeCompare(
                    referenceFile = refFile,
                    resultFile = resultFile,
                    fraction = fraction,
                    onFractionChange = { fraction = it },
                )
            }
        }
    }
}

@Composable
private fun RowScope.ViewerAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
        Text(label, color = Color.White, maxLines = 1)
    }
}

@Composable
private fun LabeledPane(label: String, file: File?, modifier: Modifier = Modifier) {
    val bitmap = rememberFileBitmap(file, targetPx = 1400)
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * Wipe comparison.
 *
 * The reference is drawn on top of the result and clipped to the left of the
 * divider with `clipRect`, so both images keep the same scale and position —
 * without that, each side would re-fit and the comparison would be meaningless.
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
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    onFractionChange((change.position.x / size.width.toFloat()).coerceIn(0.02f, 0.98f))
                }
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        DrawPane(result, "结果", Modifier.fillMaxSize())
        DrawPane(
            bitmap = reference,
            label = null,
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    clipRect(right = size.width * fraction) {
                        this@drawWithContent.drawContent()
                    }
                },
        )
        Box(
            Modifier
                .offset(x = (maxWidth * fraction) - 1.dp)
                .width(2.dp)
                .fillMaxSize()
                .background(Color.White.copy(alpha = 0.9f)),
        )
        Text(
            "拖动分界线对比",
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        // Keep the compiler honest about the unused derived value.
        if (widthPx < 0f) Text("", color = Color.Transparent)
    }
}

@Composable
private fun DrawPane(bitmap: ImageBitmap?, label: String?, modifier: Modifier) {
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
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * One full-size image at a time, flipped left/right.
 *
 * This is the default comparison on a phone: a split view would leave each image
 * under 200dp wide, which is useless for judging whether a retouch kept the face.
 * Pinch-zoom is only offered when there is no reference to switch to, so the two
 * gestures never fight over the same drag.
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
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SwitchPill("原图", showReference) { if (!showReference) onToggle() }
            SwitchPill("结果", !showReference) { if (showReference) onToggle() }
        }
        Text(
            "左右滑动切换原图与结果",
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun SwitchPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Color.Black else Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
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
        Text(
            "双指缩放 · 双击放大",
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
        )
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
