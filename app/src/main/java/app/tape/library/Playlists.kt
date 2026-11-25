package app.tape.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Playlist(val id: String, val name: String, val tracks: List<Track>, val updatedAt: Long) {
    val art get() = tracks.firstNotNullOfOrNull { it.artwork }
}

/** Your own playlists, mixing files from this phone and Drime. Stored as one JSON file. */
class Playlists(context: Context) {
    private val file = File(context.filesDir, "playlists.json")

    private val _all = MutableStateFlow(read())
    val all: StateFlow<List<Playlist>> = _all.asStateFlow()

    fun get(id: String): Playlist? = _all.value.firstOrNull { it.id == id }

    fun create(name: String, tracks: List<Track> = emptyList()): String {
        val p = Playlist(UUID.randomUUID().toString(), name.trim().ifEmpty { "New playlist" }, tracks, now())
        commit(_all.value + p)
        return p.id
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim().ifEmpty { it.name }) }

    fun delete(id: String) = commit(_all.value.filterNot { it.id == id })

    fun add(id: String, tracks: List<Track>) = update(id) { it.copy(tracks = it.tracks + tracks) }

    fun removeAt(id: String, index: Int) = update(id) { p ->
        p.copy(tracks = p.tracks.filterIndexed { i, _ -> i != index })
    }

    fun move(id: String, from: Int, to: Int) = update(id) { p ->
        if (from !in p.tracks.indices || to !in p.tracks.indices) return@update p
        val list = p.tracks.toMutableList()
        list.add(to, list.removeAt(from))
        p.copy(tracks = list)
    }

    private fun update(id: String, change: (Playlist) -> Playlist) =
        commit(_all.value.map { if (it.id == id) change(it).copy(updatedAt = now()) else it })

    private fun commit(list: List<Playlist>) {
        val sorted = list.sortedByDescending { it.updatedAt }
        _all.value = sorted
        val arr = JSONArray(
            sorted.map { p ->
                JSONObject().put("id", p.id).put("name", p.name).put("updated", p.updatedAt)
                    .put("tracks", JSONArray(p.tracks.map { it.toJson() }))
            },
        )
        // Small file; write synchronously so a crash right after can't lose a playlist.
        runCatching { file.writeText(arr.toString()) }
    }

    private fun read(): List<Playlist> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val t = o.getJSONArray("tracks")
                Playlist(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    tracks = (0 until t.length()).map { Track.fromJson(t.getJSONObject(it)) },
                    updatedAt = o.optLong("updated"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun now() = System.currentTimeMillis()
}
