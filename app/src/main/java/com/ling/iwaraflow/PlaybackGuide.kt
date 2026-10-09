package com.ling.iwaraflow

import android.app.Activity
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** A view on the current window; every tap is consumed, so dismissing cannot like or pause a video. */
object PlaybackGuide {
    private const val TAG = "playback_gesture_guide"
    internal fun key(horizontal: Boolean) = if (horizontal) "fullscreen_guide_v1_landscape" else "playback_guide_v2_portrait"
    fun isActive(activity: Activity) = sessions.containsKey(activity)
    private val sessions = java.util.IdentityHashMap<Activity, Session>()

    fun showOnce(activity: Activity, horizontal: Boolean, onDismiss: () -> Unit = {}) {
        if (isActive(activity)) return
        val prefs = activity.getSharedPreferences(AppPrefs.FILE, 0)
        if (prefs.getBoolean(key(horizontal), false)) { onDismiss(); return }
        if (activity.isFinishing || activity.isDestroyed) return
        prefs.edit().putBoolean(key(horizontal), true).apply()
        show(activity, horizontal, onDismiss)
    }
    fun dismiss(activity: Activity) { sessions[activity]?.finish(false) }
    fun show(activity: Activity, horizontal: Boolean = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE, onDismiss: () -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        dismiss(activity)
        val session = Session(activity, onDismiss)
        sessions[activity] = session
        session.start(horizontal)
    }

    private class Session(val activity: Activity, val onDismiss: () -> Unit) {
        private val originalOrientation = activity.requestedOrientation
        private val host = activity as? PlaybackGuideHost
        private val originalFullscreen = host?.playbackGuideAdapter?.isFullscreen ?: false
        private val root = activity.window.decorView as ViewGroup
        private lateinit var view: GuideView
        private var finished = false
        private val back = object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish(true)
        }
        fun start(horizontal: Boolean) {
            view = GuideView(activity, horizontal, { setMode(it) }) { finish(true) }.apply { tag = TAG }
            root.addView(view, ViewGroup.LayoutParams(-1, -1))
            (activity as? androidx.activity.ComponentActivity)?.onBackPressedDispatcher?.addCallback(back)
            host?.playbackGuideAdapter?.setGuideVisible(true)
            setMode(horizontal)
        }
        private fun setMode(horizontal: Boolean) {
            if (finished) return
            host?.setPlaybackGuideFullscreen(horizontal)
            activity.requestedOrientation = if (horizontal) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            view.requestLayout()
        }
        fun finish(notify: Boolean) {
            if (finished) return
            finished = true
            back.remove()
            root.removeView(view)
            // Keep the session registered during restoration to suppress automatic guides and autoplay.
            if (!activity.isDestroyed && !activity.isFinishing) {
                host?.setPlaybackGuideFullscreen(originalFullscreen)
                activity.requestedOrientation = originalOrientation
                host?.playbackGuideAdapter?.setGuideVisible(false)
            }
            sessions.remove(activity)
            if (notify) onDismiss()
        }
    }

    internal class GuideView(private val activity: Activity, private var horizontal: Boolean,
        switchOrientation: (Boolean) -> Unit, close: () -> Unit) : FrameLayout(activity) {
        private val density = resources.displayMetrics.density
        private fun dp(value: Int) = (value*density).toInt()
        private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCCFFFFFF.toInt(); strokeWidth = 2*density; style = Paint.Style.STROKE
            pathEffect = DashPathEffect(floatArrayOf(8*density, 7*density), 0f)
        }
        private val heading = TextView(activity).apply {
            text = "播放操作引导"; textSize = 20f; setTextColor(-1); typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        private val zones = List(3) { TextView(activity).apply {
            textSize = 15f; setTextColor(-1); gravity = Gravity.CENTER; setLineSpacing(5*density, 1f)
            setPadding(dp(10), dp(4), dp(10), dp(4)); typeface = Typeface.DEFAULT_BOLD
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        } }
        private val footer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8)); setBackgroundColor(0xF2182532.toInt())
        }
        private val controlCaption = TextView(activity).apply {
            text = "按钮区\n独立操作"; textSize = 11f; setTextColor(0xFFBDCCD7.toInt()); gravity = Gravity.CENTER
        }
        private data class Chrome(val surface: RectF?, val navigation: RectF?, val actions: RectF?, val info: RectF?, val controls: List<RectF>, val normal: Boolean)
        private var chrome = Chrome(null, null, null, null, emptyList(), true)
        internal val portraitArea = RectF()
        internal var labelAreas = emptyList<RectF>()
            private set
        private var portraitSurface = RectF()
        private var headingTop = 0
        private var footerTop = 0
        private var topInset = 0
        private var bottomInset = 0
        private val geometryListener = android.view.ViewTreeObserver.OnPreDrawListener {
            val next = readChrome()
            if (next != chrome) { chrome = next; requestLayout(); invalidate() }
            true
        }
        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnPreDrawListener(geometryListener)
        }
        override fun onDetachedFromWindow() {
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(geometryListener)
            super.onDetachedFromWindow()
        }
        private fun readChrome(): Chrome {
            val decor = activity.window.decorView
            val origin = IntArray(2).also(decor::getLocationOnScreen)
            fun bounds(view: View?): RectF? {
                if (view == null || !view.isShown || view.width <= 0 || view.height <= 0) return null
                val visible = Rect()
                if (!view.getGlobalVisibleRect(visible)) return null
                val position = IntArray(2).also(view::getLocationOnScreen)
                return RectF((position[0]-origin[0]).toFloat(), (position[1]-origin[1]).toFloat(),
                    (position[0]-origin[0]+view.width).toFloat(), (position[1]-origin[1]+view.height).toFloat())
            }
            val items = mutableListOf<View>()
            fun collect(view: View) {
                if (view === this || !view.isShown) return
                if (view.id == R.id.root && view.findViewById<View>(R.id.playerView) != null) items += view
                else if (view is ViewGroup) repeat(view.childCount) { collect(view.getChildAt(it)) }
            }
            collect(decor)
            val item = items.maxByOrNull { v -> Rect().let { if (v.getGlobalVisibleRect(it)) it.width().toLong()*it.height() else 0L } }
            val nav = listOf(R.id.topBar, R.id.feedBack, R.id.searchFeedBack, R.id.savedFeedBack).mapNotNull { bounds(decor.findViewById(it)) }.maxByOrNull { it.bottom }
            val controls = listOf(R.id.pauseControls, R.id.pauseTopRow, R.id.pauseSeekBar).mapNotNull { bounds(item?.findViewById(it)) }
            return Chrome(bounds(item), nav, bounds(item?.findViewById(R.id.actionPanel)), bounds(item?.findViewById(R.id.infoPanel)),
                controls, item?.getTag(R.id.chrome_mode) != PauseSeekBar.MODE_FULLSCREEN)
        }
        private val switch = TextView(activity).apply { textSize = 12f; setTextColor(-1); gravity = Gravity.CENTER; minHeight = dp(40) }
        init {
            setWillNotDraw(false); setBackgroundColor(0xD0182532.toInt()); isClickable = true; isFocusable = true
            addView(heading); zones.forEach { addView(it) }; addView(controlCaption)
            footer.addView(TextView(activity).apply {
                text = "← 左滑快退     右滑快进 →"; textSize = 14f; setTextColor(-1)
                typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(4))
            })
            footer.addView(TextView(activity).apply {
                text = "仅在空白画面长按；导航、按钮和底部信息区除外\n单击暂停 / 显示控件 · 双击点赞\n长按评论 1 秒：快捷发送弹幕评论"; textSize = 11f; setTextColor(-1); gravity = Gravity.CENTER
            })
            val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER }
            actions.addView(switch, LinearLayout.LayoutParams(dp(148), dp(44)))
            actions.addView(TextView(activity).apply {
                text = "知道了"; contentDescription = "关闭操作引导"; textSize = 15f; setTextColor(0xFF17324A.toInt()); gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(-1); cornerRadius = 24*density }
                setOnClickListener { close() }
            }, LinearLayout.LayoutParams(dp(100), dp(44)))
            footer.addView(actions); addView(footer)
            switch.setOnClickListener { switchOrientation(!horizontal) }
            updateLabels()
        }
        private fun updateLabels() {
            heading.text = if (horizontal) "横屏全屏操作引导" else "播放画面操作指引"
            zones[0].text = "⊘\n长按${if (horizontal) "左侧" else "上方"}\n不感兴趣\n选择视频、作者或标签"
            zones[1].text = "♡ + ☆\n按住中间 2.5 秒\n点赞＋收藏\n提前松开即可取消"
            zones[2].text = "≫\n长按${if (horizontal) "右侧" else "下方"}\n2× 加速\n松开恢复正常速度"
            switch.text = if (horizontal) "查看竖屏操作" else "查看横屏操作"
        }
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            // Orientation requests are asynchronous (and may be ignored in multi-window).
            // Always render the gesture map for the actual window, never a landscape map in portrait.
            if (horizontal != (w > h)) { horizontal = w > h; updateLabels() }
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(this)?.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            topInset = insets?.top ?: 0; bottomInset = insets?.bottom ?: 0
            footer.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(148), MeasureSpec.AT_MOST))
            footerTop = h - bottomInset - footer.measuredHeight
            chrome = readChrome()
            if (horizontal) {
                controlCaption.visibility = View.GONE
                headingTop = topInset + dp(8)
                heading.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(50), MeasureSpec.EXACTLY))
                labelAreas = List(3) { i -> RectF(i*w/3f, (headingTop+dp(50)).toFloat(), (i+1)*w/3f, footerTop.toFloat()) }
                zones.forEachIndexed { i, zone ->
                    zone.textSize = 15f; zone.setLineSpacing(5*density, 1f)
                    zone.measure(MeasureSpec.makeMeasureSpec(labelAreas[i].width().toInt(), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(labelAreas[i].height().toInt().coerceAtLeast(0), MeasureSpec.EXACTLY))
                }
                return
            }
            portraitSurface = chrome.surface ?: RectF(0f, topInset.toFloat(), w.toFloat(), (h-bottomInset).toFloat())
            headingTop = maxOf(topInset, chrome.navigation?.bottom?.toInt() ?: topInset) + dp(8)
            val right = if (chrome.normal) chrome.actions?.left ?: (w-dp(76)).toFloat() else w.toFloat()
            portraitArea.set(portraitSurface.left+dp(12), (headingTop+dp(50)).toFloat(), right-dp(10),
                minOf(chrome.info?.top?.minus(dp(8)) ?: footerTop.toFloat(), (footerTop-dp(8)).toFloat()))
            heading.measure(MeasureSpec.makeMeasureSpec(portraitArea.width().toInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(50), MeasureSpec.EXACTLY))
            controlCaption.visibility = if (chrome.normal) View.VISIBLE else View.GONE
            controlCaption.measure(MeasureSpec.makeMeasureSpec((w-right).toInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp(52), MeasureSpec.EXACTLY))
            val obstacles = chrome.controls.map { RectF(it).apply { inset(-dp(4).toFloat(), -dp(4).toFloat()) } }
            labelAreas = PlaybackGuideLayout.verticalBands(portraitSurface).map {
                PlaybackGuideLayout.clearLabelArea(it, portraitArea, obstacles).apply { if (height() > dp(8)) inset(0f, dp(4).toFloat()) }
            }
            zones.forEachIndexed { i, zone ->
                val r = labelAreas[i]
                val compact = r.height() < dp(86) || r.width() < dp(220)
                zone.textSize = if (compact) 12f else 15f; zone.setLineSpacing(2*density, 1f)
                zone.text = if (r.height() < dp(60)) listOf("长按上方：不感兴趣", "中间 2.5 秒：点赞＋收藏", "长按下方：2× 加速")[i]
                    else listOf("⊘\n长按上方空白画面\n不感兴趣选项", "♡ + ☆\n按住中间 2.5 秒\n点赞＋收藏", "≫\n长按下方空白画面\n2× 加速 · 松开恢复")[i]
                zone.measure(MeasureSpec.makeMeasureSpec(r.width().toInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(r.height().toInt().coerceAtLeast(0), MeasureSpec.EXACTLY))
            }
        }
        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val headingLeft = if (horizontal) 0 else portraitArea.left.toInt()
            heading.layout(headingLeft, headingTop, headingLeft+heading.measuredWidth, headingTop+heading.measuredHeight)
            footer.layout(0, footerTop, width, footerTop+footer.measuredHeight)
            labelAreas.forEachIndexed { i, r -> zones[i].layout(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()) }
            controlCaption.layout(width-controlCaption.measuredWidth, headingTop, width, headingTop+controlCaption.measuredHeight)
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (horizontal) {
                for (i in 1..2) canvas.drawLine(width*i/3f, (headingTop+dp(54)).toFloat(), width*i/3f, (footerTop-dp(8)).toFloat(), pen)
            } else if (!portraitArea.isEmpty) {
                val save = canvas.save()
                chrome.controls.forEach { canvas.clipOutRect(it) }
                canvas.drawRoundRect(portraitArea, dp(12).toFloat(), dp(12).toFloat(), pen)
                for (i in 1..2) {
                    val y = portraitSurface.top + portraitSurface.height()*i/3f
                    if (y > portraitArea.top && y < portraitArea.bottom) canvas.drawLine(portraitArea.left, y, portraitArea.right, y, pen)
                }
                canvas.restoreToCount(save)
            }
        }
    }
}
