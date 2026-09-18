package com.tharaa.savings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test fun parsesPlainNumber() {
        assertEquals(123450L, Money.parseToMinor("1234.50"))
    }

    @Test fun parsesWithGroupingCommas() {
        assertEquals(123450L, Money.parseToMinor("1,234.50"))
    }

    @Test fun parsesInteger() {
        assertEquals(100000L, Money.parseToMinor("1000"))
    }

    @Test fun roundsHalfUp() {
        assertEquals(101L, Money.parseToMinor("1.005"))
    }

    @Test fun rejectsGarbage() {
        assertNull(Money.parseToMinor("abc"))
        assertNull(Money.parseToMinor(""))
    }

    @Test fun formatsWithTwoDecimalsAndGrouping() {
        assertEquals("1,234.50", Money.formatMinor(123450))
        assertEquals("0.00", Money.formatMinor(0))
        assertEquals("1,000,000.00", Money.formatMinor(100_000_000))
    }

    @Test fun formatsNegative() {
        assertEquals("-500.00", Money.formatMinor(-50000))
    }
}
