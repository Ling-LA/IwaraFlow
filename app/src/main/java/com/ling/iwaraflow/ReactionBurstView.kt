package com.ling.iwaraflow

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.content.res.AppCompatResources
import kotlin.math.cos
import kotlin.math.sin

/**
 * 点赞 / 收藏动画。以前只有双击时在屏幕正中央弹一个大爱心，按按钮没有任何反馈。
 * 现在动画播在“点的那个地方”：点按钮就在按钮上播，双击视频就在手指那里播。
 *
 * 播的是 res/raw 里的两段 GIF。minSdk 是 28，系统自带的 AnimatedImageDrawable
 * 就能解 GIF，不用引第三方库。万一解码失败（厂商魔改、素材损坏），
 * 退回一套自己画的动画：图标弹出、一圈涟漪扩散、几颗小点飞散。
 */
class ReactionBurstView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * [gifRes] 是真正播的那段动画；[iconRes] / [color] 只在 GIF 解不出来时兜底。
     * [gifDp] 是画布边长——两段素材的图形在各自画布里占的比例不同，
     * 换算成屏幕上差不多大的图形。
     */
    enum class Kind(val gifRes: Int, val gifDp: Float, val iconRes: Int, val color: Int) {
        LIKE(R.raw.like_burst, 84f, R.drawable.ic_heart_rounded, 0xFFFF365D.toInt()),
        FAVORITE(R.raw.favorite_burst, 104f, R.drawable.ic_star_rounded, 0xFFFFD54F.toInt())
    }

    private val density = resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var icon: Drawable? = null
    private var gif: AnimatedImageDrawable? = null
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
        val bounds = Rect(0, 0, anchor.width, anchor.height)
        // 用共同的父容器换算坐标。getLocationOnScreen 在视图还没贴到窗口时直接返回 0，
        // 那样动画会跑到卡片左上角去。
        (parent as? ViewGroup)?.offsetDescendantRectToMyCoords(anchor, bounds)
        playAt(bounds.exactCenterX() - left, bounds.exactCenterY() - top, kind)
    }

    fun playAt(x: Float, y: Float, kind: Kind) {
        burstCenterX = x
        burstCenterY = y
        color = kind.color
        cancelBurst()
        visibility = VISIBLE
        val animation = decodeGif(kind)
        if (animation != null) startGif(animation, kind) else startDrawn(kind)
    }

    /** 素材播不出来时还有一套自己画的动画，总比点了没反应强。 */
    private fun startDrawn(kind: Kind) {
        icon = AppCompatResources.getDrawable(context, kind.iconRes)?.mutate()?.also { it.setTint(color) }
        progress = 0f
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

    private fun startGif(animation: AnimatedImageDrawable, kind: Kind) {
        val half = kind.gifDp * density / 2f
        // 先认领再设置：verifyDrawable 靠 gif 这个字段判断，认领之前的重绘请求会被丢掉。
        gif = animation
        animation.callback = this
        animation.setBounds(
            (burstCenterX - half).toInt(), (burstCenterY - half).toInt(),
            (burstCenterX + half).toInt(), (burstCenterY + half).toInt()
        )
        // 素材本身是无限循环的，这里只放一遍。
        animation.repeatCount = 0
        animation.registerAnimationCallback(object : Animatable2.AnimationCallback() {
            override fun onAnimationEnd(drawable: Drawable) {
                if (gif === drawable) cancelBurst()
            }
        })
        animation.start()
        invalidate()
    }

    private fun decodeGif(kind: Kind): AnimatedImageDrawable? = runCatching {
        val source = ImageDecoder.createSource(resources, kind.gifRes)
        // 原图 560~750 像素见方，按屏幕上的实际尺寸解码，省下大半内存。
        val target = (kind.gifDp * density).toInt().coerceAtLeast(1)
        ImageDecoder.decodeDrawable(source) { decoder, _, _ -> decoder.setTargetSize(target, target) }
    }.getOrNull() as? AnimatedImageDrawable

    fun cancelBurst() {
        animator?.cancel()
        animator = null
        gif?.let {
            it.stop()
            it.callback = null
        }
        gif = null
        progress = 0f
        visibility = GONE
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelBurst()
    }

    /**
     * View 默认只认自己的背景，别的 Drawable 请求重绘会被丢掉——不认下来，
     * GIF 就只会停在第一帧。
     */
    override fun verifyDrawable(who: Drawable): Boolean = who === gif || super.verifyDrawable(who)

    override fun onDraw(canvas: Canvas) {
        gif?.let { it.draw(canvas); return }
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
