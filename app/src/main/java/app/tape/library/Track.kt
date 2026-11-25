package app.tape.library

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import app.tape.playback.Media
import org.json.JSONObject

/**
 * One playable music file, wherever it lives (this phone or Drime). Everything a
 * playlist needs to play it later is in here, so playlists work without rescanning.
 */
data class Track(
    val mediaId: String,
    val uri: Uri,
    /** Known title (MediaStore). Null for cloud files, so their embedded tags win. */
    val title: String?,
    /** What to show before tags are read: the file name, prettified. */
    val displayTitle: String,
    val artist: String?,
    val album: String?,
    val artwork: Uri?,
    val durationMs: Long = 0,
) {
    val name: String get() = title ?: displayTitle
    val isCloud: Boolean get() = mediaId.startsWith("drime:")

    fun toMediaItem(): MediaItem = Media.item(
        mediaId = mediaId,
        uri = uri,
        title = title,
        displayTitle = displayTitle,
        artist = artist,
        album = album,
        artwork = artwork,
    )

    fun toJson(): JSONObject = JSONObject()
        .put("id", mediaId)
        .put("uri", uri.toString())
        .put("title", title ?: "")
        .put("display", displayTitle)
        .put("artist", artist ?: "")
        .put("album", album ?: "")
        .put("art", artwork?.toString() ?: "")
        .put("ms", durationMs)

    companion object {
        fun fromJson(o: JSONObject) = Track(
            mediaId = o.getString("id"),
            uri = o.getString("uri").toUri(),
            title = o.optString("title").ifEmpty { null },
            displayTitle = o.optString("display").ifEmpty { o.getString("id") },
            artist = o.optString("artist").ifEmpty { null },
            album = o.optString("album").ifEmpty { null },
            artwork = o.optString("art").ifEmpty { null }?.toUri(),
            durationMs = o.optLong("ms"),
        )
    }
}

/** An album in the Music tab: a MediaStore album or a Drime folder. */
data class AlbumEntry(
    /** "local:<albumId>" or "drime:<folderHash>" */
    val key: String,
    val title: String,
    val artist: String,
    val art: Uri?,
    val tracks: List<Track>,
    /** For Drime albums: which Music shelf they came from. */
    val source: String = "local",
) {
    val isCloud: Boolean get() = key.startsWith("drime:")
    val durationMs: Long get() = tracks.sumOf { it.durationMs }

    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("title", title)
        .put("artist", artist)
        .put("art", art?.toString() ?: "")
        .put("source", source)
        .put("tracks", org.json.JSONArray(tracks.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): AlbumEntry {
            val arr = o.getJSONArray("tracks")
            return AlbumEntry(
                key = o.getString("key"),
                title = o.getString("title"),
                artist = o.optString("artist"),
                art = o.optString("art").ifEmpty { null }?.toUri(),
                tracks = (0 until arr.length()).map { Track.fromJson(arr.getJSONObject(it)) },
                source = o.optString("source", "local"),
            )
        }
    }
}
