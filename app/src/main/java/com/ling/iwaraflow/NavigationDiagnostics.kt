package com.ling.iwaraflow

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.FileProvider
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

    /**
     * 记录一条加载过程的线索。只写条数和失败原因，不写账号、视频 ID 或标题，
     * 这样“视频加载不出来”的设备可以直接从诊断信息看出是接口失败还是预检失败。
     */
    fun note(context: Context, event: String) = record(context.applicationContext, event)

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
        val report = buildReport(activity)
        AlertDialog.Builder(activity)
            .setTitle("诊断信息（仅保存在本机）")
            .setMessage(report)
            .setNegativeButton("关闭", null)
            .setNeutralButton("复制") { _, _ ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("IwaraFlow 诊断信息", report))
                Toast.makeText(activity, "已复制", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("分享文件") { _, _ -> shareAsFile(activity, report) }
            .show()
    }

    /**
     * 整份报告存成 txt 再分享出去。以前只能复制文本，一份报告几千字，
     * 贴到 QQ 里超长会被截断，交上来的诊断经常缺尾巴；文件没有这个问题。
     */
    private fun shareAsFile(activity: Activity, report: String) {
        val file = runCatching { writeReportFile(activity, report) }.getOrNull()
        if (file == null) {
            Toast.makeText(activity, "诊断文件写入失败", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivity(Intent.createChooser(send, "分享诊断文件")) }
            .onFailure { Toast.makeText(activity, "没有可用的分享应用", Toast.LENGTH_SHORT).show() }
    }

    /** 写到缓存目录的 diagnostics/ 下，只留最近几份。 */
    internal fun writeReportFile(context: Context, report: String): File {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        return File(dir, "IwaraFlow-${version(context)}-诊断-$stamp.txt").apply { writeText(report) }
    }

    internal fun buildReport(activity: Activity): String {
        return buildString {
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
    }

    private fun version(context: Context): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }.getOrDefault("unknown")
}
