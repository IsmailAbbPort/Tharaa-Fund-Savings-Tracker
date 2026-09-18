package com.backupkit

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-based encryption for backup blobs, independent of the Android Keystore so a backup
 * made on one phone can be restored on another (unlike the device-bound at-rest key an app uses
 * locally). The key is derived from the passphrase with PBKDF2 and a per-blob random salt; the
 * payload is sealed with AES-256-GCM, which authenticates it (a wrong passphrase or any tampering
 * fails to decrypt rather than returning garbage).
 *
 * Blob layout: "BKUP" (4) | version (1) | salt (16) | iv (12) | AES-GCM ciphertext+tag.
 * The header is not secret; it just lets any device parse the parameters back out.
 */
object BackupCrypto {

    private const val MAGIC = "BKUP"
    private const val VERSION = 1
    private const val PBKDF2_ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val HEADER_LEN = 4 + 1 + SALT_LEN + IV_LEN

    fun encrypt(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val salt = randomBytes(SALT_LEN)
        val iv = randomBytes(IV_LEN)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArrayOutputStream(HEADER_LEN + ciphertext.size).apply {
            write(MAGIC.toByteArray(Charsets.US_ASCII))
            write(VERSION)
            write(salt)
            write(iv)
            write(ciphertext)
        }.toByteArray()
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): ByteArray {
        if (blob.size <= HEADER_LEN) throw BackupException("Not a backup file")
        if (String(blob.copyOfRange(0, 4), Charsets.US_ASCII) != MAGIC) {
            throw BackupException("Not a backup file")
        }
        if (blob[4].toInt() != VERSION) throw BackupException("Unsupported backup version")
        val salt = blob.copyOfRange(5, 5 + SALT_LEN)
        val iv = blob.copyOfRange(5 + SALT_LEN, HEADER_LEN)
        val ciphertext = blob.copyOfRange(HEADER_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw BackupException("Wrong passphrase or corrupted backup", e)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(key, "AES")
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }
}
