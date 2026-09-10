package com.ling.iwaraflow

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local-only lifecycle and failure evidence. Never sends a log or records account/video data. */
object NavigationDiagnostics {
    private val lock = Any()

    fun install(app: Application) {
        record(app, "启动 ${version(app)} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous != null) {
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                runCatching {
                    synchronized(lock) {
                        File(app.filesDir, "last-navigation-crash.txt").writeText(
                            "${Date()} · ${version(app)} · ${thread.name}\n${error.stackTraceToString().take(48_000)}"
                        )
                    }
                }
                // Keep Android's normal crash reporting and termination; never hide a crash.
                previous.uncaughtException(thread, error)
            }
        }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private fun log(a: Activity, state: String) = record(app,
                "${a.javaClass.simpleName} $state task=${a.taskId} root=${a.isTaskRoot} finishing=${a.isFinishing} pip=${a.isInPictureInPictureMode}")
            override fun onActivityCreated(a: Activity, b: Bundle?) = log(a, "created")
            override fun onActivityStarted(a: Activity) = log(a, "started")
            override fun onActivityResumed(a: Activity) = log(a, "resumed")
            override fun onActivityPaused(a: Activity) = log(a, "paused")
            override fun onActivityStopped(a: Activity) = log(a, "stopped")
            override fun onActivityDestroyed(a: Activity) = log(a, "destroyed")
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = log(a, "saved")
        })
    }

    private fun record(context: Context, event: String) {
        runCatching {
            synchronized(lock) {
                val file = File(context.filesDir, "navigation-lifecycle.txt")
                if (file.length() > 64_000) file.writeText(file.readText().takeLast(24_000))
                val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
                file.appendText("$time $event\n")
            }
        }
    }

    fun show(activity: Activity) {
        val report = buildString {
            appendLine("IwaraFlow ${version(activity)} · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("\n最近系统退出记录：")
            if (Build.VERSION.SDK_INT >= 30) runCatching {
                val manager = activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                manager.getHistoricalProcessExitReasons(null, 0, 3).forEach { info ->
                    appendLine("${Date(info.timestamp)} reason=${info.reason} status=${info.status} ${info.description.orEmpty()}")
                }
            }
            appendLine("\n最近异常：")
            appendLine(runCatching { File(activity.filesDir, "last-navigation-crash.txt").readText() }
                .getOrDefault("暂无 Java 异常记录"))
            appendLine("\n最近页面切换：")
            appendLine(runCatching { File(activity.filesDir, "navigation-lifecycle.txt").readText().takeLast(16_000) }
                .getOrDefault("暂无记录"))
        }
        AlertDialog.Builder(activity)
            .setTitle("诊断信息（仅保存在本机）")
            .setMessage(report)
            .setNegativeButton("关闭", null)
            .setPositiveButton("复制诊断信息") { _, _ ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("IwaraFlow 诊断信息", report))
                Toast.makeText(activity, "已复制，可粘贴到问题反馈中", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun version(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("unknown")
}
