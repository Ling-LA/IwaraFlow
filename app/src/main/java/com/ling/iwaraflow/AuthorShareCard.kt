package com.ling.iwaraflow

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/** A locally rendered profile card, independent of the receiving site's link-preview crawler. */
internal object AuthorShareCard {
    const val WIDTH = 1200
    const val HEIGHT = 630

    internal fun plainText(value: String): String = Html.fromHtml(value.take(6000)
        .replace("\r\n", "\n").replace("\n", "<br>"), Html.FROM_HTML_MODE_LEGACY).toString()
        .replace(Regex("!?\\[([^]\\n]*)]\\([^\\n)]*\\)"), "$1")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        .replace(Regex("[\\t \\u00A0]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()

    fun render(author: IwaraAuthor, avatar: Bitmap?): Bitmap {
        val image = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawColor(Color.WHITE)
        paint.color = 0xFFEAF2F8.toInt()
        canvas.drawRoundRect(RectF(1f, 1f, 1199f, 629f), 30f, 30f, paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(3f, 3f, 1197f, 627f), 28f, 28f, paint)
        paint.color = 0xFFF5F8FC.toInt()
        canvas.drawRoundRect(RectF(32f, 32f, 1168f, 218f), 24f, 24f, paint)

        val avatarBounds = RectF(64f, 60f, 192f, 188f)
        paint.color = 0xFFDAE7F1.toInt()
        canvas.drawOval(avatarBounds, paint)
        if (avatar != null && !avatar.isRecycled) {
            val scale = maxOf(128f / avatar.width, 128f / avatar.height)
            val shader = BitmapShader(avatar, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(Matrix().apply {
                setScale(scale, scale)
                postTranslate(64f + (128f - avatar.width * scale) / 2, 60f + (128f - avatar.height * scale) / 2)
            })
            paint.shader = shader; canvas.drawOval(avatarBounds, paint); paint.shader = null
        } else {
            // Neutral silhouette; the card remains usable without a loaded avatar.
            paint.color = 0xFF8EAAC0.toInt()
            canvas.drawCircle(128f, 106f, 23f, paint)
            canvas.drawRoundRect(RectF(88f, 137f, 168f, 177f), 22f, 22f, paint)
        }

        fun text(value: String, x: Float, y: Float, width: Int, size: Float, color: Int, lines: Int, bold: Boolean = false) {
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = size; this.color = color
                typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, textPaint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setMaxLines(lines)
                .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(7f, 1f).build()
            canvas.save(); canvas.translate(x, y); layout.draw(canvas); canvas.restore()
        }
        val name = plainText(author.name).ifBlank { author.username }.take(200)
        text(name, 224f, 74f, 876, 48f, 0xFF17324A.toInt(), 1, true)
        text("@${author.username.take(180)}", 224f, 145f, 876, 28f, 0xFF61798A.toInt(), 1)
        text(plainText(author.description), 64f, 252f, 1072, 34f, 0xFF29465A.toInt(), 4)
        paint.color = 0xFF9B7DEA.toInt(); canvas.drawRect(34f, 491f, 1166f, 497f, paint)
        text("Iwara", 64f, 520f, 900, 30f, 0xFF17324A.toInt(), 1, true)
        text(VideoShare.authorLinkFor(author).take(400), 64f, 566f, 1072, 24f, 0xFF61798A.toInt(), 1)
        return image
    }
}
