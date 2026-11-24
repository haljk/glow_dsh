package dev.haljk.glow.ui

import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.haljk.glow.core.Density
import dev.haljk.glow.data.GalleryItem
import dev.haljk.glow.data.JobRegistry
import dev.haljk.glow.ui.theme.glow
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The one card surface.
 *
 * A hairline, not tonal elevation: at 1–3 dp the tonal mix is about 2% of the
 * primary colour, which measured as a 1.02:1 difference against the page and read
 * as no layer at all.
 */
@Composable
fun GlowCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    // Light mode separates the card from the page with a soft shadow and no outline;
    // on a neutral field an outline reads as a box drawn around the content. Dark mode
    // keeps a hairline because a shadow on near-black is invisible.
    val dark = isSystemInDarkTheme()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = if (dark) {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
        shadowElevation = if (dark) 0.dp else 2.dp,
    ) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun SectionHeader(
    title: String,
    hint: String? = null,
    action: String? = null,
    enabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (action != null && onAction != null) {
            // Plain clickable text: even with zero content padding, TextButton keeps a
            // 58dp minimum width and centres its label inside it, which pushed a
            // three-character link 9dp inside the field's right edge.
            Text(
                action,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.45f),
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { onAction() }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * The three paths.
 *
 * Copy is kept to four characters so it never breaks mid-word, and the row is
 * sized to its tallest child so all three cards share one baseline at any font
 * scale. Selection is colour plus a border only — bolding the selected title made
 * a 14 sp card title heavier than the 17 sp section title above it.
 */
@Composable
fun ScenarioRow(current: Scenario, compact: Boolean = false, onPick: (Scenario) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Scenario.entries.forEach { entry ->
            val selected = entry == current
            val shape = RoundedCornerShape(16.dp)
            // Selection is fill plus a tick, never a border: a 2dp primary outline on a
            // card collided with the focused-input outline, so "selected" and "focused"
            // looked like the same state.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .heightIn(min = if (compact) 74.dp else 92.dp)
                    .clip(shape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface,
                    )
                    .clickable { onPick(entry) }
                    .padding(horizontal = 10.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Text(
                    entry.blurb,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Bottom-anchored primary action.
 *
 * It is genuinely disabled when the form is incomplete, instead of always looking
 * ready and answering with a toast: the biggest shape on the page claiming to be
 * pressable is a promise the app should keep.
 */
@Composable
fun PrimaryAction(
    label: String,
    enabled: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (busy) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.primary,
            contentColor = if (busy) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            // At full ink the disabled label was the heaviest text on the screen and
            // read as a secondary button rather than a refused one.
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}


/**
 * Task state lives in the bottom bar.
 *
 * It used to be a banner at the top of the list, which meant a running job pushed the
 * form down while the primary button sat at the bottom saying nothing. The bar is where
 * the thumb already is, and it is one place for running, done and failed.
 */
@Composable
fun JobProgressBar(
    snapshot: JobRegistry.Snapshot,
    onBackground: () -> Unit,
    onCancel: () -> Unit,
) {
    var now by remember(snapshot.id) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(snapshot.id) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val elapsed = ((now - snapshot.startedAt) / 1000).coerceAtLeast(0).toInt()
    val title = when (snapshot.stage) {
        JobRegistry.Stage.QUEUED ->
            if (snapshot.queuePosition > 0) "排队中 · 第 ${snapshot.queuePosition} 位" else "排队中"
        JobRegistry.Stage.RUNNING ->
            if (snapshot.percent > 0) "正在生成 · ${snapshot.percent}%" else "正在生成"
        else -> "正在处理"
    }
    val timing = if (snapshot.etaSeconds > 0) {
        "约剩 ${mmss(snapshot.etaSeconds)} · 已用 ${mmss(elapsed)}"
    } else {
        "已用 ${mmss(elapsed)}"
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    timing,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onBackground, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("后台继续", color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("取消", color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (snapshot.percent > 0) {
            LinearProgressIndicator(
                progress = { snapshot.percent / 100f },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** Finished job: a card with a way into the result, not a jump. */
@Composable
fun JobDoneBar(onView: (() -> Unit)?, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("已生成", style = MaterialTheme.typography.titleMedium)
            Text(
                "结果已存到「以前做的」",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onView != null) {
            TextButton(onClick = onView, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("查看", color = MaterialTheme.colorScheme.primary)
            }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 44.dp)) {
            Text("关闭", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun mmss(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    return "${safe / 60}:${(safe % 60).toString().padStart(2, '0')}"
}

@Composable
fun GalleryTile(item: GalleryItem, file: java.io.File?, onOpen: () -> Unit) {
    val bitmap = rememberFileBitmap(file, targetPx = 420)
    Column(Modifier.fillMaxWidth().clickable { onOpen() }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (item.ratio > 0.4f) 1f else 0.72f)
                .clip(RoundedCornerShape(12.dp))
                // Same shape and colour whether or not the bitmap has arrived: a
                // "载入中" label next to loaded images reads as a broken file.
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = item.note.ifBlank { "生成结果" },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        if (item.note.isNotBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                item.note,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            relativeTime(item.createdAt),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        )
    }
}

@Composable
fun DensityChips(current: Density, onPick: (Density) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Density.entries.forEach { entry ->
            ChoiceChip(
                label = entry.label.substringBefore("（"),
                selected = entry == current,
                modifier = Modifier.weight(1f),
                onClick = { onPick(entry) },
            )
        }
    }
}

@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    secondLine: String? = null,
    onClick: () -> Unit,
) {
    // Splitting the label on a space inside this component was wrong: it also cut
    // "1 张" into "1" / "张" and collapsed the target to 27dp wide. The extra line
    // is now opt-in per call site, and there is a floor on the width either way.
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .widthIn(min = 52.dp)
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            if (secondLine != null) {
                Text(
                    secondLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
fun FavoritesRow(favorites: List<String>, onUse: (String) -> Unit, onRemove: (String) -> Unit) {
    if (favorites.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(favorites) { text ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 2.dp).heightIn(min = 44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .width(180.dp)
                            .clickable { onUse(text) },
                    )
                    TextButton(onClick = { onRemove(text) }, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("×", fontSize = 17.sp)
                    }
                }
            }
        }
    }
}

/** 43 秒 / 1 分 20 秒 — matches how long these jobs actually take. */
fun elapsedLabel(seconds: Long): String =
    if (seconds < 60) "$seconds 秒" else "${seconds / 60} 分 ${seconds % 60} 秒"

fun relativeTime(timestamp: Long): String {
    val zone = ZoneId.systemDefault()
    val moment = Instant.ofEpochMilli(timestamp).atZone(zone)
    val today = LocalDate.now(zone)
    val clock = String.format(java.util.Locale.US, "%02d:%02d", moment.hour, moment.minute)
    return when (moment.toLocalDate()) {
        today -> "今天 $clock"
        today.minusDays(1) -> "昨天 $clock"
        else -> "${moment.monthValue} 月 ${moment.dayOfMonth} 日"
    }
}

/**
 * Connection state in the app bar.
 *
 * It replaced a full row of text under the bar: the state is a status, not content, and
 * the failing case (a wrong address) is exactly when the user needs a route into
 * settings — so the pill itself is the shortcut.
 */
@Composable
fun ConnectionPill(
    imageOk: Boolean?,
    llmOk: Boolean?,
    probing: Boolean,
    onClick: () -> Unit,
) {
    val trouble = imageOk == false || llmOk == false
    val waiting = probing || (imageOk == null && llmOk == null)
    val dotColor = when {
        trouble -> MaterialTheme.colorScheme.error
        waiting -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.glow.success
    }
    val label = when {
        trouble -> "未连接"
        waiting -> "检查中"
        else -> "已连接"
    }
    val container = if (trouble) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (trouble) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(container)
            .clickable(onClick = onClick)
            .heightIn(min = 32.dp)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}
