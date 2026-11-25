package app.tape.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tape.AppGraph
import app.tape.drime.DrimeConfig
import app.tape.drime.DrimeEntry
import app.tape.graph
import app.tape.library.DrimeShelf
import app.tape.library.Track
import app.tape.library.prettyName
import kotlinx.coroutines.launch

@Composable
fun DrimeScreen() {
    val graph = LocalContext.current.graph
    val tokens by graph.tokens.tokens.collectAsStateWithLifecycle()
    if (tokens == null) ConnectDrime(graph) else DrimeBrowserView(graph)
}

/**
 * Connecting = pasting a personal access token (Drime › Settings › Developers).
 * Simple and fine for personal use. The OAuth sign-in (DrimeLogin.signIn) is still in
 * the code and appears as a second option only when the build has a drime.clientId,
 * i.e. if TAPE is ever shared with people who shouldn't have to make tokens.
 */
@Composable
private fun ConnectDrime(graph: AppGraph) {
    val context = LocalContext.current
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun connect() {
        if (busy || token.isBlank()) return
        busy = true
        error = null
        // App scope, not this screen's: once the token is saved this screen goes away,
        // and that must not cancel (and undo) the connection.
        graph.scope.launch {
            try {
                graph.drimeLogin.useAccessToken(token)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "That token didn't work."
            } finally {
                busy = false
            }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Your cloud library", style = MaterialTheme.typography.headlineSmall, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text(
            "Stream the music and audiobooks you keep in Drime. Paste an access token to connect.",
            style = MaterialTheme.typography.bodyMedium,
            color = Muted,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; error = null },
            singleLine = true,
            enabled = !busy,
            placeholder = { Text("Paste your Drime token") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { connect() }),
            isError = error != null,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        PillButton(if (busy) "Connecting…" else "Connect", enabled = token.isNotBlank() && !busy) { connect() }
        Spacer(Modifier.height(28.dp))
        Text("How to make a token", style = MaterialTheme.typography.titleSmall, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text(
            "In Drime, open Settings › Developers › Create token. Choose Restricted, set Files and Folders to Read, " +
                "and leave the rest at No access. TAPE can then only read, never change or delete.",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )

        if (DrimeConfig.hasClientId) {
            Spacer(Modifier.height(20.dp))
            Text(
                "Sign in with Drime instead",
                style = MaterialTheme.typography.labelLarge,
                color = Muted,
                modifier = Modifier.clip(CircleShape).clickable {
                    val activity = context.findActivity() ?: return@clickable
                    busy = true
                    error = null
                    // App scope: the sign-in outlives this screen while the browser is in front.
                    graph.scope.launch {
                        try {
                            graph.drimeLogin.signIn(activity)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            error = e.message ?: "Couldn't connect to Drime."
                        } finally {
                            busy = false
                        }
                    }
                }.padding(vertical = 10.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrimeBrowserView(graph: AppGraph) {
    val state by graph.drimeBrowser.state.collectAsStateWithLifecycle()
    val account by graph.tokens.accountName.collectAsStateWithLifecycle()
    var folderMenu by remember { mutableStateOf<DrimeEntry?>(null) }
    var accountMenu by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { if (!state.loaded && !state.loading) graph.drimeBrowser.refresh() }
    BackHandler(enabled = state.path.size > 1) { graph.drimeBrowser.up() }

    val tracks = state.tracks
    val here = state.here
    val items = remember(tracks) {
        tracks.map { e ->
            Track(
                mediaId = "drime:${e.hash}",
                uri = graph.drime.downloadUrl(e.hash).toUri(),
                title = null, // embedded tags win when present
                displayTitle = prettyName(e.name),
                artist = null,
                album = here.name.takeIf { here.hash != null },
                artwork = null,
            )
        }
    }
    var fileMenu by remember { mutableStateOf<Int?>(null) }

    fun addToBooks(hash: String, name: String, asBook: Boolean) {
        graph.scope.launch { graph.books.addShelf(DrimeShelf(hash, name, asBook)) }
        toast = if (asBook) "“$name” added to Books" else "Books will look inside “$name”"
    }

    fun addToMusic(hash: String, name: String) {
        graph.scope.launch { graph.drimeMusic.add(hash, name) }
        toast = "“$name” added to Music"
    }

    Column(Modifier.fillMaxSize()) {
        // Breadcrumbs
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                state.path.forEachIndexed { i, crumb ->
                    val last = i == state.path.lastIndex
                    Text(
                        crumb.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (last) Ink else Muted,
                        maxLines = 1,
                        modifier = Modifier.quietClick { if (!last) graph.drimeBrowser.goTo(i) }.padding(vertical = 12.dp, horizontal = 6.dp),
                    )
                    if (!last) Text("/", color = Muted.copy(alpha = 0.5f), style = MaterialTheme.typography.labelLarge)
                }
            }
            if (state.loading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Muted)
            Box {
                IconButton(onClick = { accountMenu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = Muted) }
                DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                    if (here.hash != null) {
                        DropdownMenuItem(
                            text = { Text("Add this folder to Music") },
                            onClick = { accountMenu = false; addToMusic(here.hash, here.name) },
                        )
                        DropdownMenuItem(
                            text = { Text("Add this folder to Books") },
                            onClick = { accountMenu = false; addToBooks(here.hash, here.name, asBook = true) },
                        )
                        DropdownMenuItem(
                            text = { Text("Use as audiobook library") },
                            onClick = { accountMenu = false; addToBooks(here.hash, here.name, asBook = false) },
                        )
                    }
                    DropdownMenuItem(text = { Text("Refresh") }, onClick = { accountMenu = false; graph.drimeBrowser.refresh() })
                    DropdownMenuItem(
                        text = { Text(if (account != null) "Disconnect $account" else "Disconnect") },
                        onClick = {
                            accountMenu = false
                            graph.drimeBrowser.reset()
                            graph.scope.launch { graph.drimeLogin.signOut() }
                        },
                    )
                }
            }
        }

        toast?.let {
            LaunchedEffect(it) { kotlinx.coroutines.delay(2500); toast = null }
            Text(it, style = MaterialTheme.typography.bodySmall, color = Accent, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
        }

        when {
            state.error != null && state.entries.isEmpty() -> EmptyState(
                title = "Couldn't open folder",
                body = state.error!!,
                action = "Try again",
                onAction = { graph.drimeBrowser.refresh() },
            )
            state.loaded && !state.loading && state.folders.isEmpty() && tracks.isEmpty() -> EmptyState(
                title = "Nothing to play here",
                body = "This folder has no audio files or subfolders.",
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (tracks.isNotEmpty()) {
                    item {
                        Row(Modifier.padding(horizontal = 24.dp, vertical = 10.dp)) {
                            PillButton("Play", icon = Icons.Rounded.PlayArrow) { graph.player.playTracks(items) }
                            Spacer(Modifier.width(10.dp))
                            PillButton("Shuffle", icon = Icons.Rounded.Shuffle, primary = false) { graph.player.playTracks(items, shuffle = true) }
                        }
                    }
                }
                items(state.folders, key = { it.hash }) { folder ->
                    Box {
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(onClick = { graph.drimeBrowser.open(folder) }, onLongClick = { folderMenu = folder })
                                .padding(horizontal = 24.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Folder, null, tint = Muted, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(16.dp))
                            Text(folder.name, style = MaterialTheme.typography.bodyLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Icon(Icons.Rounded.ChevronRight, null, tint = Muted.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = folderMenu == folder, onDismissRequest = { folderMenu = null }) {
                            DropdownMenuItem(
                                text = { Text("Add to Music") },
                                leadingIcon = { Icon(Icons.Rounded.MusicNote, null) },
                                onClick = { folderMenu = null; addToMusic(folder.hash, folder.name) },
                            )
                            DropdownMenuItem(
                                text = { Text("Add to Books as one book") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.LibraryBooks, null) },
                                onClick = { folderMenu = null; addToBooks(folder.hash, folder.name, asBook = true) },
                            )
                            DropdownMenuItem(
                                text = { Text("Use as audiobook library") },
                                onClick = { folderMenu = null; addToBooks(folder.hash, folder.name, asBook = false) },
                            )
                        }
                    }
                }
                items(tracks.size, key = { tracks[it].hash }) { i ->
                    val track = tracks[i]
                    Box {
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(onClick = { graph.player.playTracks(items, i) }, onLongClick = { fileMenu = i })
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.GraphicEq, null, tint = Muted.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(prettyName(track.name), style = MaterialTheme.typography.bodyLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOf(DrimeEntry.extensionOf(track.name, track.extension).uppercase(), formatBytes(track.size)).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted,
                                )
                            }
                        }
                        DropdownMenu(expanded = fileMenu == i, onDismissRequest = { fileMenu = null }) {
                            DropdownMenuItem(
                                text = { Text("Add to playlist…") },
                                onClick = { fileMenu = null; PlaylistPicker.open(listOf(items[i])) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "${bytes / 1024} KB"
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
