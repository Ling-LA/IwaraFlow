package com.ling.iwaraflow

import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real overlay window and foreground service; no account or public media is used. */
@RunWith(AndroidJUnit4::class)
class FloatingPlayerDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun instance(): FloatingVideoService? = FloatingVideoService::class.java.getDeclaredField("instance")
        .apply { isAccessible = true }.get(null) as? FloatingVideoService
    private fun field(service: FloatingVideoService, name: String): Any? = service.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(service)

    @Test fun customWindowHasOnlyReturnCloseAndPlayControlsAndStopsOnClose() {
        val context = instrumentation.targetContext
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        val activity = instrumentation.startActivitySync(Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val file = File(context.cacheDir, "floating-fixture.mp4")
        instrumentation.context.assets.open("navigation-fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            main {
                context.startForegroundService(Intent(context, FloatingVideoService::class.java)
                    .putExtra("url", Uri.fromFile(file).toString()).putExtra("id", "floating-fixture")
                    .putExtra("title", "Local playback fixture").putExtra("return", Intent(context, SettingsActivity::class.java)))
            }
            val deadline = SystemClock.uptimeMillis() + 15000L
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                main { instance()?.let { ready = (field(it, "window") as? FrameLayout)?.isLaidOut == true } }
                if (!ready) SystemClock.sleep(50)
            }
            assertTrue("Overlay should be attached", ready)
            main {
                val service = instance()!!
                val root = field(service, "window") as FrameLayout
                val controls = (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<TextView>()
                assertEquals(3, controls.size)
                val back = controls.single { it.contentDescription == "返回软件" }
                val close = controls.single { it.contentDescription == "关闭小窗" }
                val toggle = controls.single { it.contentDescription == "播放或暂停" }
                assertTrue(back.left < close.left)
                assertTrue(toggle.top > close.top)
                val player = field(service, "player") as ExoPlayer
                assertTrue(player.playWhenReady)
                toggle.performClick(); assertFalse(player.playWhenReady)
                toggle.performClick(); assertTrue(player.playWhenReady)
                close.performClick()
                assertNull(field(service, "player"))
                assertNull(instance())
            }
        } finally {
            main { context.stopService(Intent(context, FloatingVideoService::class.java)); activity.finish() }
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }
}
