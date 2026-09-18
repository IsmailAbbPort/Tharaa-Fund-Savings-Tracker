package com.tharaa.savings

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The data model and its JSON persistence shape (kotlinx.serialization runs on the plain JVM). */
class SavingsDataTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val day = 24L * 60 * 60 * 1000

    @Test fun defaultsSeedAnEighteenPercentRate() {
        val d = SavingsData()
        assertEquals(1, d.rateChanges.size)
        assertEquals(1800, d.rateChanges.first().annualRateBps)
        assertEquals(1800, d.currentRateBps)
    }

    @Test fun defaultsSeedTwoLabelsWithStableIds() {
        val d = SavingsData()
        assertEquals(2, d.labels.size)
        assertEquals("Serious savings", d.labelById(SavingsData.SERIOUS_ID)?.name)
        assertEquals("Fun saving", d.labelById(SavingsData.FUN_ID)?.name)
        assertEquals(null, d.labelById("nope"))
    }

    @Test fun hasPasscodeNeedsBothHashAndSalt() {
        assertFalse(SavingsData().hasPasscode)
        assertFalse(SavingsData(passcodeHash = "h").hasPasscode)
        assertFalse(SavingsData(passcodeSalt = "s").hasPasscode)
        assertTrue(SavingsData(passcodeHash = "h", passcodeSalt = "s").hasPasscode)
    }

    @Test fun currentRateIsTheLatestByEffectiveDate() {
        val d = SavingsData(
            rateChanges = listOf(
                RateChange(effectiveTimestamp = 0L, annualRateBps = 1800),
                RateChange(effectiveTimestamp = 200 * day, annualRateBps = 2000),
                RateChange(effectiveTimestamp = 100 * day, annualRateBps = 1500),
            )
        )
        assertEquals(2000, d.currentRateBps)
    }

    @Test fun serializationRoundTripsAllState() {
        val original = SavingsData(
            txns = listOf(
                Txn(labelId = "SERIOUS", type = TxnType.DEPOSIT, amountMinor = 500_000, timestamp = day, note = "salary"),
                Txn(labelId = "FUN", type = TxnType.WITHDRAWAL, amountMinor = 20_000, timestamp = 2 * day),
            ),
            rateChanges = listOf(
                RateChange(effectiveTimestamp = 0L, annualRateBps = 1800),
                RateChange(effectiveTimestamp = 100 * day, annualRateBps = 1600),
            ),
            passcodeHash = "hash", passcodeSalt = "salt", biometricEnabled = true,
        )
        val restored = json.decodeFromString<SavingsData>(json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test fun decodingOlderJsonWithoutNewFieldsUsesDefaults() {
        // Backward compatibility: a file written by a leaner build must still load.
        val minimal = """{"txns":[]}"""
        val d = json.decodeFromString<SavingsData>(minimal)
        assertTrue(d.txns.isEmpty())
        assertEquals(1800, d.currentRateBps)   // seeded default rate
        assertFalse(d.hasPasscode)
        assertFalse(d.biometricEnabled)
    }

    @Test fun decodingIgnoresUnknownKeys() {
        // A field removed in a later build must not crash decoding of an old file.
        val withExtra = """{"txns":[],"someRemovedFlag":true}"""
        val d = json.decodeFromString<SavingsData>(withExtra)
        assertTrue(d.txns.isEmpty())
    }
}
