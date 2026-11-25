package app.tape.drime

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Drime credentials, encrypted with a key held in the Android Keystore ([KeystoreCipher]).
 *
 * Writes use commit(), not apply(): Drime rotates OAuth refresh tokens and treats a
 * reused one as stolen (revoking everything), so a new refresh token must be on disk
 * before anything uses it.
 */
class TokenStore(context: Context) {

    /** [refresh] is null for a personal access token, which never expires. */
    data class Tokens(val access: String, val refresh: String?, val expiresAt: Long)

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(KEY_ALIAS)

    init {
        // Tokens from versions before 0.2 were stored with the deprecated security-crypto
        // library. They can't be read without it, so they're removed (connect once again).
        context.deleteSharedPreferences(LEGACY_FILE)
    }

    private val _tokens = MutableStateFlow(read())
    val tokens: StateFlow<Tokens?> = _tokens.asStateFlow()

    private val _accountName = MutableStateFlow(prefs.getString(KEY_NAME, null))
    val accountName: StateFlow<String?> = _accountName.asStateFlow()

    @Synchronized
    fun current(): Tokens? = _tokens.value

    @Synchronized
    fun save(tokens: Tokens?) {
        val editor = prefs.edit()
        if (tokens == null) {
            editor.clear()
            _accountName.value = null
        } else {
            val json = JSONObject()
                .put("access", tokens.access)
                .put("refresh", tokens.refresh ?: "")
                .put("expires", tokens.expiresAt)
            editor.putString(KEY_BLOB, cipher.encrypt(json.toString().toByteArray(Charsets.UTF_8)))
        }
        editor.commit()
        _tokens.value = tokens
    }

    fun saveAccountName(name: String?) {
        prefs.edit().putString(KEY_NAME, name).apply()
        _accountName.value = name
    }

    private fun read(): Tokens? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val json = JSONObject(String(cipher.decrypt(blob), Charsets.UTF_8))
            Tokens(
                access = json.getString("access"),
                refresh = json.optString("refresh").ifEmpty { null },
                expiresAt = json.optLong("expires", Long.MAX_VALUE),
            )
        } catch (e: Exception) {
            // Restored onto a new phone (the Keystore key didn't come along) or corrupted.
            prefs.edit().clear().commit()
            null
        }
    }

    private companion object {
        const val FILE = "drime_tokens"
        const val LEGACY_FILE = "drime_auth"
        const val KEY_ALIAS = "tape.drime.tokens"
        const val KEY_BLOB = "blob"
        const val KEY_NAME = "account_name"
    }
}
