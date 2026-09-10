package com.ling.iwaraflow

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Reproduce Android's actual Conscrypt TLS-close behavior without an external service. */
@RunWith(AndroidJUnit4::class)
class HttpsCleanupDeviceTest {
    @Test fun closingApiWithAnIdleHttpsConnectionDoesNotCrashTheUiThread() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverTls.sslSocketFactory(), false)
        server.protocols = listOf(Protocol.HTTP_1_1)
        server.enqueue(MockResponse().setBody("TLS cleanup fixture"))
        server.start()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1)).build()
        val api = IwaraApi(instrumentation.targetContext)
        api.javaClass.getDeclaredField("client").apply { isAccessible = true }.set(api, client)
        try {
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { response ->
                assertEquals("TLS cleanup fixture", response.body!!.string())
            }
            assertEquals("The test needs a live idle TLS socket", 1, client.connectionPool.idleConnectionCount())
            instrumentation.runOnMainSync { api.close() }
            val deadline = SystemClock.uptimeMillis() + 5_000
            while (client.connectionPool.connectionCount() > 0 && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(20)
            assertEquals("Closed Activity retained a TLS connection", 0, client.connectionPool.connectionCount())
        } finally {
            api.close()
            client.connectionPool.evictAll() // Instrumentation worker, never the UI thread.
            server.shutdown()
        }
    }
}
