package com.ling.iwaraflow

import android.app.Activity
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object PageNavigation {
    const val HOME = "return_to_recommendations"
    fun home(activity: Activity) {
        activity.startActivity(Intent(activity, MainActivityV3::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(HOME, true))
    }
    fun show(activity: Activity, includeHome: Boolean = true, beforeNavigate: () -> Unit = {}) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (includeHome) actions += "回到主页" to { home(activity) }
        actions += "我的" to { activity.startActivity(Intent(activity, MyActivity::class.java)) }
        actions += "浏览历史" to { saved(activity, SavedVideosActivity.KIND_HISTORY) }
        actions += "我的收藏" to { saved(activity, SavedVideosActivity.KIND_FAVORITES) }
        actions += "已下载" to { saved(activity, SavedVideosActivity.KIND_DOWNLOADS) }
        actions += "搜索" to { activity.startActivity(Intent(activity, SearchActivity::class.java)) }
        actions += "兴趣管理" to { activity.startActivity(Intent(activity, InterestActivity::class.java)) }
        actions += "设置" to { activity.startActivity(Intent(activity, SettingsActivity::class.java)) }
        AlertDialog.Builder(activity).setTitle("跳转页面")
            .setItems(actions.map { it.first }.toTypedArray()) { _, index -> beforeNavigate(); actions[index].second() }.show()
    }
    fun saved(activity: Activity, kind: String) {
        activity.startActivity(Intent(activity, SavedVideosActivity::class.java).putExtra(SavedVideosActivity.EXTRA_KIND, kind))
    }
    fun install(activity: Activity, feed: FrameLayout, beforeNavigate: () -> Unit): View {
        val dp = activity.resources.displayMetrics.density
        return TextView(activity).apply {
            text = "⋮"; textSize = 28f; gravity = Gravity.CENTER; setTextColor(-1)
            tag = "page_navigation_menu"
            contentDescription = "页面菜单"; setBackgroundColor(0x66000000)
            layoutParams = FrameLayout.LayoutParams((48*dp).toInt(), (48*dp).toInt(), Gravity.TOP or Gravity.END)
                .apply { topMargin = (16*dp).toInt(); rightMargin = (14*dp).toInt() }
            setOnClickListener { show(activity, beforeNavigate = beforeNavigate) }
            feed.addView(this)
        }
    }
}
