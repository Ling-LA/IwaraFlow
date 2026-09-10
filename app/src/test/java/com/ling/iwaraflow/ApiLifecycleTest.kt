package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ApiLifecycleTest {
    @Test fun lateFollowingPageRequestAfterCloseDoesNotCrash() {
        val api = IwaraApi(RuntimeEnvironment.getApplication())
        api.close()
        // A current-user/page response may arrive after FollowingActivity.onDestroy.
        api.getFollowingUsers("fixture-user", 1) { fail("Closed page received a callback") }
        api.close()
    }

    @Test fun activeRequestStillDeliversItsResult() {
        val api = IwaraApi(RuntimeEnvironment.getApplication())
        val delivered = CountDownLatch(1)
        var value: Any? = null
        try {
            enqueue(api, { value = it; delivered.countDown() }) { 42 }
            assertTrue(delivered.await(5, TimeUnit.SECONDS))
            assertEquals(42, value)
        } finally { api.close() }
    }

    @Test fun responseCompletingAfterCloseCannotCallDestroyedPage() {
        val api = IwaraApi(RuntimeEnvironment.getApplication())
        val started = CountDownLatch(1)
        val finishRequest = CountDownLatch(1)
        val delivered = AtomicBoolean(false)
        val executor = api.javaClass.getDeclaredField("io").apply { isAccessible = true }.get(api) as ExecutorService
        try {
            enqueue(api, { delivered.set(true) }) {
                started.countDown()
                // Simulate an in-flight response which completes despite cancellation.
                while (true) {
                    try { finishRequest.await(); break } catch (_: InterruptedException) { }
                }
                42
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            api.close()
            finishRequest.countDown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            assertFalse("A late response reached a destroyed page", delivered.get())
        } finally {
            finishRequest.countDown()
            api.close()
        }
    }

    private fun enqueue(api: IwaraApi, callback: (Any?) -> Unit, request: () -> Any?) {
        api.javaClass.declaredMethods.single { it.name == "enqueue" }
            .apply { isAccessible = true }.invoke(api, callback, request)
    }
}
