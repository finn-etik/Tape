package app.tape.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.tape.graph
import app.tape.playback.NowPlaying
import app.tape.playback.PlayerConnection
import app.tape.playback.SleepTimer
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val graph = LocalContext.current.graph
    val pc = graph.player
    val np by pc.state.collectAsStateWithLifecycle()
    var position by remember { mutableLongStateOf(pc.position()) }
    var showQueue by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            position = pc.position()
            delay(250)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Paper)
            .pointerInput(Unit) { detectTapGestures { } } // don't let taps fall through to the list below
            .systemBarsPadding()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose, modifier = Modifier.padding(start = 0.dp)) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Close", tint = Ink)
            }
            Text(
                (if (np.isBook) np.collection else np.collection.ifEmpty { "Now playing" }).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            if (!np.isBook) {
                IconButton(onClick = { pc.currentTrack()?.let { PlaylistPicker.open(listOf(it)) } }) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", tint = Ink)
                }
            }
            IconButton(onClick = { showQueue = true }) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, if (np.isBook) "Chapters" else "Queue", tint = Ink)
            }
        }

        Spacer(Modifier.weight(0.6f))
        Artwork(
            model = np.artwork,
            label = np.title,
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f),
            corner = 14.dp,
        )
        Spacer(Modifier.weight(0.5f))

        Column(Modifier.fillMaxWidth()) {
            Text(np.title, style = MaterialTheme.typography.headlineSmall, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val sub = if (np.isBook && np.count > 1) "Chapter ${np.index + 1} of ${np.count}" else np.subtitle
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            np.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }

        Spacer(Modifier.height(20.dp))
        Scrubber(
            positionMs = position,
            durationMs = np.durationMs,
            onSeek = { position = it; pc.seekTo(it) },
        )

        Spacer(Modifier.height(16.dp))
        Transport(np, onToggle = pc::togglePlay, pc = pc)

        Spacer(Modifier.weight(0.5f))
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (np.isBook) SpeedChip(np.speed, pc) else ModeToggle(
                icon = Icons.Rounded.Shuffle, on = np.shuffle, label = "Shuffle", onClick = pc::toggleShuffle,
            )
            SleepChip(graph.sleepTimer, isBook = np.isBook)
            if (!np.isBook) {
                ModeToggle(
                    icon = if (np.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    on = np.repeatMode != Player.REPEAT_MODE_OFF,
                    label = "Repeat",
                    onClick = pc::cycleRepeat,
                )
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }
    }

    if (showQueue) {
        val titles = remember(np.count, np.index) { pc.queueTitles() }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (np.index - 2).coerceAtLeast(0))
        ModalBottomSheet(onDismissRequest = { showQueue = false }, containerColor = MaterialTheme.colorScheme.surface) {
            Text(
                if (np.isBook) "Chapters" else "Up next",
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                itemsIndexed(titles) { i, title ->
                    val current = i == np.index
                    Row(
                        Modifier.fillMaxWidth().clickable { pc.jumpTo(i); showQueue = false }.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}", style = MaterialTheme.typography.bodySmall, color = if (current) Accent else Muted, modifier = Modifier.width(36.dp))
                        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (current) Accent else Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun Transport(np: NowPlaying, onToggle: () -> Unit, pc: PlayerConnection) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (np.isBook) {
            SmallControl(Icons.Rounded.SkipPrevious, "Previous chapter", 22.dp) { pc.previous() }
            SmallControl(Icons.Rounded.Replay10, "Back 10 seconds", 32.dp) { pc.skipBack() }
            PlayButton(np, onToggle)
            SmallControl(Icons.Rounded.Forward30, "Forward 30 seconds", 32.dp) { pc.skipForward() }
            SmallControl(Icons.Rounded.SkipNext, "Next chapter", 22.dp) { pc.next() }
        } else {
            Spacer(Modifier.size(48.dp))
            SmallControl(Icons.Rounded.SkipPrevious, "Previous", 34.dp) { pc.previous() }
            PlayButton(np, onToggle)
            SmallControl(Icons.Rounded.SkipNext, "Next", 34.dp) { pc.next() }
            Spacer(Modifier.size(48.dp))
        }
    }
}

@Composable
private fun PlayButton(np: NowPlaying, onToggle: () -> Unit) {
    Box(
        Modifier.size(76.dp).clip(CircleShape).background(Ink).clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (np.isBuffering && !np.isPlaying) {
            CircularProgressIndicator(Modifier.size(26.dp), color = Paper, strokeWidth = 2.dp)
        } else {
            Icon(
                if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (np.isPlaying) "Pause" else "Play",
                tint = Paper,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun SmallControl(icon: ImageVector, description: String, size: Dp, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Icon(icon, description, tint = Ink, modifier = Modifier.size(size))
    }
}

@Composable
private fun ModeToggle(icon: ImageVector, on: Boolean, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, label, tint = if (on) Accent else Muted)
    }
}

@Composable
private fun SpeedChip(speed: Float, pc: PlayerConnection) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(text = formatSpeed(speed), active = speed != 1f) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PlayerConnection.SPEEDS.forEach { s ->
                DropdownMenuItem(
                    text = { Text(formatSpeed(s), color = if (s == speed) Accent else Ink) },
                    onClick = { open = false; pc.setSpeed(s) },
                )
            }
        }
    }
}

@Composable
private fun SleepChip(timer: SleepTimer, isBook: Boolean) {
    val mode by timer.mode.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(mode) {
        while (mode is SleepTimer.Mode.At) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val label = when (val m = mode) {
        SleepTimer.Mode.Off -> "Sleep"
        SleepTimer.Mode.EndOfChapter -> if (isBook) "End of chapter" else "End of track"
        is SleepTimer.Mode.At -> {
            val left = ((m.endsAt - now) / 1000).coerceAtLeast(0)
            "%d:%02d".format(left / 60, left % 60)
        }
    }
    Box {
        Chip(text = label, icon = Icons.Rounded.Bedtime, active = mode != SleepTimer.Mode.Off) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(10, 20, 30, 45, 60).forEach { minutes ->
                DropdownMenuItem(text = { Text("$minutes minutes") }, onClick = { open = false; timer.start(minutes) })
            }
            DropdownMenuItem(
                text = { Text(if (isBook) "End of chapter" else "End of track") },
                onClick = { open = false; timer.endOfChapter() },
            )
            if (mode != SleepTimer.Mode.Off) {
                DropdownMenuItem(text = { Text("Turn off", color = Muted) }, onClick = { open = false; timer.cancel() })
            }
        }
    }
}

@Composable
private fun Chip(text: String, icon: ImageVector? = null, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (active) Accent else Muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (active) Accent else Muted)
    }
}

/** 1.0×, 1.25×… Float.toString is locale-independent, so no "1,0×" on Swedish phones. */
private fun formatSpeed(s: Float): String = "$s×"

/** A hairline scrubber: a thin track, a small dot that grows while you drag. */
@Composable
fun Scrubber(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val fraction = drag ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val ink = Ink
    val line = Line
    val dot by animateDpAsState(if (drag != null) 8.dp else 4.5.dp, label = "dot")

    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(durationMs) {
                    detectTapGestures { o ->
                        if (durationMs > 0) onSeek((o.x / size.width * durationMs).toLong().coerceIn(0, durationMs))
                    }
                }
                .pointerInput(durationMs) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            drag?.let { if (durationMs > 0) onSeek((it * durationMs).toLong()) }
                            drag = null
                        },
                        onDragCancel = { drag = null },
                    ) { change, _ ->
                        change.consume()
                        drag = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
        ) {
            val y = size.height / 2
            val stroke = 2.dp.toPx()
            drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(ink, Offset(0f, y), Offset(size.width * fraction, y), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(ink, radius = dot.toPx(), center = Offset(size.width * fraction, y))
        }
        val shown = if (drag != null) (fraction * durationMs).toLong() else positionMs
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(shown), style = MaterialTheme.typography.bodySmall, color = Muted)
            Spacer(Modifier.weight(1f))
            Text(
                if (durationMs > 0) "−" + formatTime(durationMs - shown) else "",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}
