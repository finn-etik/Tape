package app.tape

import android.app.Application
import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import app.tape.drime.DrimeApi
import app.tape.drime.DrimeAuthInterceptor
import app.tape.drime.DrimeBrowser
import app.tape.drime.DrimeLogin
import app.tape.drime.DrimeSession
import app.tape.drime.TokenStore
import app.tape.library.BookLibrary
import app.tape.library.DrimeMusic
import app.tape.library.MusicCatalog
import app.tape.library.Playlists
import app.tape.library.LocalMusic
import app.tape.library.ProgressStore
import app.tape.playback.PlayerConnection
import app.tape.playback.SleepTimer
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class TapeApp : Application(), SingletonImageLoader.Factory {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    /** Coil uses the Drime-aware client, so cover art stored in Drime just loads. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.drimeHttp })) }
            .crossfade(true)
            .build()
}

val Context.graph: AppGraph get() = (applicationContext as TapeApp).graph

/** Hand-wired dependencies. Small enough that a DI framework would be more code, not less. */
class AppGraph(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val plainHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val tokens = TokenStore(app)
    private val session = DrimeSession(tokens, plainHttp)
    val drimeHttp: OkHttpClient = plainHttp.newBuilder().addInterceptor(DrimeAuthInterceptor(session)).build()
    val drime = DrimeApi(drimeHttp)
    val drimeLogin = DrimeLogin(tokens, drime, plainHttp)
    val drimeBrowser = DrimeBrowser(drime, scope)

    val localMusic = LocalMusic(app)
    val drimeMusic = DrimeMusic(app, drime)
    val music = MusicCatalog(localMusic, drimeMusic, scope)
    val playlists = Playlists(app)
    val progress = ProgressStore(app)
    val books = BookLibrary(app, drime)
    val sleepTimer = SleepTimer()
    val player = PlayerConnection(app, progress)

    /** Streams from Drime are cached on disk (LRU, 1 GB) so replays and seeks are instant. */
    private val mediaCache: SimpleCache by lazy {
        SimpleCache(
            File(app.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(1L shl 30),
            StandaloneDatabaseProvider(app),
        )
    }

    /** Local files go through DefaultDataSource directly; http(s) through cache → OkHttp (with auth). */
    val dataSourceFactory: DataSource.Factory by lazy {
        DefaultDataSource.Factory(
            app,
            CacheDataSource.Factory()
                .setCache(mediaCache)
                .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(drimeHttp))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR),
        )
    }
}
