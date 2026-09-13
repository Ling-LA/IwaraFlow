package com.ling.iwaraflow

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View

/**
 * 全屏播放：收起卡片上的信息栏 / 操作栏和页面自己的顶栏，画面铺满。
 *
 * 横屏视频（宽大于高）顺便把屏幕转过去，竖着拿手机也能看满一整屏；竖屏视频本来就铺满，
 * 只把控件收起来，不转屏。暂停时进度条、快进后退、剩余时长、小窗和退出全屏会重新出现，
 * 那一套由 [PauseSeekBar] 按卡片的模式标记决定，见 [VideoAdapter.setChromeVisible]。
 */
object FullscreenMode {
    /**
     * 进入或退出全屏。[chrome] 是页面自己要一起收起的东西（顶栏、返回按钮等）。
     * 返回实际是否处于全屏，页面据此记状态。
     */
    fun apply(activity: Activity, adapter: VideoAdapter, chrome: List<View?>, enabled: Boolean): Boolean {
        adapter.setFullscreen(enabled)
        chrome.forEach { it?.visibility = if (enabled) View.GONE else View.VISIBLE }
        val landscapeVideo = (adapter.activeVideoAspect() ?: 0f) > 1f
        activity.requestedOrientation = if (enabled && landscapeVideo) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        return enabled
    }
}
