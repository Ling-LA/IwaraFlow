package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * 按住画面左右滑动调进度：小幅是微调，划得越远跨度越大，且始终不会滑出这条视频。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SeekGestureTest {
    private val width = 1080
    private val tenMinutes = 10 * 60 * 1000L

    private fun target(from: Long, dx: Float, duration: Long = tenMinutes) =
        VideoAdapter.seekTargetFor(from, dx, width, duration)

    @Test fun noMovementKeepsThePosition() {
        assertEquals(60_000L, target(60_000L, 0f))
    }

    @Test fun draggingRightGoesForwardAndLeftGoesBack() {
        val from = 5 * 60 * 1000L
        assertTrue(target(from, width * 0.5f) > from)
        assertTrue(target(from, -width * 0.5f) < from)
    }

    @Test fun aSmallDragIsAFineAdjustment() {
        val from = 5 * 60 * 1000L
        // 划过屏幕的十分之一：几秒的微调，而不是几十秒。
        val delta = target(from, width * 0.1f) - from
        assertTrue("十分之一屏应该只调几秒，实际 ${delta}ms", delta in 1_000L..8_000L)
    }

    @Test fun aBigDragCoversMuchMore() {
        val from = 5 * 60 * 1000L
        val small = target(from, width * 0.25f) - from
        val half = target(from, width * 0.5f) - from
        val full = target(from, width * 1f) - from
        assertTrue("越划越远跨度越大", small < half && half < full)
        assertEquals("划满一屏就是整个可调范围", VideoAdapter.MAX_SEEK_RANGE_MS, full)
        assertTrue("不是线性的：半屏远不到满屏的一半", half < full / 2)
    }

    @Test fun theRangeNeverExceedsTheVideoLength() {
        val short = 90_000L
        assertEquals("短片划满一屏最多到片尾", short, target(0L, width * 1f, short))
        assertEquals("往回划最多到开头", 0L, target(30_000L, -width * 1f, short))
    }

    @Test fun theResultStaysInsideTheVideo() {
        val duration = 120_000L
        listOf(-3f, -1f, -0.2f, 0f, 0.2f, 1f, 3f).forEach { ratio ->
            val t = target(60_000L, width * ratio, duration)
            assertTrue("$ratio 屏算出来的 $t 越界了", t in 0L..duration)
        }
    }

    @Test fun aZeroWidthCardDoesNotMoveThePlayhead() {
        assertEquals(42_000L, VideoAdapter.seekTargetFor(42_000L, 500f, 0, tenMinutes))
        assertEquals(42_000L, VideoAdapter.seekTargetFor(42_000L, 500f, width, 0L))
    }

    @Test fun theCurveIsSymmetric() {
        val from = 5 * 60 * 1000L
        val forward = target(from, width * 0.4f) - from
        val back = from - target(from, -width * 0.4f)
        assertEquals("往前往后的幅度应该一样", forward.toDouble(), back.toDouble(), 1.0)
        assertTrue(abs(forward) > 0)
    }
}
