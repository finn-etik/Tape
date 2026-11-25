package app.tape.drime

import app.tape.library.NaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

data class DrimeEntry(
    val id: Long,
    val name: String,
    val type: String,
    val hash: String,
    val size: Long,
    val mime: String?,
    val extension: String?,
) {
    val isFolder: Boolean get() = type == "folder"

    val isAudio: Boolean
        get() = !isFolder && (
            type == "audio" ||
                mime?.startsWith("audio/") == true ||
                extensionOf(name, extension) in AUDIO_EXTENSIONS
            )

    val isImage: Boolean
        get() = !isFolder && (type == "image" || extensionOf(name, extension) in IMAGE_EXTENSIONS)

    companion object {
        val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "mka")
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

        fun extensionOf(name: String, ext: String?): String =
            (ext ?: name.substringAfterLast('.', "")).lowercase()
    }
}

data class DrimeListing(val entries: List<DrimeEntry>)

class DrimeException(message: String) : IOException(message)

/** The small slice of https://docs.drime.cloud/api-reference that a player needs. */
class DrimeApi(private val http: OkHttpClient) {

    fun downloadUrl(hash: String): String = "${DrimeConfig.API}/file-entries/download/$hash"

    /** Lists a folder (null = root, or the app folder for folder-scoped apps), all pages. */
    suspend fun list(folderHash: String?): DrimeListing = withContext(Dispatchers.IO) {
        val all = mutableListOf<DrimeEntry>()
        var page = 1
        while (true) {
            val url = "${DrimeConfig.API}/drive/file-entries".toHttpUrl().newBuilder()
                .addQueryParameter("workspaceId", "0")
                .addQueryParameter("perPage", "100")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("orderBy", "name")
                .addQueryParameter("orderDir", "asc")
                .apply { if (folderHash != null) addQueryParameter("folderId", folderHash) }
                .build()
            val json = getJson(url.toString())
            val data = json.optJSONArray("data")
            if (data != null) {
                for (i in 0 until data.length()) all += parseEntry(data.getJSONObject(i))
            }
            val last = json.optInt("last_page", page)
            if (page >= last || data == null || data.length() == 0) break
            page++
        }
        // Folders first, then names the way people expect: "2" before "10".
        DrimeListing(all.sortedWith(compareBy<DrimeEntry> { !it.isFolder }.thenBy(NaturalOrder) { it.name }))
    }

    /** Display name of the signed-in account. */
    suspend fun accountName(): String? = withContext(Dispatchers.IO) {
        val user = getJson("${DrimeConfig.API}/users/me").optJSONObject("user") ?: return@withContext null
        user.str("display_name") ?: user.str("first_name") ?: user.str("email")
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw DrimeException(
                    when (response.code) {
                        401 -> "Your Drime session has ended. Connect again."
                        403 -> "TAPE isn't allowed to open this. Check the app's permissions in Drime."
                        404 -> "This folder no longer exists, or is outside TAPE's app folder."
                        429 -> "Drime is asking us to slow down. Try again in a minute."
                        else -> "Drime answered with an error (${response.code})."
                    },
                )
            }
            return JSONObject(text)
        }
    }

    private fun parseEntry(o: JSONObject) = DrimeEntry(
        id = o.optLong("id"),
        name = o.str("name") ?: "Untitled",
        type = o.str("type") ?: "file",
        hash = o.getString("hash"),
        size = o.optLong("file_size"),
        mime = o.str("mime"),
        extension = o.str("extension"),
    )
}

/** org.json turns JSON null into the string "null"; this doesn't. */
internal fun JSONObject.str(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
