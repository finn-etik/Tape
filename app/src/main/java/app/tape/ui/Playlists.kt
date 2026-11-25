package app.tape.ui

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.runtime.toMutableStateList
import androidx.media3.exoplayer.offline.Download
import app.tape.playback.Kept
import app.tape.playback.Media
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tape.graph
import app.tape.library.Playlist
import app.tape.library.Track

/** Anywhere in the app can ask for "add these to a playlist"; [TapeRoot] shows the sheet. */
object PlaylistPicker {
    var pending by mutableStateOf<List<Track>?>(null)
    fun open(tracks: List<Track>) {
        if (tracks.isNotEmpty()) pending = tracks
    }
}

class TrackAction(val label: String, val run: () -> Unit)

/**
 * A song in any list. Long-press opens its menu: Play next, Add to queue (when music is
 * playing), Add to playlist, then any screen-specific [actions].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    showArt: Boolean,
    number: Int? = null,
    actions: List<TrackAction> = emptyList(),
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val graph = LocalContext.current.graph
    val downloads by graph.downloads.state.collectAsStateWithLifecycle()
    val kept = track.isCloud && downloads[Media.fileId(track.mediaId)]?.state == Download.STATE_COMPLETED
    var menu by remember { mutableStateOf<List<TrackAction>?>(null) }
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Paper)
                .combinedClickable(onClick = onClick, onLongClick = {
                    menu = buildList {
                        if (graph.player.canQueue) {
                            add(TrackAction("Play next") { graph.player.playNext(listOf(track)) })
                            add(TrackAction("Add to queue") { graph.player.addToQueue(listOf(track)) })
                        }
                        add(addToPlaylistAction(track))
                        addAll(actions)
                    }
                })
                .padding(start = 24.dp, end = if (trailing != null) 12.dp else 24.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showArt) {
                Artwork(track.artwork, track.album ?: track.name, Modifier.size(44.dp), corner = 6.dp)
                Spacer(Modifier.width(14.dp))
            } else if (number != null) {
                Text("$number", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.width(32.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(track.name, style = MaterialTheme.typography.bodyLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = if (showArt) listOfNotNull(track.artist, track.album.takeIf { track.artist == null }).joinToString(" · ") else ""
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            if (track.isCloud) {
                Icon(
                    if (kept) Icons.Rounded.DownloadDone else Icons.Rounded.Cloud,
                    if (kept) "On this phone" else "In Drime",
                    tint = if (kept) Accent else Muted.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            if (track.durationMs > 0) Text(formatTime(track.durationMs), style = MaterialTheme.typography.bodySmall, color = Muted)
            trailing?.invoke()
        }
        DropdownMenu(expanded = menu != null, onDismissRequest = { menu = null }) {
            menu?.forEach { a ->
                DropdownMenuItem(text = { Text(a.label) }, onClick = { menu = null; a.run() })
            }
        }
    }
}

fun addToPlaylistAction(track: Track) = TrackAction("Add to playlist…") { PlaylistPicker.open(listOf(track)) }

/** "Add to playlist" sheet: pick a playlist, or name a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(tracks: List<Track>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val playlists by graph.playlists.all.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(playlists.isEmpty()) }
    var name by remember { mutableStateOf("") }
    val what = if (tracks.size == 1) "“${tracks.first().name}”" else "${tracks.size} songs"

    fun done(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Text(
            "Add $what to…",
            style = MaterialTheme.typography.titleMedium,
            color = Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (naming) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            fun create() {
                val p = name.trim().ifEmpty { "New playlist" }
                graph.playlists.create(p, tracks)
                done("Added to $p")
            }
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Playlist name") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { create() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Spacer(Modifier.height(16.dp))
                PillButton("Create playlist") { create() }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp)) {
                item {
                    Row(
                        Modifier.fillMaxWidth().clickable { naming = true }.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Ink, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("New playlist", style = MaterialTheme.typography.bodyLarge, color = Ink)
                    }
                }
                items(playlists, key = { it.id }) { p ->
                    PlaylistRow(p) {
                        graph.playlists.add(p.id, tracks)
                        done("Added to ${p.name}")
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistRow(p: Playlist, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(p.art, p.name, Modifier.size(52.dp), corner = 6.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (p.tracks.size == 1) "1 song" else "${p.tracks.size} songs", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
fun PlaylistsList(onOpen: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val playlists by graph.playlists.all.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(
                Modifier.fillMaxWidth().clickable { naming = true }.padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, null, tint = Ink, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text("New playlist", style = MaterialTheme.typography.bodyLarge, color = Ink)
            }
        }
        items(playlists, key = { it.id }) { p -> PlaylistRow(p) { onOpen(p.id) } }
        if (playlists.isEmpty()) {
            item {
                Text(
                    "Make one here, or long-press any song and choose “Add to playlist”. " +
                        "Songs from this phone and from Drime can share a playlist.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }

    if (naming) {
        NameDialog(title = "New playlist", initial = "", confirm = "Create", onDismiss = { naming = false }) { name ->
            naming = false
            onOpen(graph.playlists.create(name))
        }
    }
}

@Composable
fun PlaylistScreen(id: String, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val playlists by graph.playlists.all.collectAsStateWithLifecycle()
    val p = playlists.firstOrNull { it.id == id }
    if (p == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val keepOnPhone = rememberKeepOnPhone()
    val kept = keptStatus(p.tracks)
    val hasCloud = p.tracks.any { it.isCloud }

    // A local copy that follows the finger while dragging; the store is updated on drop.
    // Rows get their own ids because the same song may appear twice.
    val rows = remember(p.tracks) { p.tracks.mapIndexed { i, t -> RowItem(i.toLong(), t) }.toMutableStateList() }
    val listState = rememberLazyListState()
    val headerRows = if (p.tracks.isEmpty()) 3 else 4
    val reorder = rememberReorderState(
        listState = listState,
        headerCount = headerRows,
        itemCount = { rows.size },
        onMove = { from, to -> rows.add(to, rows.removeAt(from)) },
        onDrop = { from, to -> graph.playlists.move(p.id, from, to) },
    )

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            BackBar("Playlists", onBack) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = Ink) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (hasCloud) {
                            if (kept == Kept.None || kept == Kept.Partly || kept == Kept.Failed) {
                                DropdownMenuItem(text = { Text("Keep on phone") }, onClick = { menu = false; keepOnPhone { graph.downloads.keepTracks(p.tracks) } })
                            }
                            if (kept != Kept.None) {
                                DropdownMenuItem(text = { Text("Remove from phone") }, onClick = { menu = false; graph.downloads.removeTracks(p.tracks) })
                            }
                        }
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                        DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menu = false; deleting = true })
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.Bottom) {
                Artwork(p.art, p.name, Modifier.size(132.dp), corner = 10.dp)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.headlineSmall, color = Ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(if (p.tracks.size == 1) "1 song" else "${p.tracks.size} songs", style = MaterialTheme.typography.bodySmall, color = Muted)
                    KeptLine(kept, Modifier.padding(top = 4.dp))
                }
            }
        }
        item { Spacer(Modifier.height(0.dp)) }
        if (p.tracks.isEmpty()) {
            item {
                Text(
                    "Empty for now. Long-press any song in Music or Drime, or use the + in the player, to add it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        } else {
            item {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Play", icon = Icons.Rounded.PlayArrow) { graph.player.playTracks(p.tracks) }
                    PillButton("Shuffle", icon = Icons.Rounded.Shuffle, primary = false) { graph.player.playTracks(p.tracks, shuffle = true) }
                }
                Spacer(Modifier.height(8.dp))
            }
            itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                with(reorder) {
                    TrackRow(
                        track = r.track,
                        showArt = true,
                        actions = listOf(TrackAction("Remove from playlist") { graph.playlists.removeAt(p.id, i) }),
                        modifier = Modifier.draggedRow(i),
                        trailing = { DragHandle(reorder, i) },
                    ) { graph.player.playTracks(rows.map { it.track }, i) }
                }
            }
        }
    }

    if (renaming) {
        NameDialog(title = "Rename playlist", initial = p.name, confirm = "Rename", onDismiss = { renaming = false }) {
            graph.playlists.rename(p.id, it)
            renaming = false
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete “${p.name}”?") },
            text = { Text("The songs themselves stay where they are.") },
            confirmButton = {
                TextButton(onClick = { deleting = false; graph.playlists.delete(p.id); onBack() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/** A row in a reorderable list, with an id that survives moves. */
class RowItem<T>(val key: Long, val track: T)

@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Playlist name") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(name) }),
                modifier = Modifier.focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
