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
            val text = LocalDataBackup.export(history.libraryDatabase, history.interestDatabase, history.systemTagMultipliers())
            contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use { it.write(text) }
            UiText.text(R.string.ui_d8f4682fae16, "旧版收藏与兴趣已导出（不包含官方播放列表、登录密钥和浏览历史）")
        }
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) AlertDialog.Builder(this).setTitle(UiText.text(R.string.ui_b1a09d0ab30a, "合并旧版收藏与兴趣？"))
            .setMessage(UiText.text(R.string.ui_1dfb60eeab60, "已有条目保留，新增条目会合并到本机。导入后可在我的收藏中点击“重试同步”，将旧收藏添加至官网。仅导入你信任的备份。"))
            .setPositiveButton(UiText.text(R.string.ui_b07ece270100, "合并")) { _, _ -> work {
                val text = contentResolver.openInputStream(uri)!!.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) { val n = stream.read(buffer); if (n < 0) break
                        require(output.size() + n <= 5_000_000) { UiText.text(R.string.ui_ad5100742efd, "备份超过 5 MB") }; output.write(buffer, 0, n)
                    }
                    output.toString("UTF-8")
                }
                val count = LocalDataBackup.restore(history.libraryDatabase, text, history.interestDatabase) { values ->
                    val existing = history.systemTagMultipliers()
                    values.filterKeys { it !in existing }.forEach { (key, value) -> history.setSystemTagMultiplier(key, value) }
                }
                history.invalidateInterests()
                UiText.text(R.string.ui_ea5d38825e24, "已合并 %1\$s 条记录", count)
            } }.setNegativeButton(UiText.text(R.string.ui_2cd0f3be8738, "取消"), null).show()
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        history = HistoryStore(this); cache = MediaPreloadCache(this)
        val page = PageLayout(this, UiText.text(R.string.ui_2100fbd17556, "数据与存储"))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val card = page.card(); panel.addView(card)
        val size = page.text(UiText.text(R.string.ui_c9630227f56b, "正在读取缓存占用…")); card.addView(size)
        worker.execute {
            // Statistics do not acquire a second cache or enumerate/delete the active directory.
            val directory = java.io.File(cacheDir, "video_preload")
            val bytes = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            runOnUiThread { size.text = UiText.text(R.string.ui_60c5641da505, "视频缓存：%1\$s\n低于 256 MiB 可用空间时停止额外缓存，继续在线播放。", DownloadLibrary.formatSize(bytes)) }
        }
        card.addView(page.action(UiText.text(R.string.ui_ef15c0032711, "清理未在播放的视频缓存")) { WarmRecommendationStore.clear(this); cache.clearIdleCache(); Toast.makeText(this, UiText.text(R.string.ui_b9fb8af0516e, "已安排后台清理，当前视频保留"), Toast.LENGTH_SHORT).show() })
        val prefs = AppPrefs(this)
        fun budgetChoice(label: String, values: List<Int>, current: () -> Int, apply: (Int) -> Unit) {
            card.addView(page.action(label) {
                val names = values.map { if (it == 0) UiText.text(R.string.ui_4ea2cac556cc, "不限额") else "$it MiB" }.toTypedArray()
                AlertDialog.Builder(this).setTitle(label).setSingleChoiceItems(names, values.indexOf(current())) { dialog, index ->
                    apply(values[index]); dialog.dismiss()
                    Toast.makeText(this, UiText.text(R.string.ui_ac80a6f593d4, "预算已更新"), Toast.LENGTH_SHORT).show()
                }.setNegativeButton(UiText.text(R.string.ui_2cd0f3be8738, "取消"), null).show()
            })
        }
        budgetChoice(UiText.text(R.string.ui_8ffd1cf3935f, "视频缓存容量上限"), listOf(256, 512, 1024, 2048, 4096, 8192), { prefs.cacheBudgetMb }) {
            prefs.cacheBudgetMb = it; cache.applyBudget()
        }
        budgetChoice(UiText.text(R.string.ui_1c0bc9551de6, "每日移动网络预加载预算"), listOf(0, 128, 256, 512, 1024, 2048), { prefs.meteredPreloadMb }) { prefs.meteredPreloadMb = it }
        card.addView(page.text(UiText.text(R.string.ui_7228e525588a, "默认移动预加载不限额。预算只限制提前缓存，不限制正常播放；缓存达到容量上限后仍可在线播放。"), 13f))
        card.addView(androidx.appcompat.widget.SwitchCompat(this).apply {
            text = UiText.text(R.string.ui_43a1fa0675e6, "按账号隔离兴趣"); isChecked = prefs.isolateInterests
            setOnCheckedChangeListener { _, enabled ->
                prefs.isolateInterests = enabled; history.invalidateInterests()
                Toast.makeText(this@DataManagementActivity, UiText.text(R.string.ui_a2ff8aaea332, "已切换兴趣范围，返回推荐页后生效"), Toast.LENGTH_LONG).show()
            }
        })
        card.addView(page.text(UiText.text(R.string.ui_2f1d7a777790, "默认共用本机兴趣。开启后，每个账号及未登录状态分别保存系统兴趣、手动兴趣和屏蔽；原共享兴趣保留。历史和旧版收藏可通过下面的独立开关调整；导入导出作用于各自当前范围。"), 13f))
        card.addView(androidx.appcompat.widget.SwitchCompat(this).apply {
            text = UiText.text(R.string.ui_5cd913379d41, "按账号隔离历史与旧版收藏"); isChecked = prefs.isolateLibrary
            setOnCheckedChangeListener { _, enabled ->
                prefs.isolateLibrary = enabled
                Toast.makeText(this@DataManagementActivity, UiText.text(R.string.ui_8bcafe1ae03c, "已切换范围，重新打开列表后生效；原数据保留"), Toast.LENGTH_LONG).show()
            }
        })
        card.addView(page.text(UiText.text(R.string.ui_f145d8ce9386, "关闭时共享设备记录；开启时账号与未登录状态各自独立，原共享记录仍保留。备份仅包含当前范围的旧版本地收藏与兴趣；官方播放列表始终按账号分别同步，不受此开关影响。"), 13f))
        card.addView(page.action(UiText.text(R.string.ui_bc0489373e04, "导出旧版收藏与兴趣")) { exportFile.launch("IwaraFlow-backup.json") })
        card.addView(page.action(UiText.text(R.string.ui_87bee4f55d3f, "导入旧版收藏与兴趣")) { importFile.launch(arrayOf("application/json", "text/plain")) })
        card.addView(page.action(UiText.text(R.string.ui_4e4e6dfdeeae, "清除浏览历史")) { confirm(UiText.text(R.string.ui_4e4e6dfdeeae, "清除浏览历史"), UiText.text(R.string.ui_63a8d3b4ac95, "会清除本机浏览记录与本机已看标记，收藏和兴趣保留。")) {
            history.libraryDatabase.delete("history", null, null)
            history.libraryDatabase.delete("seen_videos", "account_id=''", null)
            WarmRecommendationStore.clear(this)
            UiText.text(R.string.ui_fa610bc22908, "浏览历史已清除")
        } })
        card.addView(page.action(UiText.text(R.string.ui_87435e888146, "重置系统推荐画像")) { confirm(UiText.text(R.string.ui_87435e888146, "重置系统推荐画像"), UiText.text(R.string.ui_31c385fbb1a8, "会清除系统学习和推荐统计，手动兴趣与屏蔽保留。")) {
            val db = history.interestDatabase
            db.beginTransaction()
            try { listOf("preference_entities", "interactions").forEach { db.delete(it, null, null) }; db.setTransactionSuccessful() }
            finally { db.endTransaction() }
            history.clearExtraLikeSignals()
            history.writableDatabase.delete("recommendation_impressions", null, null)
            history.invalidateInterests(); UiText.text(R.string.ui_5a0deb23adec, "系统画像已重置")
        } })
        card.addView(page.action(UiText.text(R.string.ui_67ffd99b0412, "上传任务")) { startActivity(android.content.Intent(this, UploadTasksActivity::class.java)) })
        card.addView(page.action(UiText.text(R.string.ui_dda25b7c277c, "查看播放性能与推荐诊断")) { NavigationDiagnostics.show(this) })
        card.addView(page.text(UiText.text(R.string.ui_c01e841abc15, "备份由你选择保存位置；应用不会自动上传。清除历史与重置画像是独立操作。"), 13f))
        page.scroll(panel)
    }
    private fun confirm(title: String, body: String, action: () -> String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton(UiText.text(R.string.ui_36f33adaf094, "确认")) { _, _ -> work(action) }.setNegativeButton(UiText.text(R.string.ui_2cd0f3be8738, "取消"), null).show()
    }
    private fun work(action: () -> String) {
        worker.execute {
            val message = runCatching(action).getOrElse { it.message ?: UiText.text(R.string.ui_e113c7d13e87, "操作失败，请重试") }
            runOnUiThread { if (!isFinishing && !isDestroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
    }
    override fun onDestroy() { worker.shutdown(); worker.executeSafeClose(); cache.close(); super.onDestroy() }
    private fun java.util.concurrent.ExecutorService.executeSafeClose() {
        Thread { awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS); history.close() }.start()
    }
}
