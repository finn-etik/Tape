package app.tape.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Everything in Music, from this phone and from Drime, as one list of albums and songs. */
class MusicCatalog(val local: LocalMusic, val drime: DrimeMusic, scope: CoroutineScope) {

    /** For the UI: updates whenever either source changes. */
    val albums: StateFlow<List<AlbumEntry>> = combine(local.albums, drime.albums, ::mergeAlbums)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val songs: StateFlow<List<Track>> = combine(local.songs, drime.albums, ::mergeSongs)
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** For Android Auto: computed now, so it's right even straight after [load]. */
    fun currentAlbums(): List<AlbumEntry> = mergeAlbums(local.albums.value, drime.albums.value)
    fun currentSongs(): List<Track> = mergeSongs(local.songs.value, drime.albums.value)
    fun album(key: String): AlbumEntry? = currentAlbums().firstOrNull { it.key == key }

    private fun mergeAlbums(a: List<AlbumEntry>, b: List<AlbumEntry>) =
        (a + b).sortedWith(compareBy(NaturalOrder) { it.title })

    private fun mergeSongs(s: List<Track>, b: List<AlbumEntry>) =
        (s + b.flatMap { it.tracks }).distinctBy { it.mediaId }.sortedWith(compareBy(NaturalOrder) { it.name })

    /** Loads whatever isn't loaded yet; safe to call often (e.g. from Android Auto). */
    suspend fun load() {
        if (local.songs.value.isEmpty()) local.refresh()
        drime.ensureScanned()
    }
}
