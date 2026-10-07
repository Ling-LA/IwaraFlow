package com.ling.iwaraflow

import android.graphics.RectF

/** Guide bands use the same full item coordinates as FullscreenGesture, never redivide a cropped area. */
internal object PlaybackGuideLayout {
    fun verticalBands(surface: RectF): List<RectF> = List(3) { i ->
        RectF(surface.left, surface.top + surface.height()*i/3f, surface.right, surface.top + surface.height()*(i+1)/3f)
    }
    fun clearLabelArea(band: RectF, available: RectF, controls: List<RectF>): RectF {
        val initial = RectF(band)
        if (!initial.intersect(available)) return RectF()
        var pieces = listOf(initial)
        controls.forEach { control ->
            pieces = pieces.flatMap { r ->
                val overlap = RectF(r)
                if (!overlap.intersect(control)) listOf(r) else listOf(
                    RectF(r.left, r.top, r.right, overlap.top),
                    RectF(r.left, overlap.bottom, r.right, r.bottom),
                    RectF(r.left, overlap.top, overlap.left, overlap.bottom),
                    RectF(overlap.right, overlap.top, r.right, overlap.bottom)
                ).filter { !it.isEmpty }
            }
        }
        return pieces.maxByOrNull { it.width()*it.height() } ?: RectF()
    }
}
