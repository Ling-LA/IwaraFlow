package com.ling.iwaraflow

import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Neutral content only. Record device timings without a flaky emulator FPS threshold. */
@RunWith(AndroidJUnit4::class)
class ReactionPerformanceDeviceTest {
    private val test = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = test.runOnMainSync(block)
    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    @Test fun preparedBurstsAreReusedAndBothHoldAnimationsRemainCancellable() {
        val activity = test.startActivitySync(Intent(test.targetContext, AuthorActivity::class.java)
            .putExtra(AuthorActivity.EXTRA_ID, "fixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AuthorActivity
        val frameThread = HandlerThread("reaction-frame-metrics").apply { start() }
        val frames = ConcurrentLinkedQueue<Long>()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> frames.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION)) }
        lateinit var root: FrameLayout
        lateinit var burst: ReactionBurstView
        lateinit var like: ImageView
        lateinit var screenHold: ScreenReactionHold
        lateinit var buttonHold: HoldReaction
        val screenCompletions = AtomicInteger()
        val buttonCompletions = AtomicInteger()
        val oldDecodeMs = mutableListOf<Double>()
        val reuseMs = mutableListOf<Double>()
        try {
            main {
                (field(activity, "api") as IwaraApi).close()
                fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
                root = FrameLayout(activity).apply { setBackgroundColor(0xFF16334A.toInt()) }
                root.addView(TextView(activity).apply {
                    text = "IwaraFlow\n长按画面三连\n点赞 · 收藏 · 关注"; textSize = 22f
                    setTextColor(-1); gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(-1, dp(170), Gravity.TOP))
                like = ImageView(activity).apply { setImageResource(R.drawable.ic_heart_rounded); imageTintList = android.content.res.ColorStateList.valueOf(0xFFFF365D.toInt()) }
                val favorite = ImageView(activity).apply { setImageResource(R.drawable.ic_star_rounded); imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFD54F.toInt()) }
                root.addView(like, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(150) })
                root.addView(favorite, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(70) })
                burst = ReactionBurstView(activity)
                root.addView(burst, FrameLayout.LayoutParams(-1, -1))
                activity.setContentView(root)
                screenHold = ScreenReactionHold(root) { screenCompletions.incrementAndGet() }
                buttonHold = HoldReaction(like, 0xFFFF365D.toInt(), favorite, 0xFFFFD54F.toInt()) { buttonCompletions.incrementAndGet() }
            }
            test.waitForIdleSync()
            val deadline = SystemClock.uptimeMillis() + 10000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                main { ready = (field(burst, "prepared") as Map<*, *>).size == 2 }
                if (!ready) SystemClock.sleep(50)
            }
            assertTrue("Both GIFs must be decoded off-thread before the reuse check", ready)
            // Reproduce the previous synchronous path, then measure only the new playAt call.
            main {
                val cached = (field(burst, "prepared") as Map<*, *>).toMap()
                repeat(8) {
                    val kind = ReactionBurstView.Kind.entries[it % 2]
                    var start = System.nanoTime()
                    val size = (kind.gifDp * activity.resources.displayMetrics.density).toInt()
                    val decoded = ImageDecoder.decodeDrawable(ImageDecoder.createSource(activity.resources, kind.gifRes)) { decoder, _, _ -> decoder.setTargetSize(size, size) }
                    oldDecodeMs += (System.nanoTime() - start) / 1_000_000.0
                    (decoded as AnimatedImageDrawable).stop()
                    start = System.nanoTime()
                    burst.playAt(root.width / 2f, root.height / 2f, kind)
                    reuseMs += (System.nanoTime() - start) / 1_000_000.0
                    assertSame("playAt must reuse the prepared drawable", cached[kind], field(burst, "gif"))
                    burst.cancelBurst()
                }
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(frameThread.looper))
                screenHold.start(root.width / 2f, root.height / 2f)
            }
            SystemClock.sleep(500)
            main { assertTrue(screenHold.running); screenHold.cancel() }
            SystemClock.sleep(1100)
            assertEquals("Cancelled screen hold must never complete", 0, screenCompletions.get())
            main { screenHold.start(root.width / 2f, root.height / 2f) }
            // Screenshot encoding/export may outlast the hold; keep it outside the cancellation check.
            SystemClock.sleep(400)
            ProfileUiDeviceTest().screenshot("triple-reaction-compact")
            SystemClock.sleep(1700)
            assertEquals(1, screenCompletions.get())
            main { screenHold.cancel() }
            fun touch(action: Int) = main {
                val time = SystemClock.uptimeMillis()
                MotionEvent.obtain(time, time, action, like.width / 2f, like.height / 2f, 0).also { like.dispatchTouchEvent(it); it.recycle() }
            }
            touch(MotionEvent.ACTION_DOWN); SystemClock.sleep(100); touch(MotionEvent.ACTION_UP)
            assertEquals(0, buttonCompletions.get())
            touch(MotionEvent.ACTION_DOWN); SystemClock.sleep(650); touch(MotionEvent.ACTION_CANCEL)
            SystemClock.sleep(1000); assertEquals(0, buttonCompletions.get())
            repeat(2) {
                touch(MotionEvent.ACTION_DOWN); SystemClock.sleep(1700); touch(MotionEvent.ACTION_UP)
                assertEquals(it + 1, buttonCompletions.get())
            }
            main { activity.window.removeOnFrameMetricsAvailableListener(listener) }
            assertTrue("The animation must actually render frames", frames.size > 20)
            fun percentile(values: List<Double>, p: Double) = values.sorted()[((values.size - 1) * p).toInt()]
            val record = org.json.JSONObject().put("sdk", android.os.Build.VERSION.SDK_INT)
                .put("baselineDecodeMedianMs", percentile(oldDecodeMs, .5)).put("preparedPlayMedianMs", percentile(reuseMs, .5))
                .put("preparedPlayMaxMs", reuseMs.max()).put("renderedFrames", frames.size)
                .put("frameP95Ms", percentile(frames.map { it / 1_000_000.0 }, .95))
                .put("note", "Neutral emulator fixture; decode-call comparison, not an end-to-end old/new playback benchmark")
            val report = File(activity.getExternalFilesDir(null), "ui-checks/reaction-performance.json").apply { parentFile?.mkdirs(); writeText(record.toString(2)) }
            android.os.ParcelFileDescriptor.AutoCloseInputStream(test.uiAutomation.executeShellCommand("cp ${report.absolutePath} /sdcard/Download/IwaraFlow-ui-checks/reaction-performance.json")).use { it.readBytes() }
            android.util.Log.i("ReactionPerformance", record.toString())
        } finally {
            main { activity.window.removeOnFrameMetricsAvailableListener(listener); activity.finish() }
            frameThread.quitSafely()
        }
    }
}
