package com.schmitzkr.grimreader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.schmitzkr.grimreader.core.api.OidcCallbackRequest
import com.schmitzkr.grimreader.core.model.OidcProviderDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authorization-code sign-in through the OIDC provider Grimmory is
 * configured with, the way the web client does it:
 *
 * 1. the provider's discovery document gives the authorization endpoint;
 * 2. `state` is a one-time value the SERVER generates and caches
 *    (`GET /auth/oidc/state`), because the server validates it on callback
 *    and rejects anything it did not mint;
 * 3. PKCE and a nonce are generated here and kept in DataStore keyed by
 *    that state, since the browser may kill and relaunch the app;
 * 4. the browser (a Custom Tab) returns to `is.schmitzkr.grimreader://oidc-callback`,
 *    and `POST /auth/oidc/callback` turns the code into the usual token pair.
 *
 * The redirect URI must be on the server's `oidc_redirect_uris` allow-list
 * and registered on the provider.
 */
@Singleton
class OidcFlow @Inject constructor(
    private val context: Context,
    private val settings: Settings,
    private val clients: ClientHolder,
) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    /** Opens the provider's sign-in page. Throws with a readable message when it cannot. */
    suspend fun begin(provider: OidcProviderDetails) {
        val issuer = provider.issuerUri?.trimEnd('/') ?: error("The server has no OIDC issuer configured.")
        val clientId = provider.clientId ?: error("The server has no OIDC client id configured.")
        val authEndpoint = withContext(Dispatchers.IO) { discoverAuthorizationEndpoint(issuer) }
        val state = clients.current().api.oidcState()["state"]?.jsonPrimitive?.content
            ?: error("The server did not issue a sign-in state.")
        val verifier = randomToken(64)
        val nonce = randomToken(32)
        settings.storeOidcPending(state, verifier, nonce)

        val url = Uri.parse(authEndpoint).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", "openid")
            .appendQueryParameter("code_challenge", challengeFor(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("nonce", nonce)
            .build()
        val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
        tab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        tab.launchUrl(context, url)
    }

    /** Builds the callback body for a redirect, or null if the state is unknown or stale. */
    suspend fun callbackFor(redirect: Uri): OidcCallbackRequest? {
        if (redirect.scheme != REDIRECT_SCHEME || redirect.host != REDIRECT_HOST) return null
        val code = redirect.getQueryParameter("code") ?: return null
        val state = redirect.getQueryParameter("state") ?: return null
        val pending = settings.takeOidcPending(state) ?: return null
        return OidcCallbackRequest(
            code = code,
            state = state,
            redirectUri = REDIRECT_URI,
            codeVerifier = pending.first,
            nonce = pending.second,
        )
    }

    private fun discoverAuthorizationEndpoint(issuer: String): String {
        val request = Request.Builder().url("$issuer/.well-known/openid-configuration").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("The identity provider's discovery document could not be read (${response.code}).")
            val doc = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            return doc["authorization_endpoint"]?.jsonPrimitive?.content
                ?: error("The identity provider does not declare an authorization endpoint.")
        }
    }

    private fun randomToken(bytes: Int): String {
        val buf = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    private fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    companion object {
        const val REDIRECT_SCHEME = "is.schmitzkr.grimreader"
        const val REDIRECT_HOST = "oidc-callback"
        const val REDIRECT_URI = "$REDIRECT_SCHEME://$REDIRECT_HOST"
    }
}
