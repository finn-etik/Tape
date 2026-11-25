package app.tape.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.tape.library.Book
import app.tape.library.ProgressStore
import app.tape.library.Track
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/** Everything the UI needs to draw the player. */
data class NowPlaying(
    val hasMedia: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    /** Album or book title. */
    val collection: String = "",
    val artwork: Any? = null, // Uri or ByteArray; Coil takes either
    val bookId: String? = null,
    val index: Int = 0,
    val count: Int = 0,
    val speed: Float = 1f,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
) {
    val isBook: Boolean get() = bookId != null
}

/**
 * The UI's handle on PlaybackService, via a MediaController. Connected while
 * MainActivity is started; actions issued before the connection is ready wait for it.
 */
class PlayerConnection(private val context: Context, private val progress: ProgressStore) {

    private val scope = MainScope()
    private var future: ListenableFuture<MediaController>? = null
    private var pausedAt = 0L

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    private val controller: MediaController?
        get() = future?.takeIf { it.isDone && !it.isCancelled }?.let { runCatching { it.get() }.getOrNull() }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            controller?.let {
                it.addListener(listener)
                publish(it)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
    }

    private suspend fun await(): MediaController {
        connect()
        return future!!.await()
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    private fun publish(p: Player) {
        val item = p.currentMediaItem
        val md = p.mediaMetadata
        val bookId = Media.bookId(item)
        val title = (md.title ?: md.displayTitle ?: item?.mediaMetadata?.displayTitle)?.toString().orEmpty()
        val subtitle = if (bookId != null) {
            md.albumTitle?.toString().orEmpty()
        } else {
            listOfNotNull(md.artist, md.albumTitle).joinToString(" · ")
        }
        _state.value = NowPlaying(
            hasMedia = item != null,
            isPlaying = p.isPlaying,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            title = title,
            subtitle = subtitle,
            collection = md.albumTitle?.toString().orEmpty(),
            artwork = md.artworkData ?: md.artworkUri,
            bookId = bookId,
            index = p.currentMediaItemIndex,
            count = p.mediaItemCount,
            speed = p.playbackParameters.speed,
            durationMs = p.duration.coerceAtLeast(0),
            shuffle = p.shuffleModeEnabled,
            repeatMode = p.repeatMode,
            error = p.playerError?.let { friendlyError(it.errorCodeName) },
        )
    }

    // ---- Reading ---------------------------------------------------------------------

    fun position(): Long = controller?.currentPosition ?: 0L

    fun queueTitles(): List<String> {
        val c = controller ?: return emptyList()
        return (0 until c.mediaItemCount).map { i ->
            val md = c.getMediaItemAt(i).mediaMetadata
            (md.title ?: md.displayTitle)?.toString() ?: "Track ${i + 1}"
        }
    }

    // ---- Starting things ---------------------------------------------------------------

    fun play(items: List<MediaItem>, startIndex: Int = 0, startPositionMs: Long = 0L, shuffle: Boolean = false) {
        if (items.isEmpty()) return
        scope.launch {
            val c = await()
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(items, if (shuffle) (items.indices).random() else startIndex, startPositionMs)
            c.prepare()
            c.play()
        }
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) =
        play(tracks.map { it.toMediaItem() }, startIndex, 0L, shuffle)

    /** The song playing now, as a [Track] that can go into a playlist. Null for audiobooks. */
    fun currentTrack(): Track? {
        val c = controller ?: return null
        val item = c.currentMediaItem ?: return null
        if (Media.bookId(item) != null) return null
        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri ?: return null
        val own = item.mediaMetadata
        val live = c.mediaMetadata // includes tags read from the file
        return Track(
            mediaId = item.mediaId,
            uri = uri,
            title = own.title?.toString(),
            displayTitle = (live.title ?: own.displayTitle ?: live.displayTitle)?.toString() ?: item.mediaId,
            artist = (own.artist ?: live.artist)?.toString(),
            album = (own.albumTitle ?: live.albumTitle)?.toString(),
            artwork = own.artworkUri,
        )
    }

    fun playBook(book: Book, chapter: Int? = null) {
        val p = progress.get(book.id)
        val resume = p != null && !p.finished
        val index = chapter ?: if (resume) p.chapter.coerceIn(0, book.chapters.lastIndex) else 0
        val position = if (chapter == null && resume) p.positionMs else 0L
        play(book.mediaItems(), index, position)
    }

    // ---- Transport ---------------------------------------------------------------------

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
            pausedAt = System.currentTimeMillis()
            return
        }
        // Coming back to a book after a break? Step back a little to find the thread again.
        if (_state.value.isBook && pausedAt > 0 && System.currentTimeMillis() - pausedAt > 2 * 60_000) {
            c.seekTo((c.currentPosition - 10_000).coerceAtLeast(0))
        }
        when (c.playbackState) {
            Player.STATE_IDLE -> c.prepare()
            Player.STATE_ENDED -> c.seekTo(0, 0)
        }
        c.play()
    }

    fun seekTo(ms: Long) = controller?.seekTo(ms)
    fun skipBack() = controller?.seekBack()
    fun skipForward() = controller?.seekForward()
    fun next() = controller?.seekToNextMediaItem()
    fun previous() = controller?.seekToPrevious()
    fun jumpTo(index: Int) = controller?.seekTo(index, 0L)
    fun setSpeed(speed: Float) = controller?.setPlaybackSpeed(speed)

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    private fun friendlyError(code: String): String = when {
        code.contains("NETWORK") || code.contains("TIMEOUT") -> "Can't reach Drime right now."
        code.contains("BAD_HTTP_STATUS") -> "Drime refused this file. Try connecting again."
        code.contains("FILE_NOT_FOUND") || code.contains("NO_PERMISSION") -> "This file can't be opened any more."
        code.contains("DECODING") || code.contains("PARSING") -> "This format can't be played."
        else -> "Playback stopped unexpectedly."
    }

    companion object {
        val SPEEDS = listOf(0.8f, 1f, 1.1f, 1.2f, 1.3f, 1.5f, 1.75f, 2f)
    }
}

