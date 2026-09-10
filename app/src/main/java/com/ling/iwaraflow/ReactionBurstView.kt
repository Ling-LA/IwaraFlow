package com.ling.iwaraflow

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.content.res.AppCompatResources
import kotlin.math.cos
import kotlin.math.sin

/**
 * 点赞 / 收藏动画。以前只有双击时在屏幕正中央弹一个大爱心，按按钮没有任何反馈。
 * 现在动画播在“点的那个地方”：点按钮就在按钮上播，双击视频就在手指那里播。
 *
 * 一次动画分三层：图标先弹大再回落并淡出、外面一圈涟漪扩散、几颗小点向四周飞散。
 */
class ReactionBurstView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Kind(val iconRes: Int, val color: Int) {
        LIKE(R.drawable.ic_heart_rounded, 0xFFFF365D.toInt()),
        FAVORITE(R.drawable.ic_star_rounded, 0xFFFFD54F.toInt())
    }

    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var icon: Drawable? = null
    private var color = Color.WHITE
    private var animator: ValueAnimator? = null
    private var progress = 0f

    /** 上一次动画的中心，本视图坐标系。空动画时保持上一次的值。 */
    var burstCenterX = 0f
        private set
    var burstCenterY = 0f
        private set

    /** 在某个按钮上播放：动画的中心就是这个按钮的中心。 */
    fun playOn(anchor: View, kind: Kind) {
        val anchorAt = IntArray(2)
        val selfAt = IntArray(2)
        anchor.getLocationOnScreen(anchorAt)
        getLocationOnScreen(selfAt)
        playAt(
            anchorAt[0] - selfAt[0] + anchor.width / 2f,
            anchorAt[1] - selfAt[1] + anchor.height / 2f,
            kind
        )
    }

    fun playAt(x: Float, y: Float, kind: Kind) {
        burstCenterX = x
        burstCenterY = y
        color = kind.color
        icon = AppCompatResources.getDrawable(context, kind.iconRes)?.mutate()?.also { it.setTint(color) }
        animator?.cancel()
        progress = 0f
        visibility = VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    progress = 0f
                    visibility = GONE
                }
            })
            start()
        }
    }

    fun cancelBurst() {
        animator?.cancel()
        animator = null
        progress = 0f
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelBurst()
    }

    override fun onDraw(canvas: Canvas) {
        val drawable = icon ?: return
        val t = progress
        if (t <= 0f) return

        // 图标：先冲到最大再回落一点，后半程淡出。
        val scale = if (t < POP_AT) lerp(0.25f, 1.2f, t / POP_AT)
        else lerp(1.2f, 0.94f, (t - POP_AT) / (1f - POP_AT))
        val iconAlpha = if (t < FADE_AT) 1f else (1f - (t - FADE_AT) / (1f - FADE_AT)).coerceAtLeast(0f)
        val half = ICON_DP * density * scale / 2f
        drawable.alpha = (iconAlpha * 255).toInt().coerceIn(0, 255)
        drawable.setBounds(
            (burstCenterX - half).toInt(), (burstCenterY - half).toInt(),
            (burstCenterX + half).toInt(), (burstCenterY + half).toInt()
        )
        drawable.draw(canvas)

        // 涟漪：只在前半程，越扩越细越淡。
        if (t < RING_UNTIL) {
            val ringT = t / RING_UNTIL
            ringPaint.color = color
            ringPaint.alpha = ((1f - ringT) * 140).toInt().coerceIn(0, 255)
            ringPaint.strokeWidth = lerp(3.5f, 0.8f, ringT) * density
            canvas.drawCircle(burstCenterX, burstCenterY, lerp(6f, 34f, ringT) * density, ringPaint)
        }

        // 飞散的小点。
        particlePaint.color = color
        particlePaint.alpha = ((1f - t) * 220).toInt().coerceIn(0, 255)
        val distance = lerp(10f, 32f, t) * density
        val dotRadius = lerp(3.2f, 0.6f, t) * density
        for (i in 0 until PARTICLES) {
            val angle = (Math.PI * 2 * i / PARTICLES - Math.PI / 2).toFloat()
            canvas.drawCircle(
                burstCenterX + cos(angle) * distance,
                burstCenterY + sin(angle) * distance,
                dotRadius,
                particlePaint
            )
        }
    }

    private fun lerp(from: Float, to: Float, fraction: Float) =
        from + (to - from) * fraction.coerceIn(0f, 1f)

    companion object {
        const val DURATION_MS = 720L
        private const val ICON_DP = 62f
        private const val PARTICLES = 7
        private const val POP_AT = 0.42f
        private const val FADE_AT = 0.6f
        private const val RING_UNTIL = 0.7f
    }
}
