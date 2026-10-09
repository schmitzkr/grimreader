package com.schmitzkr.grimreader

import com.schmitzkr.grimreader.data.OidcRedirectPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OidcRedirectPolicyTest {
    private val scheme = "is.schmitzkr.grimreader"
    private val host = "oidc-callback"

    @Test
    fun `the exact registered redirect is accepted`() {
        assertTrue(OidcRedirectPolicy.isOurRedirect(scheme, host, -1, "", null, null))
        assertTrue(OidcRedirectPolicy.isOurRedirect(scheme, host, -1, null, null, null))
    }

    @Test
    fun `look-alike redirects are refused`() {
        assertFalse(OidcRedirectPolicy.isOurRedirect("https", host, -1, "", null, null))
        assertFalse(OidcRedirectPolicy.isOurRedirect(scheme, "other", -1, "", null, null))
        assertFalse(OidcRedirectPolicy.isOurRedirect(scheme, host, 8080, "", null, null))
        assertFalse(OidcRedirectPolicy.isOurRedirect(scheme, host, -1, "/extra", null, null))
        assertFalse(OidcRedirectPolicy.isOurRedirect(scheme, host, -1, "", "user", null))
        assertFalse(OidcRedirectPolicy.isOurRedirect(scheme, host, -1, "", null, "frag"))
    }

    @Test
    fun `error or incomplete callbacks are not usable`() {
        assertTrue(OidcRedirectPolicy.isUsableCallback("c", "s", null))
        assertFalse(OidcRedirectPolicy.isUsableCallback("c", "s", "access_denied"))
        assertFalse(OidcRedirectPolicy.isUsableCallback(null, "s", null))
        assertFalse(OidcRedirectPolicy.isUsableCallback("c", "", null))
    }

    @Test
    fun `authorization endpoint must be a plain https url`() {
        OidcRedirectPolicy.requireSafeAuthorizationEndpoint("https://idp.example.com/authorize")
        listOf(
            "intent://x#Intent;scheme=a;end",
            "market://details?id=evil",
            "tel:123",
            "http://idp.example.com/authorize",
            "https://user:pw@idp.example.com/authorize",
            "https:///authorize",
            "https://idp.example.com/a#frag",
            "https://idp exam.com/a",
        ).forEach { bad ->
            assertThrows(bad, IllegalStateException::class.java) {
                OidcRedirectPolicy.requireSafeAuthorizationEndpoint(bad)
            }
        }
    }
}
