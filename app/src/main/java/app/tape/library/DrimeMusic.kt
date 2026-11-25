package app.tape.library

import android.content.Context
import androidx.core.net.toUri
import app.tape.drime.DrimeApi
import app.tape.drime.DrimeEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A Drime folder added to Music. */
data class MusicShelf(val hash: String, val name: String) {
    val key: String get() = "drime:$hash"
}

/**
 * Music from Drime. Each added folder is searched two levels deep, which covers both
 * "Music/Album" and "Music/Artist/Album": every folder that holds audio becomes an album.
 * Results are cached, so the Music tab and Android Auto show them instantly and offline.
 */
class DrimeMusic(context: Context, private val drime: DrimeApi) {

    private val prefs = context.getSharedPreferences("drime_music", Context.MODE_PRIVATE)
    private val cacheFile = File(context.filesDir, "drime_music.json")

    private val _shelves = MutableStateFlow(readShelves())
    val shelves: StateFlow<List<MusicShelf>> = _shelves.asStateFlow()

    private val _albums = MutableStateFlow(readCache())
    val albums: StateFlow<List<AlbumEntry>> = _albums.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var scannedThisSession = false

    suspend fun add(hash: String, name: String) {
        _shelves.value = _shelves.value.filterNot { it.hash == hash } + MusicShelf(hash, name)
        writeShelves()
        rescan()
    }

    suspend fun remove(hash: String) {
        _shelves.value = _shelves.value.filterNot { it.hash == hash }
        writeShelves()
        _albums.value = _albums.value.filterNot { it.source == "drime:$hash" }
        withContext(Dispatchers.IO) { writeCache(_albums.value) }
    }

    suspend fun ensureScanned() {
        if (!scannedThisSession) rescan()
    }

    suspend fun rescan() {
        if (_scanning.value) return
        scannedThisSession = true
        _scanning.value = true
        try {
            val previous = _albums.value.groupBy { it.source }
            val found = mutableListOf<AlbumEntry>()
            for (shelf in _shelves.value) {
                // Offline or a hiccup: keep what we knew about this folder.
                found += runCatching { scanShelf(shelf) }.getOrElse { previous[shelf.key].orEmpty() }
            }
            val albums = found.distinctBy { it.key }.sortedWith(compareBy(NaturalOrder) { it.title })
            _albums.value = albums
            withContext(Dispatchers.IO) { writeCache(albums) }
        } finally {
            _scanning.value = false
        }
    }

    private suspend fun scanShelf(shelf: MusicShelf): List<AlbumEntry> {
        val gate = Semaphore(4)
        suspend fun visit(hash: String, name: String, parent: String?, depth: Int): List<AlbumEntry> {
            val entries = gate.withPermit { drime.list(hash).entries }
            val here = albumOf(hash, name, parent, entries, shelf.key)
            if (depth >= 2) return listOfNotNull(here)
            val below = coroutineScope {
                entries.filter { it.isFolder }
                    .map { sub -> async { visit(sub.hash, sub.name, name, depth + 1) } }
                    .awaitAll().flatten()
            }
            return listOfNotNull(here) + below
        }
        return visit(shelf.hash, shelf.name, null, 0)
    }

    private fun albumOf(hash: String, name: String, parent: String?, entries: List<DrimeEntry>, source: String): AlbumEntry? {
        val audio = entries.filter { it.isAudio }.sortedWith(compareBy(NaturalOrder) { it.name })
        if (audio.isEmpty()) return null
        val (title, artistFromName) = splitAuthor(name)
        val images = entries.filter { it.isImage }
        val cover = (images.firstOrNull { it.name.substringBeforeLast('.').lowercase() in COVER_NAMES } ?: images.firstOrNull())
            ?.let { drime.downloadUrl(it.hash).toUri() }
        return AlbumEntry(
            key = "drime:$hash",
            title = title,
            artist = artistFromName ?: parent ?: "Drime",
            art = cover,
            source = source,
            tracks = audio.map { e ->
                Track(
                    mediaId = "drime:${e.hash}",
                    uri = drime.downloadUrl(e.hash).toUri(),
                    title = null,
                    displayTitle = prettyName(e.name),
                    artist = artistFromName ?: parent,
                    album = title,
                    artwork = cover,
                )
            },
        )
    }

    private fun readShelves(): List<MusicShelf> = runCatching {
        val arr = JSONArray(prefs.getString(KEY_SHELVES, "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it).let { o -> MusicShelf(o.getString("hash"), o.getString("name")) } }
    }.getOrDefault(emptyList())

    private fun writeShelves() {
        val arr = JSONArray(_shelves.value.map { JSONObject().put("hash", it.hash).put("name", it.name) })
        prefs.edit().putString(KEY_SHELVES, arr.toString()).apply()
    }

    private fun readCache(): List<AlbumEntry> {
        if (!cacheFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(cacheFile.readText())
            (0 until arr.length()).map { AlbumEntry.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun writeCache(albums: List<AlbumEntry>) {
        cacheFile.writeText(JSONArray(albums.map { it.toJson() }).toString())
    }

    private companion object {
        const val KEY_SHELVES = "shelves"
        val COVER_NAMES = setOf("cover", "folder", "front", "album", "artwork")
    }
}
