package dev.haljk.glow.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
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
            TextButton(onClick = onAction, enabled = enabled, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(action, style = MaterialTheme.typography.labelLarge)
            }
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
            val shape = RoundedCornerShape(14.dp)
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
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = shape,
                    )
                    .clickable { onPick(entry) }
                    .padding(horizontal = 10.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
fun StatusDot(ok: Boolean?, label: String) {
    val color = when (ok) {
        true -> MaterialTheme.glow.success
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Job banner.
 *
 * It carries the only sense of a process this app has: still running, how long,
 * safe to walk away, and a way out. The clock needs its own ticker because a
 * composition does not re-run on wall time.
 */
@Composable
fun JobBanner(snapshot: JobRegistry.Snapshot, onDismiss: () -> Unit, onGiveUp: () -> Unit) {
    var now by remember(snapshot.id, snapshot.status) { mutableStateOf(System.currentTimeMillis()) }
    if (snapshot.status == JobRegistry.Status.RUNNING) {
        LaunchedEffect(snapshot.id) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
    }
    val elapsedSeconds = ((now - snapshot.startedAt) / 1000).coerceAtLeast(0)
    val running = snapshot.status == JobRegistry.Status.RUNNING

    val container = when (snapshot.status) {
        JobRegistry.Status.FAILED -> MaterialTheme.colorScheme.errorContainer
        JobRegistry.Status.DONE -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val accent = when (snapshot.status) {
        JobRegistry.Status.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(shape = RoundedCornerShape(14.dp), color = container, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            Row(
                Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when (snapshot.status) {
                            JobRegistry.Status.RUNNING -> "正在生成"
                            JobRegistry.Status.DONE -> "完成，已保存 ${snapshot.produced} 张"
                            JobRegistry.Status.FAILED -> "这次没成功"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (running) {
                        Text(
                            "已等 " + elapsedLabel(elapsedSeconds),
                            // Tabular figures: without them the number reflows every
                            // second and the whole banner twitches.
                            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Normal,
                        )
                        Text(
                            "可以切走做别的，不会中断",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (snapshot.message.isNotBlank()) {
                        Text(
                            snapshot.message,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (running) {
                    TextButton(onClick = onGiveUp, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("不再等", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 44.dp)) {
                        Text("知道了", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
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
    onClick: () -> Unit,
) {
    // Two lines when the label carries a ratio: "竖版人像 / 3:4". At 1.3x font a
    // single line had to truncate to "竖版人像 …", which dropped the ratio — the
    // one piece of information the chip exists to carry.
    val parts = label.split(' ')
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape,
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                parts.first(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            if (parts.size > 1) {
                Text(
                    parts.drop(1).joinToString(" "),
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
