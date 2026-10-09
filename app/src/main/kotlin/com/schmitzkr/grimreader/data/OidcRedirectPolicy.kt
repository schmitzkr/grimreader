package com.schmitzkr.grimreader.data

import java.net.URI

/**
 * Pure checks around the OIDC redirect, kept free of `android.net.Uri` so they
 * unit-test on the JVM.
 *
 * The redirect is a custom scheme, so any app can register for it; this cannot
 * be closed client-side (it needs a verified App Link and a server-hosted
 * `assetlinks.json`). What is enforced here is that a callback is only ever
 * honoured when it is exactly the redirect we asked for, and that the
 * browser is only ever pointed at a plain https IdP page.
 */
object OidcRedirectPolicy {
    /**
     * The authorization endpoint from the discovery document becomes an
     * ACTION_VIEW URI, so it must be a well-formed https URL with a host and
     * no embedded credentials; `intent:`, `market:`, `javascript:` etc. are refused.
     */
    fun requireSafeAuthorizationEndpoint(endpoint: String) {
        val uri = try {
            URI(endpoint.trim())
        } catch (e: java.net.URISyntaxException) {
            null
        }
        val ok = uri != null &&
            uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null &&
            uri.rawFragment == null
        if (!ok) error("Sign-in was stopped: the identity provider's sign-in page is not a plain https address.")
    }

    /**
     * True only for `scheme://host` with no path, userinfo, port or fragment,
     * matching the registered redirect URI exactly (Android would also route
     * look-alikes such as `scheme://host/extra` to the same intent filter).
     */
    fun isOurRedirect(scheme: String?, host: String?, port: Int, path: String?, userInfo: String?, fragment: String?): Boolean =
        scheme == OidcFlow.REDIRECT_SCHEME &&
            host == OidcFlow.REDIRECT_HOST &&
            port == -1 &&
            (path.isNullOrEmpty()) &&
            userInfo == null &&
            fragment == null

    /** A callback carrying `error` is a refusal, never something to redeem. */
    fun isUsableCallback(code: String?, state: String?, error: String?): Boolean =
        error == null && !code.isNullOrBlank() && !state.isNullOrBlank()
}
