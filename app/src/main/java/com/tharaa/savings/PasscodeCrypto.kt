package com.tharaa.savings

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The passcode is never stored. We keep a salted PBKDF2 hash and compare against it, so reading
 * the file (even decrypted) does not reveal the PIN. PBKDF2 is deliberately slow to make brute
 * force of a 4-digit space costly.
 *
 * Uses java.util.Base64 (standard on our minSdk 26+) rather than android.util.Base64, so the
 * hashing is a pure JVM concern and can be unit-tested without a device.
 */
object PasscodeCrypto {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    fun newSalt(): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return encoder.encodeToString(salt)
    }

    fun hash(pin: String, saltB64: String): String {
        val salt = decoder.decode(saltB64)
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return encoder.encodeToString(hash)
    }

    fun verify(pin: String, saltB64: String, expectedHashB64: String): Boolean {
        val actual = hash(pin, saltB64)
        return constantTimeEquals(actual, expectedHashB64)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
}
