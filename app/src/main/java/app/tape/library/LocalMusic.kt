package app.tape.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")

/** Music on the device, read from MediaStore. */
class LocalMusic(private val context: Context) {

    private class Row(
        val id: Long, val title: String, val artist: String, val album: String,
        val albumId: Long, val durationMs: Long, val trackNo: Int,
    ) {
        fun toTrack() = Track(
            mediaId = "local:$id",
            uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
            title = title,
            displayTitle = title,
            artist = artist,
            album = album,
            artwork = ContentUris.withAppendedId(ALBUM_ART, albumId),
            durationMs = durationMs,
        )
    }

    private val _songs = MutableStateFlow<List<Track>>(emptyList())
    val songs: StateFlow<List<Track>> = _songs.asStateFlow()

    private val _albums = MutableStateFlow<List<AlbumEntry>>(emptyList())
    val albums: StateFlow<List<AlbumEntry>> = _albums.asStateFlow()

    val permission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!hasPermission) return@withContext
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK,
        )
        // Audiobooks have their own tab, so keep them out of Music where Android knows.
        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                append(" AND ${MediaStore.Audio.Media.IS_AUDIOBOOK} = 0")
            }
        }
        val rows = mutableListOf<Row>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    rows += Row(
                        id = c.getLong(0),
                        title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Untitled",
                        artist = c.getString(2)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown artist",
                        album = c.getString(3)?.takeIf { it.isNotBlank() } ?: "Unknown album",
                        albumId = c.getLong(4),
                        durationMs = c.getLong(5),
                        trackNo = c.getInt(6) % 1000, // MediaStore packs disc * 1000 + track
                    )
                }
            }
        }

        _songs.value = rows.map { it.toTrack() }.sortedWith(compareBy(NaturalOrder) { it.name })
        _albums.value = rows.groupBy { it.albumId }.map { (id, group) ->
            val ordered = group.sortedWith(compareBy<Row> { it.trackNo }.thenBy(NaturalOrder) { it.title })
            AlbumEntry(
                key = "local:$id",
                title = ordered.first().album,
                artist = ordered.map { it.artist }.distinct().singleOrNull() ?: "Various artists",
                art = ContentUris.withAppendedId(ALBUM_ART, id),
                tracks = ordered.map { it.toTrack() },
            )
        }.sortedWith(compareBy(NaturalOrder) { it.title })
    }
}
