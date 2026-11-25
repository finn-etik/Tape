package app.tape.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Fixtures were made with ffmpeg from a 15 s tone and three chapters
 * (0 s "Prologue", 4 s "Kapitel två – Ö", 9.5 s "The End"):
 *  - both.m4b         Nero chpl + QuickTime chapter track, index (moov) at the start
 *  - qt_only.m4b      chpl removed, so only the QuickTime chapter track is left
 *  - moov_at_end.m4a  same, but the index sits after the audio, as in many downloads
 */
class Mp4ChaptersTest {

    private class BytesSource(private val bytes: ByteArray) : ByteSource {
        override val size: Long get() = bytes.size.toLong()
        override fun read(offset: Long, length: Int): ByteArray =
            bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
    }

    private fun load(name: String): ByteSource =
        BytesSource(javaClass.getResourceAsStream("/chapters/$name")!!.use { it.readBytes() })

    private val expected = listOf(
        FileChapter("Prologue", 0),
        FileChapter("Kapitel två – Ö", 4_000),
        FileChapter("The End", 9_500),
    )

    @Test fun neroAndQuickTime() = check("both.m4b")

    @Test fun quickTimeOnly() = check("qt_only.m4b")

    @Test fun indexAtTheEnd() = check("moov_at_end.m4a")

    private fun check(name: String) {
        val chapters = Mp4Chapters.read(load(name))
        assertNotNull("no chapters found in $name", chapters)
        assertEquals(expected.map { it.title }, chapters!!.map { it.title })
        // Allow a few ms of rounding between time bases.
        expected.zip(chapters).forEach { (e, a) -> assertEquals(e.startMs.toDouble(), a.startMs.toDouble(), 30.0) }
    }
}
