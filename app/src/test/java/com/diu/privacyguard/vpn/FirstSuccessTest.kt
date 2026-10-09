package com.diu.privacyguard.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstSuccessTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private fun timed(block: suspend () -> Outcome<String>?): Pair<Outcome<String>?, Long> = runBlocking {
        val start = System.currentTimeMillis()
        val result = block()
        result to System.currentTimeMillis() - start
    }

    @Test
    fun fastFirstRouteWinsWithoutStartingTheOthers() {
        var laterStarted = false
        val (result, _) = timed {
            firstSuccess(
                scope,
                listOf(
                    Attempt(0, "system") { "answer" },
                    Attempt(500, "server") { laterStarted = true; "late" },
                ),
                timeoutMs = 2_000,
            )
        }
        assertEquals("system", result?.label)
        Thread.sleep(700)
        assertTrue("later route should not run after a win", !laterStarted)
    }

    @Test
    fun failedRouteStartsTheNextImmediately() {
        val (result, ms) = timed {
            firstSuccess(
                scope,
                listOf(
                    Attempt(0, "system") { null },
                    Attempt(1_000, "server") { "answer" },
                ),
                timeoutMs = 3_000,
            )
        }
        assertEquals("server", result?.label)
        assertTrue("took $ms ms; should not wait the 1 s delay", ms < 500)
    }

    @Test
    fun slowRouteIsOvertakenByALaterFastOne() {
        val (result, ms) = timed {
            firstSuccess(
                scope,
                listOf(
                    Attempt(0, "system") { delay(5_000); "too slow" },
                    Attempt(200, "server") { "answer" },
                ),
                timeoutMs = 3_000,
            )
        }
        assertEquals("server", result?.label)
        assertTrue("took $ms ms", ms < 1_000)
    }

    @Test
    fun allRoutesFailingReturnsNullWithoutWaitingForTheTimeout() {
        val (result, ms) = timed {
            firstSuccess(
                scope,
                listOf(Attempt(0, "a") { null }, Attempt(300, "b") { error("socket broke") }),
                timeoutMs = 5_000,
            )
        }
        assertNull(result)
        assertTrue("took $ms ms", ms < 1_000)
    }

    @Test
    fun timeoutReturnsNull() {
        val (result, ms) = timed {
            firstSuccess(scope, listOf(Attempt(0, "a") { delay(5_000); "late" }), timeoutMs = 300)
        }
        assertNull(result)
        assertTrue("took $ms ms", ms < 1_000)
    }
}
