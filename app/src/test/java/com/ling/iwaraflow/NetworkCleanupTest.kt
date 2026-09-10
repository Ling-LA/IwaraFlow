package com.ling.iwaraflow

import android.app.Activity
import android.os.Looper
import android.os.NetworkOnMainThreadException
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NetworkCleanupTest {
    private fun setClient(owner: Any, field: String, client: OkHttpClient) {
        owner.javaClass.getDeclaredField(field).apply { isAccessible = true }.set(owner, client)
    }

    private fun assertBackgroundClose(close: (OkHttpClient) -> Unit) {
        val evicted = CountDownLatch(1)
        val pool = mock(ConnectionPool::class.java)
        doAnswer {
            // Android's Conscrypt TLS socket enforces this when close() writes close_notify.
            if (Looper.myLooper() == Looper.getMainLooper()) throw NetworkOnMainThreadException()
            evicted.countDown()
            null
        }.`when`(pool).evictAll()
        val client = OkHttpClient.Builder().connectionPool(pool).build()
        close(client)
        assertTrue("TLS connections were not released", evicted.await(5, TimeUnit.SECONDS))
        assertTrue(client.dispatcher.executorService.isShutdown)
    }

    @Test fun apiCloseReleasesTlsConnectionsOffMainThread() = assertBackgroundClose { client ->
        val api = IwaraApi(RuntimeEnvironment.getApplication())
        setClient(api, "client", client)
        api.close()
    }

    @Test fun playableGateCloseReleasesTlsConnectionsOffMainThread() = assertBackgroundClose { client ->
        val gate = PlayableVideoGate(mock(IwaraApi::class.java))
        setClient(gate, "client", client)
        gate.close()
    }

    @Test fun followingDestroyReleasesProfileConnectionsOffMainThread() = assertBackgroundClose { client ->
        val controller = Robolectric.buildActivity(FollowingActivity::class.java).create()
        setClient(controller.get(), "profileClient", client)
        controller.destroy()
    }

    @Test fun updaterCloseReleasesTlsConnectionsOffMainThread() = assertBackgroundClose { client ->
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val updates = UpdateManager(controller.get())
        setClient(updates, "client", client)
        updates.close()
        controller.pause().stop().destroy()
    }

    @Test fun closedGateIgnoresLateInspectionRequests() {
        val gate = PlayableVideoGate(mock(IwaraApi::class.java))
        gate.close()
        gate.filterPlayable(emptyList(), "highest") { fail("Closed gate delivered results") }
        gate.inspectAll(emptyList(), "highest") { fail("Closed gate delivered results") }
        gate.close()
    }
}
