package app.tape.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tape.graph
import java.text.Normalizer

/** "Ångström  Kapitel" → "angstrom kapitel": accents and case don't matter when searching. */
private val marks = Regex("\\p{Mn}+")
private fun fold(s: String?): String =
    if (s == null) "" else Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase()

/** Every word of the query must appear somewhere in the item's text. */
private fun matches(words: List<String>, vararg fields: String?): Boolean {
    val hay = fields.joinToString(" ") { fold(it) }
    return words.all { hay.contains(it) }
}

@Composable
fun SearchScreen(
    onClose: () -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val graph = LocalContext.current.graph
    val field = rememberTextFieldState()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        graph.music.load()
        graph.books.ensureScanned()
    }

    val books by graph.books.books.collectAsStateWithLifecycle()
    val playlists by graph.playlists.all.collectAsStateWithLifecycle()
    val albums by graph.music.albums.collectAsStateWithLifecycle()
    val songs by graph.music.songs.collectAsStateWithLifecycle()

    val query = field.text.toString()
    val words = remember(query) { fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() } }
    val foundBooks = remember(words, books) { if (words.isEmpty()) emptyList() else books.filter { matches(words, it.title, it.author) } }
    val foundPlaylists = remember(words, playlists) { if (words.isEmpty()) emptyList() else playlists.filter { matches(words, it.name) } }
    val foundAlbums = remember(words, albums) { if (words.isEmpty()) emptyList() else albums.filter { matches(words, it.title, it.artist) }.take(40) }
    val foundSongs = remember(words, songs) { if (words.isEmpty()) emptyList() else songs.filter { matches(words, it.name, it.artist, it.album) }.take(150) }
    val nothing = words.isNotEmpty() && foundBooks.isEmpty() && foundPlaylists.isEmpty() && foundAlbums.isEmpty() && foundSongs.isEmpty()

    Column(modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search", tint = Ink) }
            Box(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                if (query.isEmpty()) {
                    Text("Search music and books", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp), color = Muted)
                }
                BasicTextField(
                    state = field,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp, color = Ink),
                    cursorBrush = SolidColor(Accent),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { field.clearText() }) { Icon(Icons.Rounded.Close, "Clear", tint = Muted) }
            }
        }

        when {
            words.isEmpty() -> Text(
                "Find songs, albums, books and playlists, from this phone and from Drime.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            nothing -> Text(
                "Nothing matches “$query”.",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (foundBooks.isNotEmpty()) {
                    item { SectionLabel("Books") }
                    items(foundBooks, key = { "b:" + it.id }) { b ->
                        ResultRow(b.cover, b.title, b.author) { onOpenBook(b.id) }
                    }
                }
                if (foundPlaylists.isNotEmpty()) {
                    item { SectionLabel("Playlists") }
                    items(foundPlaylists, key = { "p:" + it.id }) { p -> PlaylistRow(p) { onOpenPlaylist(p.id) } }
                }
                if (foundAlbums.isNotEmpty()) {
                    item { SectionLabel("Albums") }
                    items(foundAlbums, key = { "a:" + it.key }) { a ->
                        ResultRow(a.art, a.title, a.artist) { onOpenAlbum(a.key) }
                    }
                }
                if (foundSongs.isNotEmpty()) {
                    item { SectionLabel("Songs") }
                    itemsIndexed(foundSongs, key = { _, t -> "s:" + t.mediaId }) { i, t ->
                        TrackRow(t, showArt = true) { graph.player.playTracks(foundSongs, i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(art: Any?, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(art, title, Modifier.size(52.dp), corner = 6.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
