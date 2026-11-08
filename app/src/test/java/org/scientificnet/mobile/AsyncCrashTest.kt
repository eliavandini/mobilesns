package org.scientificnet.mobile

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.scientificnet.mobile.ui.loadBoth
import java.io.IOException

/**
 * Regression test for a launch crash: two `async` calls directly inside a `launch` on a
 * SupervisorJob scope (like viewModelScope) leak a failure to the uncaught-exception handler
 * even though `await()` is wrapped in try/catch.
 */
class AsyncCrashTest {
    private suspend fun failing(): Int = throw IOException("token expired")
    private suspend fun ok(): Int = 1

    private fun runInViewModelLikeScope(block: suspend CoroutineScope.() -> Unit): Throwable? = runBlocking {
        var uncaught: Throwable? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught = e })
        listOf(scope.launch { block() }).joinAll()
        uncaught
    }

    @Test fun oldPatternLeaksExceptionToUncaughtHandler() {
        var caught: Throwable? = null
        val uncaught = runInViewModelLikeScope {
            try {
                val a = async { failing() }
                val b = async { ok() }
                a.await() + b.await()
            } catch (e: Exception) {
                caught = e
            }
        }
        // The try/catch sees it, but it ALSO reaches the uncaught handler (= app crash on Android).
        assertEquals("token expired", uncaught?.message)
    }

    @Test fun loadBothKeepsFailureInsideTryCatch() {
        var caught: Throwable? = null
        val uncaught = runInViewModelLikeScope {
            try {
                loadBoth({ failing() }, { ok() })
            } catch (e: Exception) {
                caught = e
            }
        }
        assertNull(uncaught)
        assertEquals("token expired", caught?.message)
    }
}
