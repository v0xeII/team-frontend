package ru.nto.guestmanagement.mobile.ui.refresh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshGateTest {

    @Test
    fun secondLaunchIsIgnoredWhileFirstIsRunning() = runBlocking {
        val gate = RefreshGate()
        val release = CompletableDeferred<Unit>()
        var runs = 0

        val first = gate.launch(this) { runs++; release.await() }
        val second = gate.launch(this) { runs++ }

        assertNotNull(first)
        assertNull(second)
        release.complete(Unit)
        first!!.join()
        assertEquals(1, runs)
    }

    @Test
    fun launchWorksAgainAfterPreviousFinished() = runBlocking {
        val gate = RefreshGate()
        var runs = 0

        gate.launch(this) { runs++ }!!.join()
        gate.launch(this) { runs++ }!!.join()

        assertEquals(2, runs)
    }

    @Test
    fun cancelStopsCurrentAndAllowsNewLaunch() = runBlocking {
        val gate = RefreshGate()
        val never = CompletableDeferred<Unit>()

        val first = gate.launch(this) { never.await() }!!
        gate.cancel()
        val second = gate.launch(this) { }

        assertTrue(first.isCancelled)
        assertNotNull(second)
        second!!.join()
    }
}
