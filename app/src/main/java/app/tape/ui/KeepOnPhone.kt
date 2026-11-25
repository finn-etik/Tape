package app.tape.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tape.graph
import app.tape.library.Track
import app.tape.playback.Kept
import java.util.concurrent.atomic.AtomicReference

/** Live "kept on phone" status for a set of tracks. */
@Composable
fun keptStatus(tracks: List<Track>): Kept {
    val downloads = LocalContext.current.graph.downloads
    val state by downloads.state.collectAsStateWithLifecycle()
    return remember(tracks, state) { downloads.status(tracks) }
}

/** One quiet line: "On this phone", "Saving to phone · 40%", … Nothing when not kept. */
@Composable
fun KeptLine(kept: Kept, modifier: Modifier = Modifier) {
    val (icon, text) = when (kept) {
        Kept.None -> return
        Kept.All -> Icons.Rounded.DownloadDone to "On this phone"
        Kept.Partly -> Icons.Rounded.DownloadDone to "Partly on this phone"
        is Kept.Saving -> Icons.Rounded.Downloading to "Saving to phone · ${kept.percent}%"
        Kept.Failed -> Icons.Rounded.ErrorOutline to "Couldn't save everything · try again"
    }
    val color = if (kept == Kept.Failed) MaterialTheme.colorScheme.error else Accent
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/**
 * Wraps a "keep on phone" action. On Android 13+ it first asks (once) to show
 * notifications, so download progress can appear in the shade; it downloads either way.
 */
@Composable
fun rememberKeepOnPhone(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    val pending = remember { AtomicReference<(() -> Unit)?>(null) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending.getAndSet(null)?.invoke()
    }
    val askRef by rememberUpdatedState(ask)
    return remember {
        { action: () -> Unit ->
            val needs = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needs) {
                pending.set(action)
                askRef.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                action()
            }
        }
    }
}
