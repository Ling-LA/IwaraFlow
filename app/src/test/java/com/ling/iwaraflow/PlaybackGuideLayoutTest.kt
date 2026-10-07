package com.ling.iwaraflow

import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackGuideLayoutTest {
    @Test fun excludingChromeDoesNotMoveActualThirdBoundaries() {
        val surface = RectF(0f, 24f, 400f, 924f)
        val available = RectF(12f, 140f, 310f, 780f)
        val bands = PlaybackGuideLayout.verticalBands(surface)
        assertEquals(324f, bands[0].bottom, 0f); assertEquals(624f, bands[1].bottom, 0f)
        bands.forEachIndexed { i, band ->
            val label = PlaybackGuideLayout.clearLabelArea(band, available, emptyList())
            assertTrue(available.contains(label)); assertTrue(band.contains(label))
            assertEquals(FullscreenGesture.Action.entries[i], FullscreenGesture.action(label.centerX(), label.centerY()-surface.top, 400, 900, false))
        }
    }
    @Test fun labelsAvoidPauseControlsAndEmptyBandsDoNotBecomeFullScreen() {
        val band = RectF(0f, 300f, 400f, 600f)
        val available = RectF(12f, 100f, 310f, 780f)
        val controls = listOf(RectF(0f, 390f, 400f, 450f), RectF(0f, 300f, 90f, 390f))
        val label = PlaybackGuideLayout.clearLabelArea(band, available, controls)
        assertTrue(available.contains(label)); assertTrue(band.contains(label))
        assertTrue(controls.none { RectF.intersects(it, label) })
        assertTrue(PlaybackGuideLayout.clearLabelArea(band, RectF(0f, 700f, 400f, 800f), controls).isEmpty)
    }
}
