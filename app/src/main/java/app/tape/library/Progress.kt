package app.tape.library

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Where you are in a book. Saved every few seconds while it plays. */
data class Progress(
    val chapter: Int,
    val positionMs: Long,
    val chapterDurationMs: Long,
    val speed: Float,
    val updatedAt: Long,
    val finished: Boolean,
) {
    /** 0..1 across the whole book, estimated from chapters since cloud files have no known length. */
    fun fraction(chapterCount: Int): Float {
        if (finished) return 1f
        if (chapterCount <= 0) return 0f
        val within = if (chapterDurationMs > 0) positionMs.toFloat() / chapterDurationMs else 0f
        return ((chapter + within.coerceIn(0f, 1f)) / chapterCount).coerceIn(0f, 1f)
    }
}

class ProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("progress", Context.MODE_PRIVATE)

    /** Bumped on every save so the UI can recompose progress bars. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    val lastBookId: String? get() = prefs.getString(KEY_LAST, null)

    fun get(bookId: String): Progress? {
        val raw = prefs.getString(KEY_PREFIX + bookId, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Progress(
                chapter = o.optInt("c"),
                positionMs = o.optLong("p"),
                chapterDurationMs = o.optLong("d"),
                speed = o.optDouble("s", 1.0).toFloat(),
                updatedAt = o.optLong("u"),
                finished = o.optBoolean("f"),
            )
        }.getOrNull()
    }

    fun save(bookId: String, p: Progress) {
        val o = JSONObject()
            .put("c", p.chapter)
            .put("p", p.positionMs)
            .put("d", p.chapterDurationMs)
            .put("s", p.speed.toDouble())
            .put("u", p.updatedAt)
            .put("f", p.finished)
        prefs.edit().putString(KEY_PREFIX + bookId, o.toString()).putString(KEY_LAST, bookId).apply()
        _version.value++
    }

    fun reset(bookId: String) {
        prefs.edit().remove(KEY_PREFIX + bookId).apply()
        _version.value++
    }

    fun markFinished(bookId: String) {
        val old = get(bookId)
        save(
            bookId,
            Progress(0, 0, 0, old?.speed ?: 1f, System.currentTimeMillis(), finished = true),
        )
    }

    private companion object {
        const val KEY_PREFIX = "book:"
        const val KEY_LAST = "last"
    }
}
