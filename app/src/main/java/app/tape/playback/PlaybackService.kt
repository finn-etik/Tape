package app.tape.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import app.tape.MainActivity
import app.tape.graph
import app.tape.library.Progress
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Owns the player. Gives TAPE a media notification, lock-screen and headset
 * controls, and keeps audiobook positions saved while it plays.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val scope = MainScope()
    private var currentBookId: String? = null

    override fun onCreate() {
        super.onCreate()

        // Many audiobook MP3s have no seek index; constant-bitrate seeking makes them seekable.
        val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(graph.dataSourceFactory, extractors))
            .setAudioAttributes(attributes(speech = false), /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true) // pause when headphones come out
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player.addListener(listener)
        graph.sleepTimer.player = player

        // Artwork for the notification can be a Drime URL, so load it with our authenticated stack.
        val bitmapLoader = CacheBitmapLoader(
            DataSourceBitmapLoader(
                MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor()),
                DefaultDataSource.Factory(this, OkHttpDataSource.Factory(graph.drimeHttp)),
            ),
        )

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        session = MediaSession.Builder(this, player)
            .setCallback(SessionCallback())
            .setBitmapLoader(bitmapLoader)
            .setSessionActivity(openApp)
            .build()

        scope.launch {
            while (isActive) {
                delay(5_000)
                if (player.isPlaying) saveProgress()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf() // swiped away while paused: go quietly
        }
    }

    override fun onDestroy() {
        saveProgress()
        graph.sleepTimer.player = null
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val bookId = Media.bookId(mediaItem)
            if (bookId != currentBookId) {
                val wasBook = currentBookId != null
                currentBookId = bookId
                if (wasBook != (bookId != null)) player.setAudioAttributes(attributes(speech = bookId != null), true)
                // Every book remembers its own speed; music always plays at 1×.
                val speed = bookId?.let { graph.progress.get(it)?.speed } ?: 1f
                if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
            }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) graph.sleepTimer.onAutoAdvance()
            saveProgress()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveProgress()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            saveProgress()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                currentBookId?.let { graph.progress.markFinished(it) }
                graph.sleepTimer.cancel()
            }
        }
    }

    private fun saveProgress() {
        val player = session?.player ?: return
        val bookId = Media.bookId(player.currentMediaItem) ?: return
        if (player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) return
        graph.progress.save(
            bookId,
            Progress(
                chapter = player.currentMediaItemIndex,
                positionMs = player.currentPosition,
                chapterDurationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L,
                speed = player.playbackParameters.speed,
                updatedAt = System.currentTimeMillis(),
                finished = false,
            ),
        )
    }

    private fun attributes(speech: Boolean) = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(if (speech) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private inner class SessionCallback : MediaSession.Callback {

        /** Controllers send items without their uri; put it back. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(
            mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                if (uri == null) item else item.buildUpon().setUri(uri).build()
            }.toMutableList(),
        )

        /** Press play on a headset or in the system media controls after TAPE was closed. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val book = graph.progress.lastBookId?.let { graph.books.find(it) }
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            val p = graph.progress.get(book.id)
            val index = if (p == null || p.finished) 0 else p.chapter.coerceIn(0, book.chapters.lastIndex)
            val position = if (p == null || p.finished) 0L else p.positionMs
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(book.mediaItems(), index, position),
            )
        }
    }
}
