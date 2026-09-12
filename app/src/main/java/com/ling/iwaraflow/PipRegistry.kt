package com.ling.iwaraflow

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import java.lang.ref.WeakReference

/**
 * 记住哪个页面正处于（或正要进入）画中画。
 *
 * 作者页 / 搜索页进小窗后，系统会把它挪到一个独立的任务里，原来的任务（主页）反而会被
 * 顶到前台；从桌面点图标拉起的也是主页所在的任务。结果就是小窗浮在主页上面，两个播放器
 * 同时存在。主页恢复时要分两种情况处理：
 * - 因为小窗把子页面带走而被动露出来的：把自己退到后台，让桌面露出来，就像用户按了 Home；
 * - 用户点图标主动打开的：把小窗那个任务拉到前台展开，而不是在它底下再放一个视频。
 */
object PipRegistry {
    @Volatile private var pipActivity: WeakReference<Activity>? = null

    /** 进小窗之前就登记，主页被顶上来的那一刻就能查到。 */
    fun enter(activity: Activity) {
        pipActivity = WeakReference(activity)
    }

    fun leave(activity: Activity) {
        if (pipActivity?.get() === activity) pipActivity = null
    }

    /** 除了 [activity] 自己以外，还有没有本应用的页面在小窗里（或正要进）。 */
    fun otherPipActivity(activity: Activity): Activity? {
        val pip = pipActivity?.get() ?: return null
        if (pip === activity || pip.isFinishing || pip.isDestroyed) return null
        if (!pip.isInPictureInPictureMode && pip.taskId == activity.taskId) return null
        return pip
    }

    /** 把小窗那个任务拉到前台展开。返回 true 表示已经这么做了。 */
    fun expandInto(activity: Activity): Boolean {
        val pip = otherPipActivity(activity) ?: return false
        val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return runCatching { manager.moveTaskToFront(pip.taskId, 0); true }.getOrDefault(false)
    }
}
