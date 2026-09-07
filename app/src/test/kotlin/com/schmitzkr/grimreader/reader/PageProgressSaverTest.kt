package com.schmitzkr.grimreader.reader

import com.schmitzkr.grimreader.core.model.PageProgress
import com.schmitzkr.grimreader.ui.reader.PageProgressSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PageProgressSaverTest {
    private fun saver(
        scope: CoroutineScope,
        saved: MutableList<PageProgress>,
        persist: suspend (PageProgress) -> Unit = { saved += it },
        onError: (Throwable) -> Unit = {},
    ) = PageProgressSaver(scope, pageCount = 10, pageNumberAt = { it + 1 }, persist = persist, onError = onError)

    @Test
    fun `flipping through pages collapses into one save of the newest`() = runTest {
        val saved = mutableListOf<PageProgress>()
        val s = saver(this, saved)
        s.pageChanged(1)
        s.pageChanged(2)
        s.pageChanged(3)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf(4), saved.map { it.page })
        assertEquals(40.0, saved.single().percentage, 0.001)
    }

    @Test
    fun `saveNow drops the pending passive save and saves the given page`() = runTest {
        val saved = mutableListOf<PageProgress>()
        val s = saver(this, saved)
        s.pageChanged(5)
        assertTrue(s.saveNow(7))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(8), saved.map { it.page })
    }

    @Test
    fun `a failing save is reported and returns false`() = runTest {
        val errors = mutableListOf<Throwable>()
        val s = saver(this, mutableListOf(), persist = { error("offline") }, onError = { errors += it })
        assertFalse(s.saveNow(0))
        assertEquals(1, errors.size)
    }

    @Test
    fun `the last page saves as one hundred percent`() = runTest {
        val saved = mutableListOf<PageProgress>()
        val s = saver(this, saved)
        s.saveNow(9)
        assertEquals(100.0, saved.single().percentage, 0.001)
    }
}
