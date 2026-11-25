package app.tape.drime

import android.app.Activity
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import app.tape.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OAuth 2.0 authorization code flow with PKCE, the way Drime wants it for a public client.
 *
 * Drime refuses custom schemes (tape://callback) as redirect URIs, so TAPE uses the
 * RFC 8252 loopback pattern instead: it listens on 127.0.0.1 for exactly one
 * redirect, opens the consent page in a Custom Tab, and reads the code from the
 * browser's request to http://127.0.0.1:<port>/callback.
 */
class DrimeLogin(
    private val store: TokenStore,
    private val api: DrimeApi,
    private val http: OkHttpClient, // plain client, no auth interceptor
) {

    suspend fun signIn(activity: Activity) {
        if (!DrimeConfig.hasClientId) throw DrimeException("No Drime client id in this build.")

        val verifier = randomUrlSafe(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
        val state = randomUrlSafe(24)

        val (code, redirectUri) = withContext(Dispatchers.IO) {
            ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
                // Short accept() timeouts let us notice cancellation (accept can't be interrupted).
                server.soTimeout = 1_000

                val redirectUri = "http://127.0.0.1:${server.localPort}/callback"
                val authorizeUrl = DrimeConfig.AUTHORIZE_URL.toHttpUrl().newBuilder()
                    .addQueryParameter("client_id", DrimeConfig.clientId)
                    .addQueryParameter("redirect_uri", redirectUri)
                    .addQueryParameter("response_type", "code")
                    .addQueryParameter("scope", DrimeConfig.SCOPES)
                    .addQueryParameter("state", state)
                    .addQueryParameter("code_challenge", challenge)
                    .addQueryParameter("code_challenge_method", "S256")
                    .build()

                withContext(Dispatchers.Main) {
                    CustomTabsIntent.Builder().setShowTitle(true).build()
                        .launchUrl(activity, Uri.parse(authorizeUrl.toString()))
                }

                awaitRedirect(server, state) to redirectUri
            }
        }

        val tokens = exchange(code, redirectUri, verifier)
        store.save(tokens)
        store.saveAccountName(runCatching { api.accountName() }.getOrNull())
    }

    /** For people who'd rather paste a personal access token (Settings › Developers in Drime). */
    suspend fun useAccessToken(token: String) {
        store.save(TokenStore.Tokens(token.trim(), refresh = null, expiresAt = Long.MAX_VALUE))
        try {
            store.saveAccountName(api.accountName())
        } catch (e: Exception) {
            store.save(null)
            throw DrimeException("That token didn't work. Check that it has Files and Folders read access.")
        }
    }

    suspend fun signOut() {
        val refresh = store.current()?.refresh
        store.save(null)
        if (refresh != null && DrimeConfig.hasClientId) {
            withContext(Dispatchers.IO) {
                // Revoking the refresh token ends the whole authorization. Best effort.
                runCatching {
                    val body = FormBody.Builder()
                        .add("token", refresh)
                        .add("client_id", DrimeConfig.clientId)
                        .build()
                    http.newCall(Request.Builder().url(DrimeConfig.REVOKE_URL).post(body).build()).execute().close()
                }
            }
        }
    }

    private suspend fun awaitRedirect(server: ServerSocket, expectedState: String): String {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            if (System.currentTimeMillis() > deadline) throw DrimeException("Sign-in timed out. Try again.")
            val socket = try {
                server.accept()
            } catch (e: SocketTimeoutException) {
                continue
            }
            socket.use {
                val url = readRequestUrl(it)
                if (url == null || url.encodedPath != "/callback") {
                    respond(it, 404, "Not found")
                    return@use // browsers like to ask for /favicon.ico; keep waiting
                }
                val error = url.queryParameter("error")
                val code = url.queryParameter("code")
                val stateOk = url.queryParameter("state") == expectedState
                when {
                    !stateOk -> {
                        respond(it, 400, page(false, "This sign-in link didn't come from TAPE."))
                        throw DrimeException("Sign-in was interrupted. Try again.")
                    }
                    error != null || code == null -> {
                        val msg = if (error == "access_denied") "You declined access." else "Drime returned an error."
                        respond(it, 200, page(false, msg))
                        throw DrimeException(msg)
                    }
                    else -> {
                        respond(it, 200, page(true, "You can return to TAPE."))
                        return code
                    }
                }
            }
        }
    }

    private fun readRequestUrl(socket: Socket): HttpUrl? {
        socket.soTimeout = 5_000
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val requestLine = reader.readLine() ?: return null
        // Drain headers so the browser sees a well-behaved server.
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        val target = requestLine.split(' ').getOrNull(1) ?: return null
        return runCatching { "http://127.0.0.1$target".toHttpUrl() }.getOrNull()
    }

    private fun respond(socket: Socket, status: Int, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else if (status == 404) "Not Found" else "Bad Request"
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private suspend fun exchange(code: String, redirectUri: String, verifier: String): TokenStore.Tokens =
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", redirectUri)
                .add("client_id", DrimeConfig.clientId)
                .add("code_verifier", verifier)
                .build()
            http.newCall(Request.Builder().url(DrimeConfig.TOKEN_URL).post(body).build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val reason = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                    throw DrimeException("Drime didn't accept the sign-in ($reason). Try again.")
                }
                DrimeSession.parseTokens(JSONObject(text))
            }
        }

    private fun page(ok: Boolean, message: String): String {
        val back = "intent://auth#Intent;scheme=tape;package=${BuildConfig.APPLICATION_ID};end"
        val title = if (ok) "Drime is connected" else "Couldn’t connect"
        return """
            <!doctype html><html><head>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>TAPE</title>
            <style>
              body{margin:0;font-family:system-ui,sans-serif;background:#F3F0E8;color:#1B1A17;
                   display:flex;min-height:100vh;align-items:center;justify-content:center;text-align:center}
              h1{font-weight:500;font-size:26px;margin:0 0 8px}
              p{color:#8C877D;margin:0}
              a{display:inline-block;margin-top:32px;padding:14px 28px;border-radius:999px;
                background:#1B1A17;color:#F3F0E8;text-decoration:none}
              @media (prefers-color-scheme:dark){body{background:#121110;color:#EEEAE1}
                a{background:#EEEAE1;color:#121110}}
            </style></head><body><div>
            <h1>$title</h1><p>${escape(message)}</p>
            <a href="$back">Back to TAPE</a>
            </div></body></html>
        """.trimIndent()
    }

    private fun escape(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val TIMEOUT_MS = 5 * 60 * 1000

        fun randomUrlSafe(bytes: Int): String =
            base64Url(ByteArray(bytes).also { SecureRandom().nextBytes(it) })

        fun base64Url(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
