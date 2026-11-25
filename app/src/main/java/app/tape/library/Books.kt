package app.tape.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import app.tape.drime.DrimeApi
import app.tape.drime.DrimeEntry
import app.tape.playback.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Chapter(val title: String, val uri: Uri, val mediaId: String)

data class Book(
    val id: String,
    val title: String,
    val author: String?,
    /** "local" or "drime:<hash>" — which source produced this book. */
    val source: String,
    val cover: Uri?,
    val chapters: List<Chapter>,
) {
    val isCloud: Boolean get() = source.startsWith("drime:")

    fun mediaItems(): List<MediaItem> = chapters.mapIndexed { i, ch ->
        Media.item(
            mediaId = ch.mediaId,
            uri = ch.uri,
            title = ch.title,
            artist = author,
            album = title,
            artwork = cover,
            bookId = id,
            track = i + 1,
        )
    }
}

/**
 * A Drime folder the user added to Books.
 * [asBook] = this folder *is* one book; otherwise it's a shelf whose subfolders are books.
 */
data class DrimeShelf(val hash: String, val name: String, val asBook: Boolean) {
    val key: String get() = "drime:$hash"
}

/**
 * Audiobooks from a local folder (picked with the system folder picker) and
 * from Drime folders. A book is a folder of audio files (one level of
 * "CD 1"/"Part 2" subfolders is flattened), or a single file such as an .m4b.
 */
class BookLibrary(
    private val context: Context,
    private val drime: DrimeApi,
) {
    private val prefs = context.getSharedPreferences("books", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "books.json")

    private val _books = MutableStateFlow(readCache())
    val books: StateFlow<List<Book>> = _books.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _localRoot = MutableStateFlow(prefs.getString(KEY_LOCAL, null)?.toUri())
    val localRoot: StateFlow<Uri?> = _localRoot.asStateFlow()

    private val _shelves = MutableStateFlow(readShelves())
    val shelves: StateFlow<List<DrimeShelf>> = _shelves.asStateFlow()

    fun find(id: String): Book? = _books.value.firstOrNull { it.id == id }

    suspend fun setLocalRoot(uri: Uri) {
        val resolver = context.contentResolver
        _localRoot.value?.let { old ->
            if (old != uri) runCatching { resolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.edit().putString(KEY_LOCAL, uri.toString()).apply()
        _localRoot.value = uri
        rescan()
    }

    suspend fun clearLocalRoot() {
        _localRoot.value?.let { runCatching { context.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        prefs.edit().remove(KEY_LOCAL).apply()
        _localRoot.value = null
        rescan()
    }

    suspend fun addShelf(shelf: DrimeShelf) {
        _shelves.value = _shelves.value.filterNot { it.hash == shelf.hash } + shelf
        writeShelves()
        rescan()
    }

    suspend fun removeShelf(hash: String) {
        _shelves.value = _shelves.value.filterNot { it.hash == hash }
        writeShelves()
        rescan()
    }

    private var scannedThisSession = false

    /** Scan once per app run; after that only when the user asks or a source changes. */
    suspend fun ensureScanned() {
        if (!scannedThisSession) rescan()
    }

    suspend fun rescan() {
        if (_scanning.value) return
        scannedThisSession = true
        _scanning.value = true
        try {
            val previous = _books.value.groupBy { it.source }
            val found = mutableListOf<Book>()

            _localRoot.value?.let { root ->
                found += runCatching { withContext(Dispatchers.IO) { scanLocal(root) } }
                    .getOrElse { previous["local"].orEmpty() }
            }
            for (shelf in _shelves.value) {
                // Offline? Keep what we knew about this shelf rather than emptying it.
                found += runCatching { scanShelf(shelf) }.getOrElse { previous[shelf.key].orEmpty() }
            }

            val books = found.distinctBy { it.id }.sortedWith(compareBy(NaturalOrder) { it.title })
            _books.value = books
            withContext(Dispatchers.IO) { writeCache(books) }
        } finally {
            _scanning.value = false
        }
    }

    // ---- Local (Storage Access Framework) ---------------------------------------------

    private data class Doc(val id: String, val name: String, val mime: String) {
        val isDir get() = mime == Document.MIME_TYPE_DIR
        val isAudio get() = !isDir && (mime.startsWith("audio/") || DrimeEntry.extensionOf(name, null) in DrimeEntry.AUDIO_EXTENSIONS)
        val isImage get() = !isDir && (mime.startsWith("image/") || DrimeEntry.extensionOf(name, null) in DrimeEntry.IMAGE_EXTENSIONS)
    }

    private fun scanLocal(tree: Uri): List<Book> {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val top = children(tree, rootId)
        val books = mutableListOf<Book>()
        for (dir in top.filter { it.isDir }) {
            val inside = children(tree, dir.id)
            val files = mutableListOf<Pair<String, Doc>>()
            inside.filter { it.isAudio }.forEach { files += it.name to it }
            inside.filter { it.isDir }.forEach { sub ->
                children(tree, sub.id).filter { it.isAudio }.forEach { files += "${sub.name}/${it.name}" to it }
            }
            if (files.isEmpty()) continue
            files.sortWith(compareBy(NaturalOrder) { it.first })
            val (title, author) = splitAuthor(dir.name)
            val bookId = "local:${dir.id}"
            books += Book(
                id = bookId,
                title = title,
                author = author,
                source = "local",
                cover = pickCover(inside.filter { it.isImage }, { it.name })?.let { docUri(tree, it.id) },
                chapters = files.map { (_, doc) ->
                    Chapter(prettyName(doc.name), docUri(tree, doc.id), "local:${doc.id}")
                },
            )
        }
        for (file in top.filter { it.isAudio }) {
            val (title, author) = splitAuthor(prettyName(file.name))
            books += Book(
                id = "local:${file.id}",
                title = title,
                author = author,
                source = "local",
                cover = null,
                chapters = listOf(Chapter(title, docUri(tree, file.id), "local:${file.id}")),
            )
        }
        return books
    }

    private fun children(tree: Uri, parentId: String): List<Doc> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        val out = mutableListOf<Doc>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name.startsWith(".")) continue
                out += Doc(c.getString(0), name, c.getString(2).orEmpty())
            }
        }
        return out
    }

    private fun docUri(tree: Uri, id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    // ---- Drime -------------------------------------------------------------------------

    private suspend fun scanShelf(shelf: DrimeShelf): List<Book> {
        if (shelf.asBook) return listOfNotNull(drimeBook(shelf.hash, shelf.name, shelf.key))
        val entries = drime.list(shelf.hash).entries
        val gate = Semaphore(4) // be gentle with the API
        val folderBooks = coroutineScope {
            entries.filter { it.isFolder }.map { folder ->
                async { gate.withPermit { drimeBook(folder.hash, folder.name, shelf.key) } }
            }.awaitAll().filterNotNull()
        }
        val singles = entries.filter { it.isAudio }.map { file ->
            val (title, author) = splitAuthor(prettyName(file.name))
            Book(
                id = "drime:${file.hash}",
                title = title,
                author = author,
                source = shelf.key,
                cover = null,
                chapters = listOf(Chapter(title, drime.downloadUrl(file.hash).toUri(), "drime:${file.hash}")),
            )
        }
        return folderBooks + singles
    }

    private suspend fun drimeBook(hash: String, name: String, source: String): Book? {
        val entries = drime.list(hash).entries
        val files = mutableListOf<Pair<String, DrimeEntry>>()
        entries.filter { it.isAudio }.forEach { files += it.name to it }
        for (sub in entries.filter { it.isFolder }) {
            drime.list(sub.hash).entries.filter { it.isAudio }.forEach { files += "${sub.name}/${it.name}" to it }
        }
        if (files.isEmpty()) return null
        files.sortWith(compareBy(NaturalOrder) { it.first })
        val (title, author) = splitAuthor(name)
        return Book(
            id = "drime:$hash",
            title = title,
            author = author,
            source = source,
            cover = pickCover(entries.filter { it.isImage }, { it.name })?.let { drime.downloadUrl(it.hash).toUri() },
            chapters = files.map { (_, e) ->
                Chapter(prettyName(e.name), drime.downloadUrl(e.hash).toUri(), "drime:${e.hash}")
            },
        )
    }

    private fun <T> pickCover(images: List<T>, name: (T) -> String): T? =
        images.firstOrNull { name(it).substringBeforeLast('.').lowercase() in COVER_NAMES } ?: images.firstOrNull()

    // ---- Persistence ---------------------------------------------------------------------

    private fun readShelves(): List<DrimeShelf> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_SHELVES, "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            DrimeShelf(o.getString("hash"), o.getString("name"), o.optBoolean("book"))
        }
    }.getOrDefault(emptyList())

    private fun writeShelves() {
        val arr = JSONArray(_shelves.value.map { JSONObject().put("hash", it.hash).put("name", it.name).put("book", it.asBook) })
        prefs.edit().putString(KEY_SHELVES, arr.toString()).apply()
    }

    private fun writeCache(books: List<Book>) {
        val arr = JSONArray(
            books.map { b ->
                JSONObject()
                    .put("id", b.id)
                    .put("title", b.title)
                    .put("author", b.author ?: "")
                    .put("source", b.source)
                    .put("cover", b.cover?.toString() ?: "")
                    .put(
                        "chapters",
                        JSONArray(b.chapters.map { JSONObject().put("t", it.title).put("u", it.uri.toString()).put("m", it.mediaId) }),
                    )
            },
        )
        cacheFile.writeText(arr.toString())
    }

    private fun readCache(): List<Book> {
        if (!cacheFile.exists()) return emptyList()
        return runCatching { parseCache(cacheFile.readText()) }.getOrDefault(emptyList())
    }

    private fun parseCache(text: String): List<Book> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val ch = o.getJSONArray("chapters")
            Book(
                id = o.getString("id"),
                title = o.getString("title"),
                author = o.optString("author").ifEmpty { null },
                source = o.getString("source"),
                cover = o.optString("cover").ifEmpty { null }?.toUri(),
                chapters = (0 until ch.length()).map { j ->
                    val c = ch.getJSONObject(j)
                    Chapter(c.getString("t"), c.getString("u").toUri(), c.getString("m"))
                },
            )
        }
    }

    private companion object {
        const val KEY_LOCAL = "local_root"
        const val KEY_SHELVES = "drime_shelves"
        val COVER_NAMES = setOf("cover", "folder", "front", "album", "artwork")
    }
}
