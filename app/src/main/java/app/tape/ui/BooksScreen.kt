package app.tape.ui

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tape.graph
import app.tape.library.Book
import kotlinx.coroutines.launch

@Composable
fun BooksScreen(onOpenBook: (String) -> Unit) {
    val graph = LocalContext.current.graph
    val books by graph.books.books.collectAsStateWithLifecycle()
    val scanning by graph.books.scanning.collectAsStateWithLifecycle()
    val localRoot by graph.books.localRoot.collectAsStateWithLifecycle()
    val shelves by graph.books.shelves.collectAsStateWithLifecycle()
    val progressVersion by graph.progress.version.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) graph.scope.launch { graph.books.setLocalRoot(uri) }
    }

    LaunchedEffect(Unit) { graph.books.ensureScanned() }

    if (books.isEmpty() && localRoot == null && shelves.isEmpty()) {
        EmptyState(
            title = "Audiobooks",
            body = "Choose the folder where your audiobooks live. Each folder inside it becomes a book. You can also add folders from Drime.",
            action = "Choose folder",
            onAction = { pickFolder.launch(null) },
        )
        return
    }

    // Most recently played book that isn't finished.
    val current = remember(books, progressVersion) {
        books.mapNotNull { b -> graph.progress.get(b.id)?.takeIf { !it.finished }?.let { b to it } }
            .maxByOrNull { it.second.updatedAt }?.first
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        if (current != null) {
            item { SectionLabel("Continue") }
            item(key = "continue") {
                ContinueCard(current, progressVersion, onPlay = { graph.player.playBook(current) }, onOpen = { onOpenBook(current.id) })
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Library")
                if (scanning) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Muted)
            }
        }
        if (books.isEmpty() && !scanning) {
            item {
                Text(
                    "No books found yet. Put each book in its own folder.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Muted,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
        items(books, key = { it.id }) { book ->
            BookRow(book, progressVersion) { onOpenBook(book.id) }
        }

        item { SectionLabel("Sources", Modifier.padding(top = 20.dp)) }
        item {
            SourceRow(
                title = localRoot?.let { folderName(it) } ?: "No folder on this phone",
                subtitle = if (localRoot == null) "Choose folder" else "This phone · change",
                onClick = { pickFolder.launch(null) },
                onRemove = localRoot?.let { { graph.scope.launch { graph.books.clearLocalRoot() } } },
            )
        }
        items(shelves, key = { "shelf:" + it.hash }) { shelf ->
            SourceRow(
                title = shelf.name,
                subtitle = if (shelf.asBook) "Drime · single book" else "Drime · library folder",
                cloud = true,
                onClick = {},
                onRemove = { graph.scope.launch { graph.books.removeShelf(shelf.hash) } },
            )
        }
        item {
            Text(
                if (scanning) "Looking for books…" else "Look for new books",
                style = MaterialTheme.typography.labelLarge,
                color = Muted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).clip(CircleShape)
                    .clickable(enabled = !scanning) { graph.scope.launch { graph.books.rescan() } }.padding(12.dp),
            )
        }
    }
}

@Composable
private fun ContinueCard(book: Book, progressVersion: Long, onPlay: () -> Unit, onOpen: () -> Unit) {
    val graph = LocalContext.current.graph
    val p = remember(book.id, progressVersion) { graph.progress.get(book.id) }
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(book.cover, book.title, Modifier.size(76.dp), corner = 8.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val chapter = (p?.chapter ?: 0) + 1
            Text(
                if (book.chapters.size > 1) "Chapter $chapter of ${book.chapters.size}" else formatTime(p?.positionMs ?: 0),
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            Spacer(Modifier.height(10.dp))
            Hairline(p?.fraction(book.chapters.size) ?: 0f, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(Ink).clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.PlayArrow, "Resume", tint = Paper) }
    }
}

@Composable
private fun BookRow(book: Book, progressVersion: Long, onClick: () -> Unit) {
    val graph = LocalContext.current.graph
    val p = remember(book.id, progressVersion) { graph.progress.get(book.id) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(book.cover, book.title, Modifier.size(56.dp), corner = 6.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.bodyLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                book.author,
                when {
                    p?.finished == true -> "Finished"
                    p != null -> "${(p.fraction(book.chapters.size) * 100).toInt()}%"
                    book.chapters.size > 1 -> "${book.chapters.size} chapters"
                    else -> null
                },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p != null && !p.finished) {
                Spacer(Modifier.height(6.dp))
                Hairline(p.fraction(book.chapters.size), Modifier.fillMaxWidth(0.5f))
            }
        }
        if (book.isCloud) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Rounded.Cloud, "In Drime", tint = Muted.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun SourceRow(title: String, subtitle: String, cloud: Boolean = false, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
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
fun BookScreen(bookId: String, onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val books by graph.books.books.collectAsStateWithLifecycle()
    val progressVersion by graph.progress.version.collectAsStateWithLifecycle()
    val nowPlaying by graph.player.state.collectAsStateWithLifecycle()
    val book = books.firstOrNull { it.id == bookId }
    if (book == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val p = remember(book.id, progressVersion) { graph.progress.get(book.id) }
    val currentChapter = if (p != null && !p.finished) p.chapter else -1
    val playingHere = nowPlaying.bookId == book.id
    var menu by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            BackBar("Books", onBack) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = Ink) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (p != null) {
                            DropdownMenuItem(text = { Text("Start over") }, onClick = { menu = false; graph.progress.reset(book.id) })
                        }
                        if (p?.finished != true) {
                            DropdownMenuItem(text = { Text("Mark as finished") }, onClick = { menu = false; graph.progress.markFinished(book.id) })
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(book.cover, book.title, Modifier.size(200.dp), corner = 12.dp)
                Spacer(Modifier.height(20.dp))
                Text(book.title, style = MaterialTheme.typography.headlineSmall, color = Ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                book.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Muted) }
                Spacer(Modifier.height(20.dp))
                if (p != null && !p.finished) {
                    Hairline(p.fraction(book.chapters.size), Modifier.fillMaxWidth(0.6f))
                    Spacer(Modifier.height(20.dp))
                }
                val label = when {
                    playingHere && nowPlaying.isPlaying -> "Pause"
                    p != null && !p.finished -> "Resume"
                    p?.finished == true -> "Listen again"
                    else -> "Start listening"
                }
                PillButton(label, icon = if (label == "Pause") Icons.Rounded.Pause else Icons.Rounded.PlayArrow) {
                    if (playingHere) graph.player.togglePlay() else graph.player.playBook(book)
                }
            }
        }
        if (book.chapters.size > 1) {
            item { SectionLabel("${book.chapters.size} chapters", Modifier.padding(top = 16.dp)) }
            itemsIndexed(book.chapters, key = { _, c -> c.mediaId }) { i, chapter ->
                val isCurrent = i == currentChapter
                Row(
                    Modifier.fillMaxWidth().clickable { graph.player.playBook(book, chapter = i) }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isCurrent) Accent else Muted,
                        modifier = Modifier.width(36.dp),
                    )
                    Text(
                        chapter.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isCurrent) Accent else if (i < currentChapter) Muted else Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isCurrent && p != null) {
                        Text(formatTime(p.positionMs), style = MaterialTheme.typography.bodySmall, color = Accent)
                    }
                }
            }
        }
    }
}

/** "primary:Audiobooks/Fiction" → "Audiobooks/Fiction" */
private fun folderName(tree: Uri): String =
    runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfter(':').ifEmpty { "Phone storage" } }
        .getOrDefault("Chosen folder")
