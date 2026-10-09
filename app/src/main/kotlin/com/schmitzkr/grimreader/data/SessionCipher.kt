package com.schmitzkr.grimreader.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps a secret for storage at rest. [decrypt] throws a `GeneralSecurityException` if the value cannot be unwrapped. */
interface SessionCipher {
    fun encrypt(plain: String): String
    fun decrypt(wrapped: String): String
}

/**
 * The on-disk shape of a wrapped value: `gr1:<base64 iv>:<base64 ciphertext>`.
 * The prefix is how a legacy plaintext token (a JWT or opaque string, which
 * never starts with it) is told apart from a wrapped one.
 */
object WrappedFormat {
    const val PREFIX = "gr1:"

    class Parts(val iv: ByteArray, val ciphertext: ByteArray)

    fun serialise(iv: ByteArray, ciphertext: ByteArray): String =
        PREFIX + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ciphertext)

    /** Null when [raw] is not in the wrapped format (legacy plaintext, or garbage). */
    fun parse(raw: String): Parts? {
        if (!raw.startsWith(PREFIX)) return null
        val pieces = raw.removePrefix(PREFIX).split(':')
        if (pieces.size != 2) return null
        return try {
            val iv = Base64.getDecoder().decode(pieces[0])
            val ct = Base64.getDecoder().decode(pieces[1])
            if (iv.size != KeystoreCipher.IV_BYTES || ct.isEmpty()) null else Parts(iv, ct)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun isWrapped(raw: String): Boolean = parse(raw) != null
}

/** How long an abandoned OIDC sign-in's verifier and nonce stay usable. */
object PendingExpiry {
    const val MAX_AGE_MS = 10 * 60 * 1000L

    fun isStale(storedAtMs: Long, nowMs: Long): Boolean =
        nowMs - storedAtMs > MAX_AGE_MS || storedAtMs > nowMs + MAX_AGE_MS
}

/**
 * AES-256-GCM under a non-exportable AndroidKeyStore key. No user
 * authentication is required: the tokens have to be readable in the
 * background to refresh. A fresh random IV is generated per encryption.
 */
class KeystoreCipher : SessionCipher {
    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return WrappedFormat.serialise(cipher.iv, ct)
    }

    override fun decrypt(wrapped: String): String {
        val parts = WrappedFormat.parse(wrapped)
            ?: throw java.security.GeneralSecurityException("Not a wrapped value")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, parts.iv))
        return String(cipher.doFinal(parts.ciphertext), Charsets.UTF_8)
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }

    companion object {
        const val ALIAS = "grimreader.session"
        const val IV_BYTES = 12
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
    }
}
