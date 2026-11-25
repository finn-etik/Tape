package app.tape.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.tape.graph
import app.tape.playback.NowPlaying
import kotlinx.coroutines.delay

@Composable
fun MiniPlayer(np: NowPlaying, onOpen: () -> Unit) {
    val pc = LocalContext.current.graph.player
    var fraction by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(np.durationMs, np.index) {
        while (true) {
            fraction = if (np.durationMs > 0) pc.position().toFloat() / np.durationMs else 0f
            delay(500)
        }
    }

    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = onOpen)) {
        Hairline(fraction, Modifier.fillMaxWidth(), color = Ink)
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(np.artwork, np.title, Modifier.size(44.dp), corner = 6.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(np.title, style = MaterialTheme.typography.titleSmall, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = np.error ?: np.subtitle
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (np.error != null) MaterialTheme.colorScheme.error else Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = pc::togglePlay) {
                Icon(if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (np.isPlaying) "Pause" else "Play", tint = Ink)
            }
            if (np.isBook) {
                IconButton(onClick = { pc.skipForward() }) { Icon(Icons.Rounded.Forward30, "Forward 30 seconds", tint = Ink) }
            } else {
                IconButton(onClick = { pc.next() }) { Icon(Icons.Rounded.SkipNext, "Next", tint = Ink) }
            }
        }
    }
}
