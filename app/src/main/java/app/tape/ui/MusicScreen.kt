package app.tape.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tape.graph
import app.tape.library.Song

@Composable
fun MusicScreen(onOpenAlbum: (Long) -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    if (!granted) {
        EmptyState(
            title = "Your music, here",
            body = "TAPE plays the music stored on this phone. Allow it to see your audio files.",
            action = "Allow access",
            onAction = { ask.launch(permission) },
        )
        return
    }

    LaunchedEffect(Unit) { graph.music.refresh() }
    val albums by graph.music.albums.collectAsStateWithLifecycle()
    val songs by graph.music.songs.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableIntStateOf(0) }

    if (songs.isEmpty()) {
        EmptyState("No music yet", "Music you copy to this phone shows up here. Your Drime library is one tab over.")
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Segments(listOf("Albums", "Songs"), mode, { mode = it })
            Spacer(Modifier.weight(1f))
            Text(
                "Shuffle",
                style = MaterialTheme.typography.labelLarge,
                color = Muted,
                modifier = Modifier.clickable { graph.player.play(songs.map { it.toMediaItem() }, shuffle = true) }.padding(8.dp),
            )
        }

        if (mode == 0) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                items(albums, key = { it.id }) { album ->
                    Column(Modifier.quietClick { onOpenAlbum(album.id) }) {
                        Artwork(album.artUri, album.title, Modifier.fillMaxWidth().aspectRatio(1f), corner = 10.dp)
                        Spacer(Modifier.height(10.dp))
                        Text(album.title, style = MaterialTheme.typography.titleSmall, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(album.artist, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            val items = remember(songs) { songs.map { it.toMediaItem() } }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(songs, key = { _, s -> s.id }) { i, song ->
                    SongRow(song, showArt = true) { graph.player.play(items, i) }
                }
            }
        }
    }
}

@Composable
fun AlbumScreen(albumId: Long, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val albums by graph.music.albums.collectAsStateWithLifecycle()
    val album = albums.firstOrNull { it.id == albumId }
    if (album == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val items = remember(album) { album.songs.map { it.toMediaItem() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { BackBar("Albums", onBack) }
        item {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.Bottom) {
                Artwork(album.artUri, album.title, Modifier.size(132.dp), corner = 10.dp)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(album.title, style = MaterialTheme.typography.headlineSmall, color = Ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(album.artist, style = MaterialTheme.typography.bodyMedium, color = Muted)
                    Text("${album.songs.size} songs · ${formatDuration(album.durationMs)}", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Play", icon = Icons.Rounded.PlayArrow) { graph.player.play(items) }
                PillButton("Shuffle", icon = Icons.Rounded.Shuffle, primary = false) { graph.player.play(items, shuffle = true) }
            }
            Spacer(Modifier.height(8.dp))
        }
        itemsIndexed(album.songs, key = { _, s -> s.id }) { i, song ->
            SongRow(song, showArt = false, number = song.track.takeIf { it > 0 } ?: (i + 1)) { graph.player.play(items, i) }
        }
    }
}

@Composable
private fun SongRow(song: Song, showArt: Boolean, number: Int? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showArt) {
            Artwork(song.artUri, song.album, Modifier.size(44.dp), corner = 6.dp)
            Spacer(Modifier.width(14.dp))
        } else if (number != null) {
            Text("$number", style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.width(32.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(song.title, style = MaterialTheme.typography.bodyLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (showArt) Text(song.artist, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Text(formatTime(song.durationMs), style = MaterialTheme.typography.bodySmall, color = Muted)
    }
}
