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
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One chapter. Usually a whole file; for chapters inside one file (an .m4b) it's a span of
 * that file: [startMs] until [endMs] (-1 = to the end).
 */
data class Chapter(
    val title: String,
    val uri: Uri,
    val mediaId: String,
    val startMs: Long = 0L,
    val endMs: Long = -1L,
)

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

    /** Drime files behind this book as (file id, url, title); chapters of one .m4b share a file. */
    fun cloudFiles(): List<Triple<String, String, String>> = chapters
        .filter { it.mediaId.startsWith("drime:") }
        .map { Triple(Media.fileId(it.mediaId), it.uri.toString(), title) }
        .distinctBy { it.first }

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
            clipStartMs = ch.startMs,
            clipEndMs = if (ch.endMs >= 0) ch.endMs else androidx.media3.common.C.TIME_END_OF_SOURCE,
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
    private val http: OkHttpClient, // Drime-authenticated; used for reading chapter marks
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
            withContext(Dispatchers.IO) {
                writeCache(books)
                writeChapterMarks()
            }
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
                chapters = files.flatMap { (_, doc) ->
                    chaptersFor(doc.name, docUri(tree, doc.id), "local:${doc.id}", prettyName(doc.name), remote = false)
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
                chapters = chaptersFor(file.name, docUri(tree, file.id), "local:${file.id}", title, remote = false),
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
                chapters = withContext(Dispatchers.IO) {
                    chaptersFor(file.name, drime.downloadUrl(file.hash).toUri(), "drime:${file.hash}", title, remote = true)
                },
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
            chapters = withContext(Dispatchers.IO) {
                files.flatMap { (_, e) ->
                    chaptersFor(e.name, drime.downloadUrl(e.hash).toUri(), "drime:${e.hash}", prettyName(e.name), remote = true)
                }
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
                        JSONArray(
                            b.chapters.map {
                                JSONObject().put("t", it.title).put("u", it.uri.toString()).put("m", it.mediaId)
                                    .put("s", it.startMs).put("e", it.endMs)
                            },
                        ),
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
                    Chapter(c.getString("t"), c.getString("u").toUri(), c.getString("m"), c.optLong("s"), c.optLong("e", -1L))
                },
            )
        }
    }

    // ---- Chapters inside .m4b / .m4a files ------------------------------------------------

    private val marksFile = File(context.filesDir, "chapter_marks.json")
    private val marks: MutableMap<String, List<FileChapter>> = readChapterMarks()

    /**
     * One file → its chapters. A plain audio file is one chapter; an MP4-family file with
     * chapter marks becomes one clipped [Chapter] per mark. Marks are read once per file
     * (only the file's index, not the audio) and remembered.
     */
    private fun chaptersFor(name: String, uri: Uri, mediaId: String, title: String, remote: Boolean): List<Chapter> {
        val plain = listOf(Chapter(title, uri, mediaId))
        if (DrimeEntry.extensionOf(name, null) !in MP4_FAMILY) return plain
        val key = uri.toString()
        val found = synchronized(marks) { marks[key] } ?: run {
            val read = try {
                val src: ByteSource = if (remote) HttpByteSource(http, key) else LocalByteSource(context, uri)
                src.use { Mp4Chapters.read(it) } ?: emptyList()
            } catch (e: Exception) {
                return plain // offline or unreadable: try again next scan
            }
            synchronized(marks) { marks[key] = read }
            read
        }
        if (found.size < 2) return plain
        return found.mapIndexed { i, m ->
            Chapter(
                title = m.title.ifBlank { "Chapter ${i + 1}" },
                uri = uri,
                mediaId = "$mediaId#$i",
                startMs = m.startMs,
                endMs = found.getOrNull(i + 1)?.startMs ?: -1L,
            )
        }
    }

    private fun readChapterMarks(): MutableMap<String, List<FileChapter>> = runCatching {
        val o = JSONObject(marksFile.readText())
        o.keys().asSequence().associateWith { k ->
            val arr = o.getJSONArray(k)
            (0 until arr.length()).map { arr.getJSONObject(it).let { c -> FileChapter(c.getString("t"), c.getLong("s")) } }
        }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    private fun writeChapterMarks() {
        val o = JSONObject()
        synchronized(marks) {
            marks.forEach { (k, list) -> o.put(k, JSONArray(list.map { JSONObject().put("t", it.title).put("s", it.startMs) })) }
        }
        runCatching { marksFile.writeText(o.toString()) }
    }

    private companion object {
        val MP4_FAMILY = setOf("m4b", "m4a", "mp4")
        const val KEY_LOCAL = "local_root"
        const val KEY_SHELVES = "drime_shelves"
        val COVER_NAMES = setOf("cover", "folder", "front", "album", "artwork")
    }
}
