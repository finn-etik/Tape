package app.tape.drime

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drime credentials, encrypted at rest with a Keystore-backed key.
 *
 * Writes use commit(), not apply(): Drime rotates refresh tokens and treats a
 * reused one as stolen (revoking everything), so a new refresh token must be
 * on disk before anything uses it.
 */
class TokenStore(context: Context) {

    /** [refresh] is null for a personal access token, which never expires. */
    data class Tokens(val access: String, val refresh: String?, val expiresAt: Long)

    private val prefs: SharedPreferences = open(context)

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
            editor.putString(KEY_ACCESS, tokens.access)
                .putString(KEY_REFRESH, tokens.refresh)
                .putLong(KEY_EXPIRES, tokens.expiresAt)
        }
        editor.commit()
        _tokens.value = tokens
    }

    fun saveAccountName(name: String?) {
        prefs.edit().putString(KEY_NAME, name).apply()
        _accountName.value = name
    }

    private fun read(): Tokens? {
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        return Tokens(
            access = access,
            refresh = prefs.getString(KEY_REFRESH, null),
            expiresAt = prefs.getLong(KEY_EXPIRES, Long.MAX_VALUE),
        )
    }

    private companion object {
        const val FILE = "drime_auth"
        const val KEY_ACCESS = "access"
        const val KEY_REFRESH = "refresh"
        const val KEY_EXPIRES = "expires_at"
        const val KEY_NAME = "account_name"

        fun open(context: Context): SharedPreferences {
            fun create(): SharedPreferences = EncryptedSharedPreferences.create(
                context,
                FILE,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            return try {
                create()
            } catch (e: Exception) {
                // Keystore keys don't survive backup/restore to a new device. Start clean.
                context.deleteSharedPreferences(FILE)
                create()
            }
        }
    }
}
