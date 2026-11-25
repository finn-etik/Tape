package app.tape.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import app.tape.playback.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val track: Int,
) {
    val uri: Uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val artUri: Uri get() = albumArt(albumId)

    fun toMediaItem(): MediaItem = Media.item(
        mediaId = "local:$id",
        uri = uri,
        title = title,
        artist = artist,
        album = album,
        artwork = artUri,
    )
}

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val songs: List<Song>,
) {
    val artUri: Uri get() = albumArt(id)
    val durationMs: Long get() = songs.sumOf { it.durationMs }
}

private val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")
private fun albumArt(albumId: Long): Uri = ContentUris.withAppendedId(ALBUM_ART, albumId)

/** Music on the device, read from MediaStore. */
class LocalMusic(private val context: Context) {

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
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
        val list = mutableListOf<Song>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                list += Song(
                    id = c.getLong(0),
                    title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Untitled",
                    artist = c.getString(2)?.takeUnless { it.isBlank() || it == "<unknown>" } ?: "Unknown artist",
                    album = c.getString(3)?.takeIf { it.isNotBlank() } ?: "Unknown album",
                    albumId = c.getLong(4),
                    durationMs = c.getLong(5),
                    track = c.getInt(6) % 1000, // MediaStore packs disc * 1000 + track
                )
            }
        }
        val songs = list.sortedWith(compareBy(NaturalOrder) { it.title })
        val albums = list.groupBy { it.albumId }.map { (id, tracks) ->
            val ordered = tracks.sortedWith(compareBy<Song> { it.track }.thenBy(NaturalOrder) { it.title })
            Album(
                id = id,
                title = ordered.first().album,
                artist = ordered.map { it.artist }.distinct().singleOrNull() ?: "Various artists",
                songs = ordered,
            )
        }.sortedWith(compareBy(NaturalOrder) { it.title })

        _songs.value = songs
        _albums.value = albums
    }

    fun album(id: Long): Album? = _albums.value.firstOrNull { it.id == id }
}
