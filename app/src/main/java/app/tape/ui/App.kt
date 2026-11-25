package app.tape.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tape.graph

enum class Tab(val label: String) { Music("Music"), Books("Books"), Drime("Drime") }

@Composable
fun TapeRoot() {
    val graph = LocalContext.current.graph
    val nowPlaying by graph.player.state.collectAsStateWithLifecycle()

    var tab by rememberSaveable { mutableStateOf(Tab.Music) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var openAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    var openPlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var openBook by rememberSaveable { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(Paper)) {
        Column(Modifier.fillMaxSize()) {
            Header(selected = tab, onSelect = {
                if (it == tab) { openAlbum = null; openPlaylist = null; openBook = null } // tapping the current tab goes home
                tab = it
            })

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    Tab.Music -> {
                        val album = openAlbum
                        val playlist = openPlaylist
                        when {
                            album != null -> AlbumScreen(album, onBack = { openAlbum = null })
                            playlist != null -> PlaylistScreen(playlist, onBack = { openPlaylist = null })
                            else -> MusicScreen(onOpenAlbum = { openAlbum = it }, onOpenPlaylist = { openPlaylist = it })
                        }
                    }
                    Tab.Books -> {
                        val book = openBook
                        if (book == null) BooksScreen(onOpenBook = { openBook = it })
                        else BookScreen(book, onBack = { openBook = null })
                    }
                    Tab.Drime -> DrimeScreen()
                }
            }

            AnimatedVisibility(nowPlaying.hasMedia, enter = fadeIn(), exit = fadeOut()) {
                MiniPlayer(nowPlaying, onOpen = { playerOpen = true })
            }
            Spacer(Modifier.navigationBarsPadding())
        }

        AnimatedVisibility(
            visible = playerOpen && nowPlaying.hasMedia,
            enter = slideInVertically(tween(320)) { it },
            exit = slideOutVertically(tween(260)) { it },
        ) {
            NowPlayingScreen(onClose = { playerOpen = false })
        }
    }

    PlaylistPicker.pending?.let { tracks ->
        AddToPlaylistSheet(tracks, onDismiss = { PlaylistPicker.pending = null })
    }

    BackHandler(enabled = openAlbum != null && tab == Tab.Music && !playerOpen) { openAlbum = null }
    BackHandler(enabled = openPlaylist != null && openAlbum == null && tab == Tab.Music && !playerOpen) { openPlaylist = null }
    BackHandler(enabled = openBook != null && tab == Tab.Books && !playerOpen) { openBook = null }
    BackHandler(enabled = playerOpen) { playerOpen = false }
}

/** The tab names are the title: large, quiet, the current one in ink. */
@Composable
private fun Header(selected: Tab, onSelect: (Tab) -> Unit) {
    var about by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Music and Books are the two libraries, so they get the big titles.
        listOf(Tab.Music, Tab.Books).forEach { t ->
            val color by animateColorAsState(if (t == selected) Ink else Muted.copy(alpha = 0.55f), label = "tab")
            Text(
                t.label,
                color = color,
                style = MaterialTheme.typography.displaySmall.copy(fontSize = TabTitleSize),
                fontWeight = if (t == selected) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier.quietClick { onSelect(t) },
            )
            Spacer(Modifier.width(20.dp))
        }
        Spacer(Modifier.weight(1f))

        // Drime is where files come from, not a library of its own: a small chip, not a title.
        val onDrime = selected == Tab.Drime
        val chipBg by animateColorAsState(if (onDrime) Ink else Color.Transparent, label = "drimeBg")
        val chipFg by animateColorAsState(if (onDrime) Paper else Muted, label = "drimeFg")
        Row(
            Modifier
                .padding(bottom = 3.dp)
                .clip(CircleShape)
                .background(chipBg)
                .quietClick { onSelect(Tab.Drime) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Cloud, contentDescription = null, tint = chipFg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Drime", style = MaterialTheme.typography.labelLarge, color = chipFg)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Outlined.Info,
            contentDescription = "About TAPE",
            tint = Muted.copy(alpha = 0.6f),
            modifier = Modifier.padding(bottom = 9.dp).size(20.dp).quietClick { about = true },
        )
    }
    if (about) AboutSheet(onDismiss = { about = false })
}
