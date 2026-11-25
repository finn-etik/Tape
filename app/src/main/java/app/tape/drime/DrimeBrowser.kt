package app.tape.drime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Folder navigation state for the Drime tab. Survives tab switches. */
class DrimeBrowser(private val api: DrimeApi, private val scope: CoroutineScope) {

    data class Crumb(val hash: String?, val name: String)

    data class State(
        val path: List<Crumb> = listOf(Crumb(null, "Drime")),
        val entries: List<DrimeEntry> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
        val loaded: Boolean = false,
    ) {
        val here: Crumb get() = path.last()
        val folders: List<DrimeEntry> get() = entries.filter { it.isFolder }
        val tracks: List<DrimeEntry> get() = entries.filter { it.isAudio }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    fun open(folder: DrimeEntry) = navigate(_state.value.path + Crumb(folder.hash, folder.name))

    fun goTo(depth: Int) = navigate(_state.value.path.take(depth + 1))

    fun up(): Boolean {
        val path = _state.value.path
        if (path.size <= 1) return false
        navigate(path.dropLast(1))
        return true
    }

    fun refresh() = navigate(_state.value.path)

    fun reset() {
        job?.cancel()
        _state.value = State()
    }

    private fun navigate(path: List<Crumb>) {
        job?.cancel()
        _state.update { it.copy(path = path, loading = true, error = null, entries = if (it.path == path) it.entries else emptyList()) }
        job = scope.launch {
            try {
                val listing = api.list(path.last().hash)
                _state.update { it.copy(entries = listing.entries, loading = false, loaded = true) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(loading = false, error = e.message ?: "Couldn't open this folder.") }
            }
        }
    }
}
