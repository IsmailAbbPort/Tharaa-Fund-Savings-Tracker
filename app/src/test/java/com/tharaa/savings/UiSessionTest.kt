package com.tharaa.savings

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving the app used to throw away the calculator's inputs and drop you back on Home. The session
 * is now part of the persisted model, so these lock in both halves: it survives a write/read cycle,
 * and it stops being offered once it is stale.
 */
class UiSessionTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val minute = 60L * 1000
    private val now = 1_700_000_000_000L

    private val draft = CalculatorDraft(
        start = "250000", rate = "18", deposit = "5000",
        withdrawal = "", yearlyIncrease = "10", years = "7", months = "6",
    )

    @Test fun calculatorDraftSurvivesPersistence() {
        val saved = SavingsData(
            session = UiSession(UiSession.CALCULATOR, calculator = draft, savedAt = now)
        )
        val reloaded = json.decodeFromString<SavingsData>(json.encodeToString(saved))
        assertEquals(draft, reloaded.session?.calculator)
        assertEquals(UiSession.CALCULATOR, reloaded.session?.screen)
    }

    @Test fun aFreshSessionIsRestorable() {
        val session = UiSession(UiSession.CALCULATOR, calculator = draft, savedAt = now)
        assertTrue(session.isRestorableAt(now))
        assertTrue(session.isRestorableAt(now + 29 * minute))
        assertTrue(session.isRestorableAt(now + UiSession.RESTORE_WINDOW_MS))
    }

    @Test fun aStaleSessionIsNotRestorable() {
        val session = UiSession(UiSession.CALCULATOR, calculator = draft, savedAt = now)
        assertFalse(session.isRestorableAt(now + UiSession.RESTORE_WINDOW_MS + 1))
        assertFalse(session.isRestorableAt(now + 24 * 60 * minute))
    }

    @Test fun aSessionFromTheFutureIsNotRestorable() {
        // The device clock can move backwards; don't hand back a session we can't reason about.
        val session = UiSession(UiSession.HOME, savedAt = now + minute)
        assertFalse(session.isRestorableAt(now))
    }

    @Test fun repositoryOnlyOffersAFreshSession() {
        val d = SavingsData(session = UiSession(UiSession.CALCULATOR, calculator = draft, savedAt = now))
        assertEquals(draft, SavingsRepository.restorableSession(now + minute, d)?.calculator)
        assertNull(SavingsRepository.restorableSession(now + 60 * minute, d))
        assertNull(SavingsRepository.restorableSession(now, SavingsData()))
    }

    @Test fun filesWrittenBeforeSessionsExistedStillLoad() {
        val d = json.decodeFromString<SavingsData>("""{"txns":[]}""")
        assertNull(d.session)
    }
}
