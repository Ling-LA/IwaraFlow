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
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class UpdateManager(private val activity: Activity) {
    private val latestReleaseApi = "https://api.github.com/repos/$REPO/releases/latest"
    private val latestReleasePage = "https://github.com/$REPO/releases/latest"
    private val prefs = activity.getSharedPreferences("iwaraflow_updates", Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadExecutor()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    /** 备用通道要看跳转地址本身，不能自动跟随。 */
    private val noRedirectClient by lazy { client.newBuilder().followRedirects(false).followSslRedirects(false).build() }

    private var downloadId = prefs.getLong(KEY_PENDING_DOWNLOAD_ID, -1L)
    private var downloadedUri: Uri? = null
    private var receiverRegistered = false
    @Volatile private var closed = false
    private var activeDialog: AlertDialog? = null

    data class ReleaseInfo(
        val version: String,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val publishedAt: String,
        /** 这条信息是从哪来的（API / 发布页跳转），只用于诊断。 */
        val source: String = ""
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
        androidx.core.content.ContextCompat.registerReceiver(activity, receiver, filter,
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
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
        if (closed || activity.isFinishing || activity.isDestroyed) return
        val waiting = if (manual) showCheckingDialog().also { activeDialog = it } else null
        executor.execute {
            val current = currentVersionName()
            val result = runCatching {
                val release = fetchLatestRelease()
                if (!isNewer(release.version, current)) release
                else {
                    val generated = fetchGeneratedNotes(current, release.version)
                    if (generated.isBlank()) release else release.copy(notes = generated)
                }
            }
            result.onSuccess { NavigationDiagnostics.note(activity, "检查更新：最新 v${it.version}（${it.source}），当前 v$current") }
            result.onFailure { NavigationDiagnostics.note(activity, UiText.text(R.string.ui_cb79f814f414, "检查更新失败：%1\$s", it.message?.take(200))) }
            activity.runOnUiThread {
                waiting?.dismiss()
                if (activeDialog === waiting) activeDialog = null
                if (closed || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess { release ->
                    if (isNewer(release.version, current)) showUpdateDialog(release, current)
                    else if (manual) Toast.makeText(activity, UiText.text(R.string.ui_2b1d76ce1c33, "当前已是最新版本 v%1\$s", current), Toast.LENGTH_SHORT).show()
                }.onFailure {
                    if (manual) Toast.makeText(activity, UiText.text(R.string.ui_cb79f814f414, "检查更新失败：%1\$s", explain(it)), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 把连接层的错误说成人话；GitHub 在国内经常连不上，提示里直接点出代理。 */
    private fun explain(error: Throwable): String {
        val message = error.message.orEmpty()
        NetworkProxy.explain(message)?.let { return UiText.text(R.string.ui_b814fb72d0f2, "连不上 GitHub，请检查代理 / VPN 是否对本应用生效") }
        return message.ifBlank { error.javaClass.simpleName }
    }

    /**
     * 先走 GitHub API（有完整的更新说明和资源列表）；API 连不上或被限流（未登录每小时 60 次，
     * 共用出口 IP 时很容易碰到）就退到发布页的跳转地址：`/releases/latest` 会 302 到
     * `/releases/tag/vX.Y.Z`，版本号就在里面，APK 地址按固定的文件名拼出来。
     */
    private fun fetchLatestRelease(): ReleaseInfo {
        val viaApi = runCatching { fetchLatestViaApi() }
        viaApi.getOrNull()?.let { return it }
        val viaPage = runCatching { fetchLatestViaRedirect() }
        viaPage.getOrNull()?.let { return it }
        val apiReason = viaApi.exceptionOrNull()?.message ?: UiText.text(R.string.ui_4d8c1c5b4283, "未知")
        val pageReason = viaPage.exceptionOrNull()?.message ?: UiText.text(R.string.ui_4d8c1c5b4283, "未知")
        throw IllegalStateException(UiText.text(R.string.ui_acb3f04cfd43, "%1\$s；备用通道：%2\$s", apiReason, pageReason), viaApi.exceptionOrNull())
    }

    private fun fetchLatestViaRedirect(): ReleaseInfo {
        val request = Request.Builder().url(latestReleasePage).header("User-Agent", "IwaraFlow-Android").build()
        noRedirectClient.newCall(request).execute().use { response ->
            val location = response.header("Location").orEmpty()
            if (response.code !in 300..399 || location.isBlank()) throw IllegalStateException("发布页 HTTP ${response.code}")
            val version = normalizeVersion(location.substringAfterLast("/tag/", "").substringBefore('?').trim())
            if (version.isBlank() || version.none { it.isDigit() }) throw IllegalStateException("跳转地址里没有版本号")
            return ReleaseInfo(
                version = version,
                title = "IwaraFlow v$version",
                notes = UiText.text(R.string.ui_8c9d2e08ed68, "修复问题并改进使用体验。"),
                apkUrl = "https://github.com/$REPO/releases/download/v$version/$RELEASE_APK_NAME",
                publishedAt = "",
                source = UiText.text(R.string.ui_e1f60461afa2, "发布页跳转")
            )
        }
    }

    private fun fetchLatestViaApi(): ReleaseInfo {
        val request = githubRequest(latestReleaseApi)
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val limited = response.code == 403 && response.header("X-RateLimit-Remaining") == "0"
                throw IllegalStateException(if (limited) "GitHub 接口限流（HTTP 403）" else "GitHub HTTP ${response.code}")
            }
            val json = JSONObject(raw)
            val version = normalizeVersion(json.optString("tag_name"))
            if (version.isBlank()) throw IllegalStateException("Release 缺少版本号")
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            if (assets != null) for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                if (!name.endsWith(".apk", ignoreCase = true)) continue
                val candidate = asset.optString("browser_download_url")
                if (candidate.isBlank()) continue
                apkUrl = candidate
                if (name.contains("IwaraFlow", true) || name.contains("signed", true)) break
            }
            if (apkUrl.isBlank()) throw IllegalStateException("最新 Release 中没有 APK")
            return ReleaseInfo(
                version = version,
                title = json.optString("name").ifBlank { "IwaraFlow v$version" },
                notes = cleanReleaseNotes(json.optString("body")),
                apkUrl = apkUrl,
                publishedAt = json.optString("published_at"),
                source = "GitHub API"
            )
        }
    }

    /** Build user-facing notes from commits between the installed and latest release tags. */
    private fun fetchGeneratedNotes(current: String, remote: String): String {
        val from = URLEncoder.encode("v${normalizeVersion(current)}", "UTF-8")
        val to = URLEncoder.encode("v${normalizeVersion(remote)}", "UTF-8")
        val url = "https://api.github.com/repos/$REPO/compare/$from...$to"
        return runCatching {
            client.newCall(githubRequest(url)).execute().use { response ->
                if (!response.isSuccessful) return@use ""
                val root = JSONObject(response.body?.string().orEmpty())
                val commits = root.optJSONArray("commits") ?: return@use ""
                val lines = ArrayList<String>()
                for (i in 0 until commits.length()) {
                    val message = commits.optJSONObject(i)
                        ?.optJSONObject("commit")
                        ?.optString("message")
                        .orEmpty()
                    val title = message.lineSequence().firstOrNull().orEmpty().trim()
                    if (title.isBlank()) continue
                    if (title.startsWith("chore: bump IwaraFlow", true)) continue
                    if (title.startsWith("docs:", true)) continue
                    if (lines.none { it.equals(title, true) }) lines += title
                    if (lines.size >= 12) break
                }
                if (lines.isEmpty()) "" else lines.joinToString("\n") { "• ${humanizeCommit(it)}" }
            }
        }.getOrDefault("")
    }

    private fun humanizeCommit(message: String): String {
        val cleaned = message.replace(Regex("^(feat|fix|ui|perf|ci|refactor|chore)(\\([^)]*\\))?:\\s*", RegexOption.IGNORE_CASE), "")
        return cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun cleanReleaseNotes(raw: String): String {
        if (raw.isBlank()) return UiText.text(R.string.ui_8c9d2e08ed68, "修复问题并改进使用体验。")
        val useful = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("IwaraFlow v", true) }
            .filterNot { it.startsWith("此 Release 由", true) }
            .filterNot { it.startsWith("App 内", true) } // ui-literal: release notes marker
            .filterNot { it.startsWith("Commit:", true) }
            .joinToString("\n")
        return useful.ifBlank { UiText.text(R.string.ui_8c9d2e08ed68, "修复问题并改进使用体验。") }
    }

    private fun githubRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "IwaraFlow-Android")
        .build()

    private fun showCheckingDialog(): AlertDialog {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            addView(ProgressBar(activity), LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(TextView(activity).apply {
                text = UiText.text(R.string.ui_66891e15bd35, "正在检查新版本…")
                textSize = 15f
                setTextColor(0xFF17324A.toInt())
                setPadding(dp(16), 0, 0, 0)
            })
        }
        return AlertDialog.Builder(activity).setTitle(UiText.text(R.string.ui_7f68ebad19ba, "检查更新")).setView(row).setCancelable(false).create().also { it.show() }
    }

    private fun showUpdateDialog(release: ReleaseInfo, currentVersion: String) {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(6), dp(24), dp(8))
        }
        panel.addView(TextView(activity).apply {
            text = UiText.text(R.string.ui_4005fb10f70d, "新版本  v%1\$s", release.version)
            textSize = 22f
            setTextColor(0xFF17324A.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        panel.addView(TextView(activity).apply {
            text = UiText.text(R.string.ui_3cb0839f4759, "当前版本 v%1\$s", currentVersion)
            textSize = 12f
            setTextColor(0xFF6C879A.toInt())
            setPadding(0, dp(3), 0, dp(14))
        })
        panel.addView(TextView(activity).apply {
            text = UiText.text(R.string.ui_6c89d3260903, "更新内容")
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
            maxLines = 14
            setPadding(0, 0, 0, dp(6))
        })
        val dialog = AlertDialog.Builder(activity)
            .setTitle(UiText.text(R.string.ui_ac217e4d1ca4, "发现新版本"))
            .setView(panel)
            .setNegativeButton(UiText.text(R.string.ui_9d2055b957c0, "以后再说"), null)
            .setPositiveButton(UiText.text(R.string.ui_12487befb4ba, "立即更新")) { _, _ -> downloadRelease(release) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(0xFFD84B73.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFF6C879A.toInt())
        }
        activeDialog = dialog
        dialog.show()
    }

    private fun downloadRelease(release: ReleaseInfo) {
        try {
            val updateFile = updateApkFile()
            updateFile.parentFile?.mkdirs()
            if (updateFile.exists()) updateFile.delete()
            val request = DownloadManager.Request(Uri.parse(release.apkUrl))
                .setTitle("IwaraFlow v${release.version}")
                .setDescription(UiText.text(R.string.ui_28038654bd2c, "正在下载应用更新"))
                .setMimeType(APK_MIME)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, "updates/$UPDATE_APK_NAME")
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = dm.enqueue(request)
            downloadedUri = null
            prefs.edit().putLong(KEY_PENDING_DOWNLOAD_ID, downloadId).apply()
            NavigationDiagnostics.note(activity, UiText.text(R.string.ui_d47be76e98e4, "更新包开始下载 v%1\$s", release.version))
            Toast.makeText(activity, UiText.text(R.string.ui_79eaf62d5f30, "更新包开始下载，完成后会自动打开安装界面"), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            NavigationDiagnostics.note(activity, UiText.text(R.string.ui_2bec0badef0b, "更新包下载失败：%1\$s", e.message?.take(160)))
            Toast.makeText(activity, UiText.text(R.string.ui_59ae1eaf5914, "更新下载失败：%1\$s", e.message), Toast.LENGTH_LONG).show()
        }
    }

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
        dm.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
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
                    downloadedUri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                    installDownloadedApk()
                }
                DownloadManager.STATUS_FAILED -> {
                    val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                    val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else -1
                    NavigationDiagnostics.note(activity, "更新包下载失败：DownloadManager reason=$reason")
                    if (showFailure) Toast.makeText(activity, UiText.text(R.string.ui_63196655e600, "更新包下载失败（原因码 %1\$s），请重新检查更新", reason), Toast.LENGTH_LONG).show()
                    clearPendingDownload()
                }
            }
        }
    }

    private fun installDownloadedApk() {
        val uri = downloadedUri ?: return
        if (!canInstallPackages()) {
            Toast.makeText(activity, UiText.text(R.string.ui_7a0d6770fbec, "请允许 IwaraFlow 安装未知应用，然后返回继续安装"), Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
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
            if (install.resolveActivity(activity.packageManager) != null) activity.startActivity(install)
            else activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newRawUri("IwaraFlow update", uri)
            })
            clearPendingDownload()
        } catch (e: Exception) {
            Toast.makeText(activity, UiText.text(R.string.ui_76d186fdd702, "无法打开安装程序：%1\$s", e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun updateApkFile(): File {
        val downloads = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
        return File(downloads, "updates/$UPDATE_APK_NAME")
    }

    private fun clearPendingDownload() {
        prefs.edit().remove(KEY_PENDING_DOWNLOAD_ID).apply()
        downloadId = -1L
        downloadedUri = null
    }

    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    private fun currentVersionName(): String = try {
        @Suppress("DEPRECATION")
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "0.0.0"
    } catch (_: Exception) { "0.0.0" }

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
        closed = true
        activeDialog?.dismiss()
        activeDialog = null
        if (receiverRegistered) {
            runCatching { activity.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        executor.shutdownNow()
        HttpClientCleanup.close(client)
    }

    companion object {
        private const val REPO = "Ling-LA/IwaraFlow"
        /** CI 固定上传的资源名，备用通道靠它拼下载地址。 */
        private const val RELEASE_APK_NAME = "IwaraFlow-signed.apk"
        private const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val KEY_PENDING_DOWNLOAD_ID = "pending_download_id"
        private const val UPDATE_APK_NAME = "IwaraFlow-update.apk"
        private const val APK_MIME = "application/vnd.android.package-archive"
    }
}
