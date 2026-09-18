package com.tharaa.savings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for the savings file.
 *
 * The AES-256 key is generated inside the Android Keystore: it never leaves secure hardware,
 * cannot be exported (even on a rooted device), and is bound to this app + this device. So the
 * encrypted file is useless if copied off the phone.
 *
 * The key intentionally does NOT require per-use user authentication, so the app can read/write
 * the file without a fingerprint prompt. User-facing protection is the separate app lock.
 */
object SecureStore {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "tharaa_savings_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12       // GCM standard nonce length
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /**
     * Writes [IV (12 bytes)][GCM ciphertext] to the file.
     *
     * The bytes land in a sibling temp file that is flushed to disk and only then renamed over the
     * real one. Writing in place would truncate it first, so a crash or a low-memory kill part-way
     * through left a file that no longer authenticates - and an unreadable savings file is a lost
     * savings file. The rename is atomic, so the file on disk is always one whole version or the
     * other.
     */
    fun writeString(file: File, plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(iv)
            out.write(ciphertext)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("could not replace ${file.name}")
        }
    }

    /**
     * Returns the decrypted text, or null when there is simply no file yet.
     *
     * A file that exists but will not decrypt throws [Unreadable] instead of returning null. The
     * two cases used to be indistinguishable, and the caller read "null" as "first run" and wrote
     * an empty ledger over the top of it.
     */
    fun readString(file: File): String? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size <= IV_LENGTH) throw Unreadable("file is too short to hold an IV", null)
        return try {
            val iv = bytes.copyOfRange(0, IV_LENGTH)
            val ciphertext = bytes.copyOfRange(IV_LENGTH, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            throw Unreadable("stored file did not decrypt", e)
        }
    }

    /**
     * The file is there but we cannot read it: a partial write, or a copy restored from another
     * device whose Keystore key we don't have. Never means "no file yet".
     */
    class Unreadable(message: String, cause: Throwable?) : Exception(message, cause)
}
