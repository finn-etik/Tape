package app.tape.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A saved moment in an audiobook, with an optional note. */
data class Bookmark(
    val id: String,
    val chapter: Int,
    val positionMs: Long,
    val note: String,
    val createdAt: Long,
)

/** Bookmarks per book, kept in SharedPreferences as small JSON lists. */
class Bookmarks(context: Context) {
    private val prefs = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    /** Bumped on every change so screens can re-read. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    fun list(bookId: String): List<Bookmark> = runCatching {
        val arr = JSONArray(prefs.getString(bookId, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Bookmark(o.getString("id"), o.getInt("c"), o.getLong("p"), o.optString("n"), o.optLong("t"))
        }
    }.getOrDefault(emptyList()).sortedWith(compareBy({ it.chapter }, { it.positionMs }))

    fun add(bookId: String, chapter: Int, positionMs: Long, note: String): Bookmark {
        val b = Bookmark(UUID.randomUUID().toString(), chapter, positionMs, note.trim(), System.currentTimeMillis())
        write(bookId, list(bookId) + b)
        return b
    }

    fun updateNote(bookId: String, id: String, note: String) =
        write(bookId, list(bookId).map { if (it.id == id) it.copy(note = note.trim()) else it })

    fun delete(bookId: String, id: String) = write(bookId, list(bookId).filterNot { it.id == id })

    private fun write(bookId: String, list: List<Bookmark>) {
        val arr = JSONArray(
            list.map { JSONObject().put("id", it.id).put("c", it.chapter).put("p", it.positionMs).put("n", it.note).put("t", it.createdAt) },
        )
        prefs.edit().putString(bookId, arr.toString()).apply()
        _version.value++
    }
}
