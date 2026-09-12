package com.ling.iwaraflow

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import java.lang.ref.WeakReference

/**
 * 记住哪个页面正处于画中画。
 *
 * 作者页 / 搜索页进小窗后，系统会把它挪到一个独立的任务里；这时从桌面点图标拉起的是
 * 主页所在的任务，结果小窗还浮在主页上面，两个播放器同时存在。主页恢复时如果发现
 * 别的任务里有自家的小窗，就把那个任务拉到前台展开，而不是在它底下再放一个视频。
 */
object PipRegistry {
    @Volatile private var pipTaskId = -1
    @Volatile private var pipActivity: WeakReference<Activity>? = null

    fun enter(activity: Activity) {
        pipTaskId = activity.taskId
        pipActivity = WeakReference(activity)
    }

    fun leave(activity: Activity) {
        if (pipActivity?.get() === activity) {
            pipTaskId = -1
            pipActivity = null
        }
    }

    /** 别的任务里有本应用的小窗在放：把它拉到前台展开。返回 true 表示已经这么做了。 */
    fun expandInto(activity: Activity): Boolean {
        val id = pipTaskId
        val pip = pipActivity?.get()
        if (id == -1 || pip == null || pip.isFinishing || pip.isDestroyed || id == activity.taskId) return false
        val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return runCatching { manager.moveTaskToFront(id, 0); true }.getOrDefault(false)
    }
}
