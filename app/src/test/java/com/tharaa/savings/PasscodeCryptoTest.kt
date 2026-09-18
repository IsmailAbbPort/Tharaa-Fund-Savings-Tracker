package com.tharaa.savings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasscodeCryptoTest {

    @Test fun sameSaltAndPinGivesSameHash() {
        val salt = PasscodeCrypto.newSalt()
        assertEquals(PasscodeCrypto.hash("1234", salt), PasscodeCrypto.hash("1234", salt))
    }

    @Test fun differentSaltGivesDifferentHash() {
        val a = PasscodeCrypto.hash("1234", PasscodeCrypto.newSalt())
        val b = PasscodeCrypto.hash("1234", PasscodeCrypto.newSalt())
        assertNotEquals(a, b)
    }

    @Test fun newSaltIsRandomEachTime() {
        assertNotEquals(PasscodeCrypto.newSalt(), PasscodeCrypto.newSalt())
    }

    @Test fun verifyAcceptsTheCorrectPin() {
        val salt = PasscodeCrypto.newSalt()
        val hash = PasscodeCrypto.hash("4271", salt)
        assertTrue(PasscodeCrypto.verify("4271", salt, hash))
    }

    @Test fun verifyRejectsAWrongPin() {
        val salt = PasscodeCrypto.newSalt()
        val hash = PasscodeCrypto.hash("4271", salt)
        assertFalse(PasscodeCrypto.verify("0000", salt, hash))
    }

    @Test fun theStoredHashIsNotThePinItself() {
        val salt = PasscodeCrypto.newSalt()
        val hash = PasscodeCrypto.hash("1234", salt)
        assertFalse(hash.contains("1234"))
    }
}
