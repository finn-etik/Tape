package app.tape.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** One place that knows how TAPE builds and reads MediaItems. */
object Media {
    private const val EXTRA_BOOK_ID = "tape.bookId"

    /**
     * @param title set it when we know better than the file's tags (songs from
     *   MediaStore, book chapters). Leave it null for loose cloud files so the
     *   embedded ID3/Vorbis title wins, with [displayTitle] as a fallback.
     */
    fun item(
        mediaId: String,
        uri: Uri,
        title: String? = null,
        displayTitle: String? = null,
        artist: String? = null,
        album: String? = null,
        artwork: Uri? = null,
        bookId: String? = null,
        track: Int? = null,
        /** For a chapter inside a longer file (e.g. an .m4b): where it starts and ends. */
        clipStartMs: Long = 0L,
        clipEndMs: Long = C.TIME_END_OF_SOURCE,
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(displayTitle ?: title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setArtworkUri(artwork)
            .setTrackNumber(track)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(
                if (bookId != null) MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER
                else MediaMetadata.MEDIA_TYPE_MUSIC,
            )
        if (bookId != null) metadata.setExtras(Bundle().apply { putString(EXTRA_BOOK_ID, bookId) })

        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(uri)
            // MediaController strips the uri when items cross into the session;
            // PlaybackService restores it from here.
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(metadata.build())
            .apply {
                if (clipStartMs > 0 || clipEndMs != C.TIME_END_OF_SOURCE) {
                    setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(clipStartMs)
                            .setEndPositionMs(clipEndMs)
                            .build(),
                    )
                }
            }
            .build()
    }

    /** "drime:abc#3" (chapter 3 inside a file) → "drime:abc": the file itself, e.g. for downloads. */
    fun fileId(mediaId: String): String = mediaId.substringBefore('#')

    fun bookId(item: MediaItem?): String? = item?.mediaMetadata?.extras?.getString(EXTRA_BOOK_ID)
}
