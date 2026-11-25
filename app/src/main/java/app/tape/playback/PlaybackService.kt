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
import android.os.Bundle
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import app.tape.MainActivity
import app.tape.graph
import app.tape.library.Progress
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Owns the player. Gives TAPE a media notification, lock-screen and headset
 * controls, Android Auto browsing (via [LibraryTree]), and keeps audiobook
 * positions saved while it plays.
 */
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private lateinit var tree: LibraryTree
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
        tree = LibraryTree(graph)

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

        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
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

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

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

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        // ---- Browsing (Android Auto and other media browsers) -----------------------

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(tree.root(), rootParams()))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val children = tree.children(parentId)
                ?: return@future LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            LibraryResult.ofItemList(paged(children, page, pageSize), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            tree.item(mediaId)?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> = scope.future {
            session.notifySearchResultChanged(browser, query, tree.search(query).size, params)
            LibraryResult.ofVoid()
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            LibraryResult.ofItemList(paged(tree.search(query), page, pageSize), params)
        }

        // ---- Playing ------------------------------------------------------------------

        /**
         * A tap in the car sends one id ("book:…", "album:…"); expand it to the whole
         * queue. Items from TAPE's own UI just get their uri back (controllers strip it).
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            mediaItems.singleOrNull()?.let { tree.resolve(it) }?.let { return@future it }
            MediaSession.MediaItemsWithStartPosition(mediaItems.map(::withUri), startIndex, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.flatMap { item ->
                if (item.requestMetadata.mediaUri != null || item.localConfiguration != null) listOf(withUri(item))
                else tree.resolve(item)?.mediaItems ?: emptyList()
            }.toMutableList()
        }

        /** Press play on a headset, in the system media controls or in the car after TAPE was closed. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val book = graph.progress.lastBookId?.let { graph.books.find(it) }
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            return Futures.immediateFuture(tree.bookQueue(book))
        }
    }

    private fun withUri(item: MediaItem): MediaItem {
        val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
        return if (uri == null || item.localConfiguration != null) item else item.buildUpon().setUri(uri).build()
    }

    private fun <T> paged(list: List<T>, page: Int, pageSize: Int): ImmutableList<T> {
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return ImmutableList.copyOf(list)
        return ImmutableList.copyOf(list.drop(page * pageSize).take(pageSize))
    }

    /** Tells Android Auto TAPE supports search and prefers plain lists. */
    private fun rootParams(): LibraryParams = LibraryParams.Builder()
        .setExtras(
            Bundle().apply {
                putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
                putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
                putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT", 1)
            },
        )
        .build()
}
