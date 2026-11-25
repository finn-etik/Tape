package app.tape.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import app.tape.AppGraph
import app.tape.library.Book

/**
 * What Android Auto (and other media browsers) see: four tabs, each a simple list.
 *
 *   root
 *   ├─ Books       → each book is playable and resumes where you left off
 *   ├─ Playlists   → playlist → its songs
 *   ├─ Albums      → album → its songs (phone + Drime)
 *   └─ Songs       → every song
 *
 * Ids encode what to play, so a tap in the car resolves to a whole queue:
 *   book:<bookId>  album:<key>  albumtrack:<i>:<key>  playlist:<id>  pltrack:<i>:<id>  song:<mediaId>
 */
class LibraryTree(private val graph: AppGraph) {

    fun root(): MediaItem = folder(ROOT, "TAPE", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    suspend fun children(parentId: String): List<MediaItem>? {
        return when {
            parentId == ROOT -> listOf(
                folder(BOOKS, "Books", MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS),
                folder(PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS),
                folder(ALBUMS, "Albums", MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS),
                folder(SONGS, "Songs", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED),
            )
            parentId == BOOKS -> booksByRecent().map { b ->
                val p = graph.progress.get(b.id)
                val status = when {
                    p == null -> b.author
                    p.finished -> "Finished"
                    else -> "${(p.fraction(b.chapters.size) * 100).toInt()}%" + (b.author?.let { " · $it" } ?: "")
                }
                playable("book:${b.id}", b.title, status, MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
            }
            parentId == PLAYLISTS -> graph.playlists.all.value.map { p ->
                folder("playlist:${p.id}", p.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, "${p.tracks.size} songs")
            }
            parentId == ALBUMS -> {
                graph.music.load()
                graph.music.currentAlbums().map { a -> folder("album:${a.key}", a.title, MediaMetadata.MEDIA_TYPE_ALBUM, a.artist) }
            }
            parentId == SONGS -> {
                graph.music.load()
                graph.music.currentSongs().map { t -> playable("song:${t.mediaId}", t.name, t.artist, MediaMetadata.MEDIA_TYPE_MUSIC) }
            }
            parentId.startsWith("album:") -> {
                graph.music.load()
                val key = parentId.removePrefix("album:")
                graph.music.album(key)?.tracks?.mapIndexed { i, t ->
                    playable("albumtrack:$i:$key", t.name, t.artist, MediaMetadata.MEDIA_TYPE_MUSIC)
                }
            }
            parentId.startsWith("playlist:") -> {
                val id = parentId.removePrefix("playlist:")
                graph.playlists.get(id)?.tracks?.mapIndexed { i, t ->
                    playable("pltrack:$i:$id", t.name, t.artist, MediaMetadata.MEDIA_TYPE_MUSIC)
                }
            }
            else -> null
        }
    }

    suspend fun item(id: String): MediaItem? = when (id) {
        ROOT -> root()
        BOOKS, PLAYLISTS, ALBUMS, SONGS -> children(ROOT)?.firstOrNull { it.mediaId == id }
        else -> null
    }

    /** Turns a tapped id (or a voice search) into the queue to play. Null = not ours. */
    suspend fun resolve(item: MediaItem): MediaItemsWithStartPosition? {
        val id = item.mediaId
        return when {
            id.startsWith("book:") -> graph.books.find(id.removePrefix("book:"))?.let { bookQueue(it) }
            id.startsWith("albumtrack:") -> {
                graph.music.load()
                val (_, index, key) = id.split(":", limit = 3)
                graph.music.album(key)?.let { a -> MediaItemsWithStartPosition(a.tracks.map { it.toMediaItem() }, index.toIntOrNull() ?: 0, 0) }
            }
            id.startsWith("album:") -> {
                graph.music.load()
                graph.music.album(id.removePrefix("album:"))?.let { a -> MediaItemsWithStartPosition(a.tracks.map { it.toMediaItem() }, 0, 0) }
            }
            id.startsWith("pltrack:") -> {
                val (_, index, pid) = id.split(":", limit = 3)
                graph.playlists.get(pid)?.let { p -> MediaItemsWithStartPosition(p.tracks.map { it.toMediaItem() }, index.toIntOrNull() ?: 0, 0) }
            }
            id.startsWith("playlist:") ->
                graph.playlists.get(id.removePrefix("playlist:"))?.let { p -> MediaItemsWithStartPosition(p.tracks.map { it.toMediaItem() }, 0, 0) }
            id.startsWith("song:") -> {
                graph.music.load()
                val songs = graph.music.currentSongs()
                val index = songs.indexOfFirst { it.mediaId == id.removePrefix("song:") }
                if (index < 0) null else MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, index, 0)
            }
            id.isEmpty() && item.requestMetadata.searchQuery != null -> playFromSearch(item.requestMetadata.searchQuery!!)
            else -> null
        }
    }

    /** "Hey Google, play The Lantern Path on TAPE." Books first, then playlists, albums, songs. */
    suspend fun playFromSearch(query: String): MediaItemsWithStartPosition? {
        val first = search(query).firstOrNull()
        if (first == null) {
            // Empty query ("play TAPE"): resume the last book, or shuffle everything.
            val last = graph.progress.lastBookId?.let { graph.books.find(it) }
            if (last != null) return bookQueue(last)
            graph.music.load()
            val songs = graph.music.currentSongs().shuffled()
            return if (songs.isEmpty()) null else MediaItemsWithStartPosition(songs.map { it.toMediaItem() }, 0, 0)
        }
        return resolve(first)
    }

    /** Results for the car's search screen; same order as [playFromSearch]. */
    suspend fun search(query: String): List<MediaItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        graph.music.load()
        fun hit(vararg s: String?) = s.any { it?.lowercase()?.contains(q) == true }
        val books = graph.books.books.value.filter { hit(it.title, it.author) }
            .map { playable("book:${it.id}", it.title, it.author, MediaMetadata.MEDIA_TYPE_AUDIO_BOOK) }
        val playlists = graph.playlists.all.value.filter { hit(it.name) }
            .map { playable("playlist:${it.id}", it.name, "Playlist", MediaMetadata.MEDIA_TYPE_PLAYLIST) }
        val albums = graph.music.currentAlbums().filter { hit(it.title, it.artist) }
            .map { playable("album:${it.key}", it.title, it.artist, MediaMetadata.MEDIA_TYPE_ALBUM) }
        val songs = graph.music.currentSongs().filter { hit(it.name, it.artist) }.take(50)
            .map { playable("song:${it.mediaId}", it.name, it.artist, MediaMetadata.MEDIA_TYPE_MUSIC) }
        return books + playlists + albums + songs
    }

    fun bookQueue(book: Book): MediaItemsWithStartPosition {
        val p = graph.progress.get(book.id)
        val resume = p != null && !p.finished
        val index = if (resume) p!!.chapter.coerceIn(0, book.chapters.lastIndex) else 0
        val position = if (resume) p!!.positionMs else 0L
        return MediaItemsWithStartPosition(book.mediaItems(), index, position)
    }

    private fun booksByRecent(): List<Book> {
        val books = graph.books.books.value
        return books.sortedByDescending { graph.progress.get(it.id)?.takeIf { p -> !p.finished }?.updatedAt ?: 0L }
    }

    private fun folder(id: String, title: String, type: Int, subtitle: String? = null) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .build(),
        )
        .build()

    // No artwork in the browse lists: phone album art and Drime covers aren't readable by
    // the car app. The now-playing screen still shows art (the session loads it itself).
    private fun playable(id: String, title: String, subtitle: String?, type: Int) = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(type)
                .build(),
        )
        .build()

    companion object {
        const val ROOT = "root"
        const val BOOKS = "tab:books"
        const val PLAYLISTS = "tab:playlists"
        const val ALBUMS = "tab:albums"
        const val SONGS = "tab:songs"
    }
}
