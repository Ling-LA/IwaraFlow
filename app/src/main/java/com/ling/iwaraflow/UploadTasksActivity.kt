package com.ling.iwaraflow

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity

/** One resumable active task and a bounded encrypted record of completed submissions. */
class UploadTasksActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val task = UploadCoordinator.get(application)
        val page = PageLayout(this, UiText.text(R.string.ui_67ffd99b0412, "上传任务"))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; page.scroll(panel)
        val current = page.card(); panel.addView(current)
        current.addView(page.text(UiText.text(R.string.ui_75db758deaee, "当前任务"), 18f))
        val status = page.text(""); current.addView(status)
        task.state.observe(this) { status.text = task.filename.ifBlank { UiText.text(R.string.ui_b9c49e7503de, "尚未选择视频") } + "\n" + it.message }
        current.addView(page.action(UiText.text(R.string.ui_6fa85ea395ad, "打开上传页 / 继续任务")) {
            startActivity(Intent(this, UploadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        })
        current.addView(page.action(UiText.text(R.string.ui_de85a936261e, "暂停当前上传")) { task.pause() })
        current.addView(page.text(UiText.text(R.string.ui_e5e0aa431bef, "文件上传完毕后可继续官网处理；文件传输中断会重新上传。投稿结果不确定时，请先检查我的作品，避免重复发布。"), 13f))
        val completed = page.card(); panel.addView(completed); completed.addView(page.text(UiText.text(R.string.ui_77b196da0082, "本次登录的已提交记录"), 18f))
        val rows = task.recentTasks()
        if (rows.isEmpty()) completed.addView(page.text(UiText.text(R.string.ui_95e7ecac8399, "暂无提交记录")))
        rows.forEach { row ->
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(row.optLong("time")))
            completed.addView(page.action(row.optString("title") + UiText.text(R.string.ui_c31853a1f2f5, "\n%1\$s · 查看作品", date)) {
                startActivity(Intent(this, MainActivityV3::class.java).putExtra(IwaraSharedLink.EXTRA_URL, "https://www.iwara.tv/video/${row.optString("id")}"))
            })
        }
    }
}
