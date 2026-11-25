package app.tape.drime

import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/**
 * Hands out a valid access token, refreshing it when needed.
 *
 * Everything here is blocking and runs on OkHttp / ExoPlayer loader threads.
 * A single lock guarantees that only one refresh happens at a time, which
 * matters because Drime refresh tokens are single-use.
 */
class DrimeSession(
    private val store: TokenStore,
    private val http: OkHttpClient, // must NOT carry the auth interceptor
) {
    private val lock = Any()

    val isSignedIn: Boolean get() = store.current() != null

    fun accessToken(): String? {
        synchronized(lock) {
            val tokens = store.current() ?: return null
            val fresh = tokens.refresh == null ||
                System.currentTimeMillis() < tokens.expiresAt - EXPIRY_MARGIN_MS
            return if (fresh) tokens.access else refreshLocked(tokens)
        }
    }

    /** Called after a 401. Returns a token worth retrying with, or null. */
    fun onUnauthorized(rejected: String): String? {
        synchronized(lock) {
            val tokens = store.current() ?: return null
            if (tokens.access != rejected) return tokens.access // another thread already refreshed
            if (tokens.refresh == null) {
                store.save(null) // personal token was deleted on Drime's side
                return null
            }
            return refreshLocked(tokens)
        }
    }

    private fun refreshLocked(tokens: TokenStore.Tokens): String? {
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", tokens.refresh!!)
            .add("client_id", DrimeConfig.clientId)
            .build()
        val request = Request.Builder().url(DrimeConfig.TOKEN_URL).post(body).build()
        return try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> {
                        val next = parseTokens(JSONObject(text), fallbackRefresh = tokens.refresh)
                        store.save(next) // persist before first use
                        next.access
                    }
                    // invalid_grant & co: the authorization is over, the user must connect again.
                    response.code in 400..401 -> {
                        store.save(null)
                        null
                    }
                    else -> tokens.access // Drime hiccup; try again on the next request
                }
            }
        } catch (e: IOException) {
            tokens.access // offline: cached audio still plays, requests just fail
        }
    }

    companion object {
        private const val EXPIRY_MARGIN_MS = 60_000L

        fun parseTokens(json: JSONObject, fallbackRefresh: String? = null): TokenStore.Tokens {
            val expiresIn = json.optLong("expires_in", 3600L)
            return TokenStore.Tokens(
                access = json.getString("access_token"),
                refresh = json.optString("refresh_token").ifEmpty { fallbackRefresh },
                expiresAt = System.currentTimeMillis() + expiresIn * 1000L,
            )
        }
    }
}

/**
 * Adds the bearer token to requests for app.drime.cloud only. OkHttp drops the
 * Authorization header by itself if a download redirects to another host
 * (for example a storage bucket), so the token never leaks.
 */
class DrimeAuthInterceptor(private val session: DrimeSession) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val skip = request.url.host != DrimeConfig.HOST ||
            request.header("Authorization") != null ||
            request.url.encodedPath.startsWith("/oauth/")
        if (skip) return chain.proceed(request)

        val token = session.accessToken() ?: return chain.proceed(request)
        val response = chain.proceed(request.withToken(token))
        if (response.code != 401) return response

        response.close()
        val retry = session.onUnauthorized(token) ?: return chain.proceed(request)
        return chain.proceed(request.withToken(retry))
    }

    private fun Request.withToken(token: String) =
        newBuilder().header("Authorization", "Bearer $token").build()
}
