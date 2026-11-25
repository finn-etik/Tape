package app.tape.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage

val Ink: Color @Composable get() = MaterialTheme.colorScheme.onBackground
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val Paper: Color @Composable get() = MaterialTheme.colorScheme.background
val Line: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
val Accent: Color @Composable get() = MaterialTheme.colorScheme.primary

/** Click without the ripple: TAPE prefers quiet surfaces. */
@Composable
fun Modifier.quietClick(onClick: () -> Unit): Modifier =
    clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)

/** Cover art with a calm typographic placeholder underneath while (or if) the image is missing. */
@Composable
fun Artwork(
    model: Any?,
    label: String,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.trim().firstOrNull()?.uppercase() ?: "·",
            color = Muted,
            style = MaterialTheme.typography.titleMedium,
        )
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (primary) Ink else Color.Transparent
    val fg = if (primary) Paper else Ink
    Row(
        modifier
            .clip(CircleShape)
            .then(if (primary) Modifier.background(bg.copy(alpha = if (enabled) 1f else 0.3f)) else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    action: String? = null,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
        if (action != null) {
            Spacer(Modifier.height(28.dp))
            PillButton(action, onClick = onAction)
        }
        if (secondary != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                secondary,
                style = MaterialTheme.typography.labelLarge,
                color = Muted,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onSecondary).padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/** Small text switcher, e.g. Albums · Songs. */
@Composable
fun Segments(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) Paper else Muted,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) Ink else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
fun BackBar(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Ink) }
        Text(title, style = MaterialTheme.typography.titleSmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Muted,
        modifier = modifier.padding(horizontal = 24.dp, vertical = 12.dp),
    )
}

/** A thin, quiet progress line. */
@Composable
fun Hairline(fraction: Float, modifier: Modifier = Modifier, color: Color = Accent) {
    Box(modifier.height(2.dp).clip(CircleShape).background(Line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(2.dp).background(color))
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

val TabTitleSize = 26.sp
