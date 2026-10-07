package com.ling.iwaraflow

import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class DataManagementActivity : AppCompatActivity() {
    private lateinit var history: HistoryStore
    private lateinit var cache: MediaPreloadCache
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) work {
            val text = LocalDataBackup.export(history.readableDatabase, history.interestDatabase, history.systemTagMultipliers())
            contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(text) }
            "收藏与兴趣已导出（不包含登录密钥和浏览历史）"
        }
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) AlertDialog.Builder(this).setTitle("合并收藏与兴趣？")
            .setMessage("已有条目保留，新增条目会合并到本机。仅导入你信任的备份。")
            .setPositiveButton("合并") { _, _ -> work {
                val text = contentResolver.openInputStream(uri)!!.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) { val n = stream.read(buffer); if (n < 0) break
                        require(output.size() + n <= 5_000_000) { "备份超过 5 MB" }; output.write(buffer, 0, n)
                    }
                    output.toString("UTF-8")
                }
                val count = LocalDataBackup.restore(history.writableDatabase, text, history.interestDatabase) { values ->
                    val existing = history.systemTagMultipliers()
                    values.filterKeys { it !in existing }.forEach { (key, value) -> history.setSystemTagMultiplier(key, value) }
                }
                history.invalidateInterests()
                "已合并 $count 条记录"
            } }.setNegativeButton("取消", null).show()
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        history = HistoryStore(this); cache = MediaPreloadCache(this)
        val page = PageLayout(this, "数据与存储")
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val card = page.card(); panel.addView(card)
        val size = page.text("正在读取缓存占用…"); card.addView(size)
        worker.execute {
            // Statistics do not acquire a second cache or enumerate/delete the active directory.
            val directory = java.io.File(cacheDir, "video_preload")
            val bytes = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            runOnUiThread { size.text = "视频缓存：${DownloadLibrary.formatSize(bytes)}\n低于 256 MiB 可用空间时停止额外缓存，继续在线播放。" }
        }
        card.addView(page.action("清理未在播放的视频缓存") { cache.clearIdleCache(); Toast.makeText(this, "已安排后台清理，当前视频保留", Toast.LENGTH_SHORT).show() })
        val prefs = AppPrefs(this)
        card.addView(androidx.appcompat.widget.SwitchCompat(this).apply {
            text = "按账号隔离兴趣"; isChecked = prefs.isolateInterests
            setOnCheckedChangeListener { _, enabled ->
                prefs.isolateInterests = enabled; history.invalidateInterests()
                Toast.makeText(this@DataManagementActivity, "已切换兴趣范围，返回推荐页后生效", Toast.LENGTH_LONG).show()
            }
        })
        card.addView(page.text("默认共用本机兴趣。开启后，每个账号及未登录状态分别保存系统兴趣、手动兴趣和屏蔽；原共享兴趣保留。历史、收藏仍共用。导入导出作用于当前兴趣范围。", 13f))
        card.addView(page.action("导出收藏与兴趣") { exportFile.launch("IwaraFlow-backup.json") })
        card.addView(page.action("导入收藏与兴趣") { importFile.launch(arrayOf("application/json", "text/plain")) })
        card.addView(page.action("清除浏览历史") { confirm("清除浏览历史", "会清除本机浏览记录与本机已看标记，收藏和兴趣保留。") {
            history.writableDatabase.delete("history", null, null)
            history.writableDatabase.delete("seen_videos", "account_id=''", null)
            "浏览历史已清除"
        } })
        card.addView(page.action("重置系统推荐画像") { confirm("重置系统推荐画像", "会清除系统学习和推荐统计，手动兴趣与屏蔽保留。") {
            val db = history.interestDatabase
            db.beginTransaction()
            try { listOf("preference_entities", "interactions").forEach { db.delete(it, null, null) }; db.setTransactionSuccessful() }
            finally { db.endTransaction() }
            history.writableDatabase.delete("recommendation_impressions", null, null)
            history.invalidateInterests(); "系统画像已重置"
        } })
        card.addView(page.action("上传任务") { startActivity(android.content.Intent(this, UploadTasksActivity::class.java)) })
        card.addView(page.action("查看播放性能与推荐诊断") { NavigationDiagnostics.show(this) })
        card.addView(page.text("备份由你选择保存位置；应用不会自动上传。清除历史与重置画像是独立操作。", 13f))
        page.scroll(panel)
    }
    private fun confirm(title: String, body: String, action: () -> String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("确认") { _, _ -> work(action) }.setNegativeButton("取消", null).show()
    }
    private fun work(action: () -> String) {
        worker.execute {
            val message = runCatching(action).getOrElse { it.message ?: "操作失败，请重试" }
            runOnUiThread { if (!isFinishing && !isDestroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }
    override fun onDestroy() { worker.shutdown(); worker.executeSafeClose(); cache.close(); super.onDestroy() }
    private fun java.util.concurrent.ExecutorService.executeSafeClose() {
        Thread { awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS); history.close() }.start()
    }
}
