package com.tharaa.savings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ReminderSchedulerTest {

    private fun cal(year: Int, month0: Int, day: Int, hour: Int) = Calendar.getInstance().apply {
        timeZone = TimeZone.getDefault()
        set(year, month0, day, hour, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }

    @Test fun picksThisMonthWhenTheDayIsStillAhead() {
        val from = cal(2026, Calendar.MARCH, 5, 9).timeInMillis
        val next = ReminderScheduler.nextTrigger(15, from)
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(Calendar.MARCH, c.get(Calendar.MONTH))
        assertEquals(15, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(10, c.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun rollsToNextMonthWhenTheDayHasPassed() {
        val from = cal(2026, Calendar.MARCH, 20, 9).timeInMillis
        val next = ReminderScheduler.nextTrigger(15, from)
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(Calendar.APRIL, c.get(Calendar.MONTH))
        assertEquals(15, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun nextTriggerIsAlwaysInTheFuture() {
        val from = cal(2026, Calendar.JANUARY, 31, 12).timeInMillis
        assertTrue(ReminderScheduler.nextTrigger(28, from) > from)
    }
}
