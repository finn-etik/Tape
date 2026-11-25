package app.tape.library

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

/** A chapter mark inside an MP4/M4B file. */
data class FileChapter(val title: String, val startMs: Long)

/** Random access to a file's bytes, local or over HTTP. */
interface ByteSource : Closeable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
    override fun close() {}
}

class LocalByteSource(context: Context, uri: Uri) : ByteSource {
    private val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
        ?: throw IOException("Can't open $uri")
    private val channel = FileInputStream(pfd.fileDescriptor).channel
    override val size: Long = channel.size()

    override fun read(offset: Long, length: Int): ByteArray {
        val buf = ByteBuffer.allocate(length)
        var pos = offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n <= 0) break
            pos += n
        }
        return buf.array().copyOf(buf.position())
    }

    override fun close() {
        channel.close()
        pfd.close()
    }
}

/** Reads with HTTP Range requests, so only the few bytes needed are downloaded. */
class HttpByteSource(private val http: OkHttpClient, private val url: String) : ByteSource {
    override val size: Long by lazy { probeSize() }

    override fun read(offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val request = Request.Builder().url(url).header("Range", "bytes=$offset-${offset + length - 1}").build()
        http.newCall(request).execute().use { response ->
            // 200 would mean the server ignored Range and is sending the whole file: give up.
            if (response.code != 206) throw IOException("No range support (${response.code})")
            return response.body.bytes()
        }
    }

    private fun probeSize(): Long {
        val request = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        http.newCall(request).execute().use { response ->
            if (response.code != 206) throw IOException("No range support (${response.code})")
            // Content-Range: bytes 0-0/123456
            return response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                ?: throw IOException("No Content-Range")
        }
    }
}

/**
 * Finds the chapter list in an MP4/M4B file. Two formats exist in the wild, and tools
 * often write both:
 *  - Nero chapters: a `chpl` box in moov/udta, start times in 100 ns units.
 *  - QuickTime chapters: a text track that the audio track points to via tref/chap;
 *    each text sample is one chapter title, its timing comes from the sample table.
 * Returns null when there are no (or fewer than two) chapters.
 */
object Mp4Chapters {
    private const val MAX_MOOV = 32 * 1024 * 1024

    fun read(src: ByteSource): List<FileChapter>? {
        val moov = topLevelBox(src, "moov") ?: return null
        val chapters = nero(moov) ?: quickTime(moov, src)
        return chapters
            ?.filter { it.startMs >= 0 }
            ?.sortedBy { it.startMs }
            ?.distinctBy { it.startMs }
            ?.takeIf { it.size >= 2 }
    }

    // ---- Box plumbing --------------------------------------------------------------------

    private class Box(val type: String, val start: Int, val end: Int) // content range in buffer

    private fun topLevelBox(src: ByteSource, type: String): ByteArray? {
        var offset = 0L
        while (offset + 8 <= src.size) {
            val head = src.read(offset, minOf(16L, src.size - offset).toInt())
            if (head.size < 8) return null
            var size = u32(head, 0)
            val name = String(head, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (head.size < 16) return null
                size = u64(head, 8)
                header = 16
            } else if (size == 0L) {
                size = src.size - offset
            }
            if (size < header) return null
            if (name == type) {
                val length = size - header
                if (length > MAX_MOOV) return null
                return src.read(offset + header, length.toInt())
            }
            offset += size
        }
        return null
    }

    private fun children(buf: ByteArray, start: Int = 0, end: Int = buf.size): List<Box> {
        val out = mutableListOf<Box>()
        var pos = start
        while (pos + 8 <= end) {
            var size = u32(buf, pos)
            val name = String(buf, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (pos + 16 > end) break
                size = u64(buf, pos + 8)
                header = 16
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }
            if (size < header || pos + size > end) break
            out += Box(name, pos + header, (pos + size).toInt())
            pos += size.toInt()
        }
        return out
    }

    private fun List<Box>.find(type: String) = firstOrNull { it.type == type }
    private fun List<Box>.all(type: String) = filter { it.type == type }

    private fun path(buf: ByteArray, from: Box?, vararg types: String): Box? {
        var box = from ?: return null
        for (t in types) box = children(buf, box.start, box.end).find(t) ?: return null
        return box
    }

    // ---- Nero `chpl` ---------------------------------------------------------------------

    private fun nero(moov: ByteArray): List<FileChapter>? {
        val udta = children(moov).find("udta") ?: return null
        val chpl = children(moov, udta.start, udta.end).find("chpl") ?: return null
        var pos = chpl.start
        val version = moov[pos].toInt() and 0xff
        pos += 4
        if (version == 1) pos += 4
        if (pos >= chpl.end) return null
        val count = moov[pos].toInt() and 0xff
        pos += 1
        val out = mutableListOf<FileChapter>()
        repeat(count) {
            if (pos + 9 > chpl.end) return out
            val start100ns = u64(moov, pos)
            pos += 8
            val len = moov[pos].toInt() and 0xff
            pos += 1
            if (pos + len > chpl.end) return out
            out += FileChapter(String(moov, pos, len, Charsets.UTF_8).trim(), start100ns / 10_000)
            pos += len
        }
        return out
    }

    // ---- QuickTime chapter track -------------------------------------------------------

    private fun quickTime(moov: ByteArray, src: ByteSource): List<FileChapter>? {
        val traks = children(moov).all("trak")
        // Which track does an audio track point to as its chapter track?
        val chapterIds = traks.mapNotNull { t -> path(moov, t, "tref", "chap") }
            .flatMap { chap -> (chap.start until chap.end step 4).map { u32(moov, it) } }
            .toSet()
        if (chapterIds.isEmpty()) return null

        val trak = traks.firstOrNull { t ->
            val tkhd = path(moov, t, "tkhd") ?: return@firstOrNull false
            val version = moov[tkhd.start].toInt() and 0xff
            val id = u32(moov, tkhd.start + if (version == 1) 20 else 12)
            id in chapterIds
        } ?: return null

        val mdhd = path(moov, trak, "mdia", "mdhd") ?: return null
        val mdhdVersion = moov[mdhd.start].toInt() and 0xff
        val timescale = u32(moov, mdhd.start + if (mdhdVersion == 1) 20 else 12)
        if (timescale <= 0) return null

        val stbl = path(moov, trak, "mdia", "minf", "stbl") ?: return null
        val boxes = children(moov, stbl.start, stbl.end)
        val stts = boxes.find("stts") ?: return null
        val stsz = boxes.find("stsz") ?: return null
        val stsc = boxes.find("stsc") ?: return null
        val chunkOffsets: List<Long> = boxes.find("stco")?.let { b ->
            (0 until u32(moov, b.start + 4).toInt()).map { u32(moov, b.start + 8 + it * 4) }
        } ?: boxes.find("co64")?.let { b ->
            (0 until u32(moov, b.start + 4).toInt()).map { u64(moov, b.start + 8 + it * 8) }
        } ?: return null

        // Sample sizes
        val fixedSize = u32(moov, stsz.start + 4)
        val sampleCount = u32(moov, stsz.start + 8).toInt()
        if (sampleCount !in 1..10_000) return null
        val sizes = (0 until sampleCount).map { if (fixedSize != 0L) fixedSize else u32(moov, stsz.start + 12 + it * 4) }

        // Sample → file offset, via sample-to-chunk
        val stscCount = u32(moov, stsc.start + 4).toInt()
        val runs = (0 until stscCount).map { i ->
            val p = stsc.start + 8 + i * 12
            u32(moov, p).toInt() to u32(moov, p + 4).toInt() // first chunk (1-based), samples per chunk
        }
        val offsets = ArrayList<Long>(sampleCount)
        var sample = 0
        for ((chunkIndex, chunkStart) in chunkOffsets.withIndex()) {
            val chunk = chunkIndex + 1
            val perChunk = runs.lastOrNull { it.first <= chunk }?.second ?: break
            var at = chunkStart
            repeat(perChunk) {
                if (sample < sampleCount) {
                    offsets += at
                    at += sizes[sample]
                    sample++
                }
            }
            if (sample >= sampleCount) break
        }
        if (offsets.size != sampleCount) return null

        // Sample → start time
        val starts = ArrayList<Long>(sampleCount)
        var t = 0L
        val sttsCount = u32(moov, stts.start + 4).toInt()
        for (i in 0 until sttsCount) {
            val p = stts.start + 8 + i * 8
            val n = u32(moov, p)
            val delta = u32(moov, p + 4)
            repeat(n.toInt()) {
                if (starts.size < sampleCount) starts += t * 1000 / timescale
                t += delta
            }
        }
        if (starts.size != sampleCount) return null

        // Titles: each sample is a 16-bit length followed by the text.
        // Read them in one go when they sit close together (they usually do).
        val first = offsets.min()
        val last = offsets.indices.maxOf { offsets[it] + sizes[it] }
        val block: ByteArray? = if (last - first <= 2 * 1024 * 1024) src.read(first, (last - first).toInt()) else null
        return offsets.indices.map { i ->
            val bytes = block?.let {
                val from = (offsets[i] - first).toInt()
                it.copyOfRange(from, minOf(it.size, from + sizes[i].toInt()))
            } ?: src.read(offsets[i], sizes[i].toInt())
            FileChapter(textSample(bytes).ifBlank { "Chapter ${i + 1}" }, starts[i])
        }
    }

    private fun textSample(b: ByteArray): String {
        if (b.size < 2) return ""
        val len = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
        val end = minOf(b.size, 2 + len)
        val isUtf16 = end - 2 >= 2 && (b[2] == 0xFE.toByte() && b[3] == 0xFF.toByte())
        return if (isUtf16) String(b, 4, end - 4, Charsets.UTF_16BE).trim()
        else String(b, 2, end - 2, Charsets.UTF_8).trim()
    }

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xff) shl 24) or ((b[p + 1].toLong() and 0xff) shl 16) or
            ((b[p + 2].toLong() and 0xff) shl 8) or (b[p + 3].toLong() and 0xff)

    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)
}
