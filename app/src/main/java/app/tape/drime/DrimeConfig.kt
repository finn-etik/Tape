package app.tape.drime

import app.tape.BuildConfig

/** Endpoints and settings from https://docs.drime.cloud (OAuth + API reference). */
object DrimeConfig {
    const val HOST = "app.drime.cloud"
    const val BASE = "https://$HOST"
    const val API = "$BASE/api/v1"
    const val AUTHORIZE_URL = "$BASE/oauth/authorize"
    const val TOKEN_URL = "$BASE/oauth/token"
    const val REVOKE_URL = "$BASE/oauth/revoke"

    /** TAPE only ever reads. Space-separated, as the authorize endpoint expects. */
    const val SCOPES = "files.read folders.read"

    /**
     * Register exactly this in the Drime developer console. Drime ignores the port
     * for 127.0.0.1 (RFC 8252), so the app can listen on any free port at runtime.
     */
    const val REGISTERED_REDIRECT = "http://127.0.0.1/callback"

    val clientId: String get() = BuildConfig.DRIME_CLIENT_ID
    val hasClientId: Boolean get() = clientId.isNotBlank()
}
