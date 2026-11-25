package app.tape.playback

import android.app.Notification
import android.content.Context
import androidx.media3.common.util.Util
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import app.tape.R
import app.tape.graph
import app.tape.library.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** How much of a book, album or playlist is kept on the phone. */
sealed interface Kept {
    data object None : Kept
    data class Saving(val percent: Int) : Kept
    data object Partly : Kept
    data object All : Kept
    data object Failed : Kept
}

/**
 * "Keep on phone": Drime files downloaded in full into their own cache, which is never
 * evicted and is read before the normal streaming cache. Media3's DownloadManager does the
 * work in [TapeDownloadService], so downloads continue with the app closed.
 *
 * Download ids are file ids ("drime:<hash>"); chapters inside one .m4b share their file.
 */
class Downloads(
    private val context: Context,
    databaseProvider: DatabaseProvider,
    cache: Cache,
    upstream: DataSource.Factory,
) {
    val manager = DownloadManager(context, databaseProvider, cache, upstream, Executors.newFixedThreadPool(2)).apply {
        maxParallelDownloads = 2
    }
    val notifications = DownloadNotificationHelper(context, CHANNEL_ID)

    private val scope = MainScope()
    private var ticker: Job? = null

    /** file id → download state, kept current from the manager. */
    private val _state = MutableStateFlow<Map<String, Download>>(emptyMap())
    val state: StateFlow<Map<String, Download>> = _state.asStateFlow()

    init {
        manager.addListener(object : DownloadManager.Listener {
            override fun onInitialized(downloadManager: DownloadManager) = reload()
            override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                _state.value = _state.value + (download.request.id to download)
                watchProgress()
            }
            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                _state.value = _state.value - download.request.id
            }
        })
        reload()
    }

    fun isKept(fileId: String): Boolean = _state.value[fileId]?.state == Download.STATE_COMPLETED

    fun status(tracks: List<Track>): Kept = status(tracks.filter { it.isCloud }.map { Media.fileId(it.mediaId) })

    fun status(fileIds: Collection<String>): Kept {
        val ids = fileIds.toSet()
        if (ids.isEmpty()) return Kept.None
        val downloads = ids.mapNotNull { _state.value[it] }
        if (downloads.isEmpty()) return Kept.None
        if (downloads.size == ids.size && downloads.all { it.state == Download.STATE_COMPLETED }) return Kept.All
        val active = downloads.filter {
            it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING
        }
        if (active.isNotEmpty()) {
            val done = downloads.count { it.state == Download.STATE_COMPLETED }
            val partial = active.sumOf { it.percentDownloaded.coerceAtLeast(0f).toDouble() } / 100.0
            return Kept.Saving(((done + partial) / ids.size * 100).toInt().coerceIn(0, 99))
        }
        if (downloads.any { it.state == Download.STATE_FAILED }) return Kept.Failed
        return Kept.Partly
    }

    /** Files are given as (file id, uri, title) and deduplicated, so a chaptered .m4b downloads once. */
    fun keep(files: List<Triple<String, String, String>>) {
        files.distinctBy { it.first }.forEach { (id, uri, title) ->
            if (isKept(id)) return@forEach
            val request = DownloadRequest.Builder(id, android.net.Uri.parse(uri))
                .setData(Util.getUtf8Bytes(title))
                .build()
            DownloadService.sendAddDownload(context, TapeDownloadService::class.java, request, false)
        }
    }

    fun remove(fileIds: Collection<String>) {
        fileIds.toSet().forEach { DownloadService.sendRemoveDownload(context, TapeDownloadService::class.java, it, false) }
    }

    fun keepTracks(tracks: List<Track>) = keep(
        tracks.filter { it.isCloud }.map { Triple(Media.fileId(it.mediaId), it.uri.toString(), it.name) },
    )

    fun removeTracks(tracks: List<Track>) = remove(tracks.filter { it.isCloud }.map { Media.fileId(it.mediaId) })

    private fun reload() {
        scope.launch {
            val all = withContext(Dispatchers.IO) {
                buildMap {
                    manager.downloadIndex.getDownloads().use { c ->
                        while (c.moveToNext()) put(c.download.request.id, c.download)
                    }
                }
            }
            _state.value = all
            watchProgress()
        }
    }

    /** Percentages aren't pushed by the manager; poll them while something downloads. */
    private fun watchProgress() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                val current = manager.currentDownloads
                if (current.isEmpty()) break
                _state.value = _state.value + current.associateBy { it.request.id }
                delay(1_000)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "downloads"
    }
}

/** Runs downloads in the background with a progress notification. */
class TapeDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    Downloads.CHANNEL_ID,
    R.string.downloads_channel,
    0,
) {
    override fun getDownloadManager(): DownloadManager = graph.downloads.manager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification =
        graph.downloads.notifications.buildProgressNotification(
            this,
            R.drawable.ic_stat_download,
            null,
            "Keeping on phone",
            downloads,
            notMetRequirements,
        )

    private companion object {
        const val NOTIFICATION_ID = 2
    }
}
