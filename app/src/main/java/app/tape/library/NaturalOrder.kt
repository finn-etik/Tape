package app.tape.library

/** Sorts "Chapter 2" before "Chapter 10", ignoring case. */
object NaturalOrder : Comparator<String> {
    private val chunks = Regex("[0-9]+|[^0-9]+")

    override fun compare(a: String, b: String): Int {
        val x = chunks.findAll(a.lowercase()).map { it.value }.toList()
        val y = chunks.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0] in '0'..'9' && q[0] in '0'..'9') {
                val pn = p.trimStart('0')
                val qn = q.trimStart('0')
                if (pn.length != qn.length) pn.length - qn.length else pn.compareTo(qn)
            } else {
                p.compareTo(q)
            }
            if (c != 0) return c
        }
        return x.size - y.size
    }
}

/** "03_the_long_road.mp3" → "03 the long road" */
fun prettyName(fileName: String): String =
    fileName.substringBeforeLast('.', fileName).replace('_', ' ').trim().ifEmpty { fileName }

/** "Tove Jansson - The Summer Book" → ("The Summer Book", "Tove Jansson") */
fun splitAuthor(folderName: String): Pair<String, String?> {
    val i = folderName.indexOf(" - ")
    return if (i > 0) folderName.substring(i + 3).trim() to folderName.substring(0, i).trim()
    else folderName.trim() to null
}
