package com.backupkit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCryptoTest {

    private val data = """{"hello":"world","amount":12345}""".toByteArray()

    @Test fun roundTripsWithTheRightPassphrase() {
        val blob = BackupCrypto.encrypt(data, "correct horse".toCharArray())
        val out = BackupCrypto.decrypt(blob, "correct horse".toCharArray())
        assertArrayEquals(data, out)
    }

    @Test fun emptyPayloadRoundTrips() {
        val blob = BackupCrypto.encrypt(ByteArray(0), "p".toCharArray())
        assertArrayEquals(ByteArray(0), BackupCrypto.decrypt(blob, "p".toCharArray()))
    }

    @Test fun wrongPassphraseFails() {
        val blob = BackupCrypto.encrypt(data, "right".toCharArray())
        assertThrows(BackupException::class.java) {
            BackupCrypto.decrypt(blob, "wrong".toCharArray())
        }
    }

    @Test fun tamperedBlobFails() {
        val blob = BackupCrypto.encrypt(data, "p".toCharArray())
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte() // flip a ciphertext byte
        assertThrows(BackupException::class.java) {
            BackupCrypto.decrypt(blob, "p".toCharArray())
        }
    }

    @Test fun nonBackupBytesFail() {
        assertThrows(BackupException::class.java) {
            BackupCrypto.decrypt("just some random text".toByteArray(), "p".toCharArray())
        }
    }

    @Test fun ciphertextIsNotThePlaintext() {
        val blob = BackupCrypto.encrypt(data, "p".toCharArray())
        // The plaintext must not appear verbatim in the blob.
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("world"))
    }

    @Test fun eachEncryptionUsesFreshSaltAndIv() {
        val a = BackupCrypto.encrypt(data, "p".toCharArray())
        val b = BackupCrypto.encrypt(data, "p".toCharArray())
        // Same input + passphrase, but random salt/iv means the blobs differ.
        assertFalse(a.contentEquals(b))
        // ...yet both decrypt back to the same data.
        assertArrayEquals(BackupCrypto.decrypt(a, "p".toCharArray()), BackupCrypto.decrypt(b, "p".toCharArray()))
    }

    @Test fun bigPayloadRoundTrips() {
        val big = ByteArray(200_000) { (it % 256).toByte() }
        val blob = BackupCrypto.encrypt(big, "pass".toCharArray())
        assertTrue(blob.size > big.size)
        assertArrayEquals(big, BackupCrypto.decrypt(blob, "pass".toCharArray()))
    }
}
