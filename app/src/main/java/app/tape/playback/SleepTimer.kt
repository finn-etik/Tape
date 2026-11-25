package app.tape.playback

import androidx.media3.common.Player
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pauses playback after a while, fading out over the last few seconds instead of
 * cutting off mid-sentence. Lives for the whole process; PlaybackService attaches
 * its player, the UI only sets the mode.
 */
class SleepTimer {
    sealed interface Mode {
        data object Off : Mode
        data class At(val endsAt: Long) : Mode
        data object EndOfChapter : Mode
    }

    private val scope = MainScope()
    private var job: Job? = null

    private val _mode = MutableStateFlow<Mode>(Mode.Off)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    var player: Player? = null

    fun start(minutes: Int) {
        cancel()
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _mode.value = Mode.At(endsAt)
        job = scope.launch {
            delay((endsAt - System.currentTimeMillis() - FADE_MS).coerceAtLeast(0))
            fadeOutAndPause()
        }
    }

    fun endOfChapter() {
        cancel()
        _mode.value = Mode.EndOfChapter
    }

    fun cancel() {
        job?.cancel()
        job = null
        player?.volume = 1f
        _mode.value = Mode.Off
    }

    /** PlaybackService calls this when one chapter rolls into the next. */
    fun onAutoAdvance() {
        if (_mode.value == Mode.EndOfChapter) {
            player?.pause()
            _mode.value = Mode.Off
        }
    }

    private suspend fun fadeOutAndPause() {
        val p = player
        if (p != null && p.isPlaying) {
            val steps = 30
            for (i in 1..steps) {
                p.volume = 1f - i.toFloat() / steps
                delay(FADE_MS / steps)
            }
            p.pause()
            p.volume = 1f
        }
        job = null
        _mode.value = Mode.Off
    }

    private companion object {
        const val FADE_MS = 12_000L
    }
}
