package app.tape.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tape.graph
import kotlinx.coroutines.launch

@Composable
fun MusicScreen(onOpenAlbum: (String) -> Unit, onOpenPlaylist: (String) -> Unit) {
    val graph = LocalContext.current.graph
    var granted by remember { mutableStateOf(graph.localMusic.hasPermission) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    LaunchedEffect(granted) {
        if (granted) graph.localMusic.refresh()
        graph.drimeMusic.ensureScanned()
    }
    val albums by graph.music.albums.collectAsStateWithLifecycle()
    val songs by graph.music.songs.collectAsStateWithLifecycle()
    val playlists by graph.playlists.all.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableIntStateOf(0) }

    if (!granted && albums.isEmpty() && playlists.isEmpty()) {
        EmptyState(
            title = "Your music, here",
            body = "TAPE plays music stored on this phone and in Drime. Allow it to see your audio files, " +
                "or add a Drime folder to Music from the Drime tab.",
            action = "Allow access",
            onAction = { ask.launch(graph.localMusic.permission) },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Segments(listOf("Albums", "Songs", "Playlists"), mode, { mode = it })
            Spacer(Modifier.weight(1f))
            if (mode != 2 && songs.isNotEmpty()) {
                Text(
                    "Shuffle",
                    style = MaterialTheme.typography.labelLarge,
                    color = Muted,
                    modifier = Modifier.clickable { graph.player.playTracks(songs, shuffle = true) }.padding(8.dp),
                )
            }
        }

        when (mode) {
            0 -> AlbumGrid(granted, onAllow = { ask.launch(graph.localMusic.permission) }, onOpenAlbum = onOpenAlbum)
            1 -> if (songs.isEmpty()) {
                EmptyState("No songs yet", "Songs from this phone and from Drime folders you add to Music show up here.")
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(songs, key = { _, t -> t.mediaId }) { i, t ->
                        TrackRow(t, showArt = true, actions = listOf(addToPlaylistAction(t))) { graph.player.playTracks(songs, i) }
                    }
                }
            }
            else -> PlaylistsList(onOpen = onOpenPlaylist)
        }
    }
}

@Composable
private fun AlbumGrid(granted: Boolean, onAllow: () -> Unit, onOpenAlbum: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val albums by graph.music.albums.collectAsStateWithLifecycle()
    val shelves by graph.drimeMusic.shelves.collectAsStateWithLifecycle()
    val scanning by graph.drimeMusic.scanning.collectAsStateWithLifecycle()
    val connected by graph.tokens.tokens.collectAsStateWithLifecycle()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        if (albums.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (scanning) "Looking for music in Drime…" else "No albums yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                )
            }
        }
        items(albums, key = { it.key }) { album ->
            Column(Modifier.quietClick { onOpenAlbum(album.key) }) {
                Box {
                    Artwork(album.art, album.title, Modifier.fillMaxWidth().aspectRatio(1f), corner = 10.dp)
                    if (album.isCloud) {
                        Icon(
                            Icons.Rounded.Cloud, "In Drime", tint = Paper.copy(alpha = 0.9f),
                            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(14.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(album.title, style = MaterialTheme.typography.titleSmall, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(album.artist, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // Sources: where Music comes from, and how to change it.
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SOURCES", style = MaterialTheme.typography.labelSmall, color = Muted)
                    if (scanning) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Muted)
                    }
                }
                Spacer(Modifier.height(8.dp))
                MusicSourceRow(
                    title = "This phone",
                    subtitle = if (granted) "All music on this phone" else "Not allowed yet · tap to allow",
                    onClick = if (granted) null else onAllow,
                )
                shelves.forEach { shelf ->
                    MusicSourceRow(
                        title = shelf.name,
                        subtitle = if (connected == null) "Drime · not connected" else "Drime folder",
                        cloud = true,
                        onRemove = { graph.scope.launch { graph.drimeMusic.remove(shelf.hash) } },
                    )
                }
                if (shelves.isEmpty()) {
                    Text(
                        "To add music from Drime, open a folder in the Drime tab and choose “Add to Music”.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                } else {
                    Text(
                        if (scanning) "Looking for new music…" else "Look for new music",
                        style = MaterialTheme.typography.labelLarge,
                        color = Muted,
                        modifier = Modifier.clickable(enabled = !scanning) { graph.scope.launch { graph.drimeMusic.rescan() } }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MusicSourceRow(
    title: String,
    subtitle: String,
    cloud: Boolean = false,
    onClick: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        if (cloud) Icon(Icons.Rounded.Cloud, null, tint = Muted.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        if (onRemove != null) {
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove source", tint = Muted, modifier = Modifier.size(18.dp)) }
        }
    }
}

@Composable
fun AlbumScreen(albumKey: String, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val albums by graph.music.albums.collectAsStateWithLifecycle()
    val album = albums.firstOrNull { it.key == albumKey }
    if (album == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var menu by remember { mutableStateOf(false) }
    val total = album.durationMs

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            BackBar("Albums", onBack) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = Ink) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Add album to playlist…") },
                            onClick = { menu = false; PlaylistPicker.open(album.tracks) },
                        )
                    }
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.Bottom) {
                Artwork(album.art, album.title, Modifier.size(132.dp), corner = 10.dp)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(album.title, style = MaterialTheme.typography.headlineSmall, color = Ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(album.artist, style = MaterialTheme.typography.bodyMedium, color = Muted)
                    Text(
                        listOfNotNull("${album.tracks.size} songs", formatDuration(total).takeIf { total > 0 }, "Drime".takeIf { album.isCloud })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                    )
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Play", icon = Icons.Rounded.PlayArrow) { graph.player.playTracks(album.tracks) }
                PillButton("Shuffle", icon = Icons.Rounded.Shuffle, primary = false) { graph.player.playTracks(album.tracks, shuffle = true) }
            }
            Spacer(Modifier.height(8.dp))
        }
        itemsIndexed(album.tracks, key = { _, t -> t.mediaId }) { i, t ->
            TrackRow(t, showArt = false, number = i + 1, actions = listOf(addToPlaylistAction(t))) {
                graph.player.playTracks(album.tracks, i)
            }
        }
    }
}
