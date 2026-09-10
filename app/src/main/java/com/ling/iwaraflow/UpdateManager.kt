package com.ling.iwaraflow

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UpdateManager(private val activity: Activity) {
    private val latestReleaseApi = "https://api.github.com/repos/Ling-LA/IwaraFlow/releases/latest"
    private val prefs = activity.getSharedPreferences("iwaraflow_updates", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private var downloadId = prefs.getLong(KEY_PENDING_DOWNLOAD_ID, -1L)
    private var downloadedUri: Uri? = null
    private var receiverRegistered = false

    data class ReleaseInfo(
        val version: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val publishedAt: String
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id < 0 || id != downloadId) return
            handlePendingDownload(showFailure = true)
        }
    }

    init {
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= 33) {
            // ACTION_DOWNLOAD_COMPLETE is a system broadcast. The download id is still checked
            // before any action is taken, so unrelated broadcasts cannot trigger an install.
            activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    fun checkOnLaunch() {
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_auto_check", 0L)
        if (now - last < AUTO_CHECK_INTERVAL_MS) return
        prefs.edit().putLong("last_auto_check", now).apply()
        check(manual = false)
    }

    fun check(manual: Boolean = true) {
        val waiting = if (manual) showCheckingDialog() else null
        executor.execute {
            val result = runCatching { fetchLatestRelease() }
            activity.runOnUiThread {
                waiting?.dismiss()
                result.onSuccess { release ->
                    val current = currentVersionName()
                    if (isNewer(release.version, current)) {
                        showUpdateDialog(release, current)
                    } else if (manual) {
                        Toast.makeText(activity, "当前已是最新版本 v$current", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    if (manual) Toast.makeText(activity, "检查更新失败：${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun fetchLatestRelease(): ReleaseInfo {
        val request = Request.Builder()
            .url(latestReleaseApi)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "IwaraFlow-Android")
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("GitHub HTTP ${response.code}")
            val json = JSONObject(raw)
            val version = normalizeVersion(json.optString("tag_name"))
            if (version.isBlank()) throw IllegalStateException("Release 缺少版本号")

            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    if (!name.endsWith(".apk", ignoreCase = true)) continue
                    val candidate = asset.optString("browser_download_url")
                    if (candidate.isBlank()) continue
                    apkUrl = candidate
                    if (name.contains("IwaraFlow", true) || name.contains("signed", true)) break
                }
            }
            if (apkUrl.isBlank()) throw IllegalStateException("最新 Release 中没有 APK")

            return ReleaseInfo(
                version = version,
                title = json.optString("name").ifBlank { "IwaraFlow v$version" },
                notes = json.optString("body").ifBlank { "修复问题并改进使用体验。" },
                apkUrl = apkUrl,
                publishedAt = json.optString("published_at")
            )
        }
    }

    private fun showCheckingDialog(): AlertDialog {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(ProgressBar(activity), LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(TextView(activity).apply {
                text = "正在检查新版本…"
                textSize = 15f
                setTextColor(0xFF17324A.toInt())
                setPadding(dp(16), 0, 0, 0)
            })
        }
        return AlertDialog.Builder(activity).setTitle("检查更新").setView(row).setCancelable(false).create().also { it.show() }
    }

    private fun showUpdateDialog(release: ReleaseInfo, currentVersion: String) {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(6), dp(24), dp(8))
        }
        panel.addView(TextView(activity).apply {
            text = "新版本  v${release.version}"
            textSize = 22f
            setTextColor(0xFF17324A.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        panel.addView(TextView(activity).apply {
            text = "当前版本 v$currentVersion"
            textSize = 12f
            setTextColor(0xFF6C879A.toInt())
            setPadding(0, dp(3), 0, dp(14))
        })
        panel.addView(TextView(activity).apply {
            text = "更新内容"
            textSize = 14f
            setTextColor(0xFFD84B73.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(7))
        })
        panel.addView(TextView(activity).apply {
            text = release.notes
            textSize = 14f
            setTextColor(0xFF35566C.toInt())
            setLineSpacing(0f, 1.18f)
            maxLines = 12
            setPadding(0, 0, 0, dp(6))
        })

        val dialog = AlertDialog.Builder(activity)
            .setTitle("发现新版本")
            .setView(panel)
            .setNegativeButton("以后再说", null)
            .setPositiveButton("立即更新") { _, _ -> downloadRelease(release) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(0xFFD84B73.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFF6C879A.toInt())
        }
        dialog.show()
    }

    private fun downloadRelease(release: ReleaseInfo) {
        try {
            val updateFile = updateApkFile()
            updateFile.parentFile?.mkdirs()
            if (updateFile.exists()) updateFile.delete()

            val request = DownloadManager.Request(Uri.parse(release.apkUrl))
                .setTitle("IwaraFlow v${release.version}")
                .setDescription("正在下载应用更新")
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(
                    activity,
                    Environment.DIRECTORY_DOWNLOADS,
                    "updates/$UPDATE_APK_NAME"
                )

            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = dm.enqueue(request)
            downloadedUri = null
            prefs.edit().putLong(KEY_PENDING_DOWNLOAD_ID, downloadId).apply()
            Toast.makeText(activity, "更新包开始下载，完成后会自动打开安装界面", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "更新下载失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Called whenever MainActivity becomes active again. This makes install triggering independent
     * from the DownloadManager broadcast: if the Activity was recreated or the OEM dropped the
     * broadcast, a completed download is discovered here and installation continues.
     */
    fun tryContinueInstall() {
        downloadId = prefs.getLong(KEY_PENDING_DOWNLOAD_ID, downloadId)
        if (downloadId < 0) return
        handlePendingDownload(showFailure = false)
    }

    private fun handlePendingDownload(showFailure: Boolean) {
        val id = prefs.getLong(KEY_PENDING_DOWNLOAD_ID, downloadId)
        if (id < 0) return
        downloadId = id

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(id)
        dm.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            if (statusIndex < 0) return
            when (cursor.getInt(statusIndex)) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val file = updateApkFile()
                    if (!file.exists() || file.length() <= 0L) {
                        if (showFailure) Toast.makeText(activity, "更新包下载完成，但 APK 文件不存在", Toast.LENGTH_LONG).show()
                        clearPendingDownload()
                        return
                    }
                    downloadedUri = FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        file
                    )
                    installDownloadedApk()
                }
                DownloadManager.STATUS_FAILED -> {
                    if (showFailure) Toast.makeText(activity, "更新包下载失败，请重新检查更新", Toast.LENGTH_LONG).show()
                    clearPendingDownload()
                }
            }
        }
    }

    private fun installDownloadedApk() {
        val uri = downloadedUri ?: return
        if (!canInstallPackages()) {
            Toast.makeText(activity, "请允许 IwaraFlow 安装未知应用，然后返回继续安装", Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            }
            return
        }

        try {
            val install = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("IwaraFlow update", uri)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
            }
            if (install.resolveActivity(activity.packageManager) != null) {
                activity.startActivity(install)
            } else {
                val fallback = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri("IwaraFlow update", uri)
                }
                activity.startActivity(fallback)
            }
            clearPendingDownload()
        } catch (e: Exception) {
            Toast.makeText(activity, "无法打开安装程序：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateApkFile(): File {
        val downloads = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: activity.filesDir
        return File(downloads, "updates/$UPDATE_APK_NAME")
    }

    private fun clearPendingDownload() {
        prefs.edit().remove(KEY_PENDING_DOWNLOAD_ID).apply()
        downloadId = -1L
        downloadedUri = null
    }

    private fun canInstallPackages(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()
    }

    private fun currentVersionName(): String {
        return try {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Exception) { "0.0.0" }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val r = versionParts(remote)
        val c = versionParts(current)
        val size = maxOf(r.size, c.size)
        for (i in 0 until size) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    private fun versionParts(value: String): List<Int> = normalizeVersion(value)
        .split('.', '-', '_')
        .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    private fun normalizeVersion(value: String): String = value.trim().removePrefix("v").removePrefix("V")
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    fun close() {
        if (receiverRegistered) {
            runCatching { activity.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        executor.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    companion object {
        private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val KEY_PENDING_DOWNLOAD_ID = "pending_download_id"
        private const val UPDATE_APK_NAME = "IwaraFlow-update.apk"
        private const val APK_MIME = "application/vnd.android.package-archive"
    }
}
