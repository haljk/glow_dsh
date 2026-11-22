package dev.haljk.glow.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The banner clock is the only place a user watches a number that changes, so its
 * wording is worth pinning: 43 秒, then 1 分 5 秒, then 2 分 0 秒 — not 65 秒 and
 * not "1 分 5 秒" for 60 seconds flat.
 *
 * A plain JUnit test: the function is pure, and it is called every second from the
 * banner so a wrong branch would be visible for the whole job.
 */
class ElapsedLabelTest {

    @Test
    fun `under a minute stays in seconds`() {
        assertEquals("0 秒", elapsedLabel(0))
        assertEquals("43 秒", elapsedLabel(43))
        assertEquals("59 秒", elapsedLabel(59))
    }

    @Test
    fun `one minute switches to minutes and seconds`() {
        assertEquals("1 分 0 秒", elapsedLabel(60))
        assertEquals("1 分 5 秒", elapsedLabel(65))
        assertEquals("1 分 59 秒", elapsedLabel(119))
    }

    @Test
    fun `long jobs keep counting`() {
        assertEquals("2 分 0 秒", elapsedLabel(120))
        assertEquals("3 分 20 秒", elapsedLabel(200))
        // A full retouch takes about seven minutes, so the high end matters.
        assertEquals("7 分 12 秒", elapsedLabel(432))
    }
}
