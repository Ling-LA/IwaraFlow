package com.ling.iwaraflow

import android.content.Context
import androidx.core.content.ContextCompat

object UiPalette {
    fun resolve(context: Context, light: Int): Int { return ContextCompat.getColor(context, when (light) {
        0xFFEEF8FE.toInt() -> R.color.page_background
        0xFFDDF1FC.toInt() -> R.color.page_header
        0xFF17324A.toInt() -> R.color.page_text
        0xFF607D93.toInt(), 0xFF8A9BAA.toInt(), 0xFF48697F.toInt() -> R.color.page_secondary
        0xFF285C7B.toInt() -> R.color.page_accent
        0xFFD7E9F4.toInt() -> R.color.page_border
        0xFFFFFFFF.toInt() -> R.color.page_surface
        else -> return light
    })
    }
}
