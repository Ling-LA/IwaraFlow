package com.ling.iwaraflow

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputFilter
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider

/** Native form; publishing uses the same API login as playback and comments. */
class UploadActivity : AppCompatActivity() {
    private lateinit var model: UploadViewModel
    private lateinit var titleInput: EditText
    private lateinit var bodyInput: EditText
    private lateinit var tagsInput: EditText
    private lateinit var rating: Spinner
    private lateinit var visibility: Spinner
    private lateinit var rules: CheckBox
    private lateinit var fileLabel: TextView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var submit: Button
    private lateinit var choose: Button
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selectFile(uri)
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        model = ViewModelProvider(this)[UploadViewModel::class.java]
        val page = PageLayout(this, "上传视频")
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.scroll(panel)
        fun text(value: String) = page.text(value).apply { setPadding(0, page.dp(8), 0, page.dp(8)) }
        fun button(label: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; textSize = 15f
            backgroundTintList = null
            setBackgroundResource(if (primary) R.drawable.bg_profile_primary else R.drawable.bg_profile_button)
            setTextColor(if (primary) -1 else UiPalette.resolve(context, 0xFF285C7B.toInt()))
            layoutParams = LinearLayout.LayoutParams(-1, page.dp(48)).apply { topMargin = page.dp(8) }
            setOnClickListener { click() }
        }
        val info = page.card()
        panel.addView(info)
        info.addView(text("使用当前 Iwara 账号投稿，文件与信息将直接提交至官网。"))
        info.addView(button("查看上传任务") { startActivity(Intent(this, UploadTasksActivity::class.java)) })
        choose = button("选择 MP4 视频") { picker.launch(arrayOf("video/mp4")) }
        info.addView(choose)
        fileLabel = text("尚未选择视频")
        info.addView(fileLabel)
        info.addView(page.text("MP4（H.264 / AAC），至少 20 秒；普通账号上限 300 MiB，Premium 600 MiB。最终以官网校验为准。", 13f))
        fun input(label: String, lines: Int, max: Int): EditText {
            info.addView(text(label))
            return EditText(this).apply {
                hint = label; minLines = lines; maxLines = if (lines == 1) 1 else 8
                if (lines == 1) setSingleLine(true)
                filters = arrayOf(InputFilter.LengthFilter(max))
                info.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        titleInput = input("标题", 1, 1000)
        bodyInput = input("简介", 4, 50_000)
        tagsInput = input("标签（逗号分隔，例如 animation, music）", 2, 2000)
        info.addView(text("内容分级"))
        rating = Spinner(this).apply {
            adapter = ArrayAdapter(this@UploadActivity, android.R.layout.simple_spinner_dropdown_item, listOf("全年龄", "成人"))
            info.addView(this, LinearLayout.LayoutParams(-1, page.dp(48)))
        }
        info.addView(text("可见范围"))
        visibility = Spinner(this).apply {
            adapter = ArrayAdapter(this@UploadActivity, android.R.layout.simple_spinner_dropdown_item, listOf("公开", "仅好友", "不公开列出"))
            info.addView(this, LinearLayout.LayoutParams(-1, page.dp(48)))
        }
        rules = CheckBox(this).apply { text = "我已阅读并同意 Iwara 规则"; info.addView(this) }
        info.addView(page.action("查看 Iwara 规则  ↗") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.iwara.tv/rules"))) }
        })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        info.addView(progress, LinearLayout.LayoutParams(-1, page.dp(5)))
        status = text(model.state.value?.message.orEmpty())
        info.addView(status)
        submit = button("提交投稿", true) { submitForm() }
        info.addView(submit)
        info.addView(page.action("查看我的作品  ›") { startActivity(Intent(this, MyActivity::class.java)) })
        val guidelines = page.card()
        panel.addView(guidelines)
        guidelines.addView(text("创作者须知").apply { textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD) })
        guidelines.addView(text("文件要求\n• 格式：MP4，建议 H.264 视频 + AAC 音频\n• 大小：普通账号 300 MiB，Premium 600 MiB\n• 最短时长：20 秒\n• 最低分辨率：官网标注 960 × 720（总像素不少于 691200）\n其他编码或过高码率可能由官网重新编码。"))
        guidelines.addView(text("参考码率（kb/s，30 fps / 60 fps）\n720p：4500 / 6300\n1080p：7500 / 10500\n1440p：11500 / 16100\n2160p：17000 / 23800"))
        fun officialLink(label: String, url: String) {
            guidelines.addView(page.action(label + "  ↗") {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            })
        }
        officialLink("创作者常见问题", "https://www.iwara.tv/page/creator-faq")
        officialLink("视频渲染与压缩指南", "https://www.iwara.tv/page/video-compression")
        guidelines.addView(text("官网规则").apply { textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD) })
        val rulesStatus = text("正在读取官网规则…")
        guidelines.addView(rulesStatus)
        rulesStatus.setOnClickListener { model.loadRules() }
        val ruleRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        guidelines.addView(ruleRows)
        model.rules.observe(this) { entries ->
            ruleRows.removeAllViews()
            entries.orEmpty().forEachIndexed { index, rule ->
                val body = text(IwaraRule.readableBody(rule.body)).apply {
                    textSize = 14f; setTextIsSelectable(true)
                    visibility = if (index == 0) View.VISIBLE else View.GONE
                    android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
                    movementMethod = android.text.method.LinkMovementMethod.getInstance()
                }
                val heading = page.action(rule.title + if (index == 0) "  ▾" else "  ▸") {
                    body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
                heading.setOnClickListener {
                    body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    (heading as TextView).text = rule.title + if (body.visibility == View.VISIBLE) "  ▾" else "  ▸"
                }
                ruleRows.addView(heading)
                ruleRows.addView(body)
            }
        }
        model.rulesStatus.observe(this) { rulesStatus.text = it }
        officialLink("查看官网规则原文", "https://www.iwara.tv/rules")


        titleInput.setText(state?.getString("title") ?: model.draft?.title.orEmpty())
        bodyInput.setText(state?.getString("body") ?: model.draft?.body.orEmpty())
        tagsInput.setText(state?.getString("tags") ?: model.draft?.tags?.joinToString(", ").orEmpty())
        rating.setSelection(state?.getInt("rating") ?: if (model.draft?.rating == "ecchi") 1 else 0)
        visibility.setSelection(state?.getInt("visibility") ?: when { model.draft?.privateVideo == true -> 1; model.draft?.unlisted == true -> 2; else -> 0 })
        rules.isChecked = state?.getBoolean("rules") ?: model.draft?.rulesAgreement ?: false
        if (model.uri == null) state?.getString("uri")?.let { selectFile(Uri.parse(it)) }
        if (state?.getBoolean("publishing") == true && !model.busy && model.state.value?.videoId == null) {
            model.publicationUncertain = true
            model.state.value = UploadUiState("error", "上次发布中断，请先查看我的作品确认投稿结果", uncertain = true)
        }
        updateFileLabel()
        model.state.observe(this) { value ->
            if (value == null) return@observe
            status.text = value.message + if (value.busy && value.phase != "publishing" && value.percent >= 0) " ${value.percent}%" else ""
            progress.visibility = if (value.busy) View.VISIBLE else View.GONE
            progress.isIndeterminate = value.percent < 0
            progress.progress = value.percent.coerceAtLeast(0)
            val editable = !value.busy && value.videoId == null
            listOf<View>(titleInput, bodyInput, tagsInput, rating, visibility, rules, choose).forEach { it.isEnabled = editable }
            choose.isEnabled = !value.busy
            choose.text = if (value.videoId != null) "选择下一个视频" else "选择 MP4 视频"
            submit.isEnabled = editable && model.loggedIn()
            submit.text = when {
                value.videoId != null -> "已提交"
                value.uncertain -> "确认投稿结果后重试"
                value.phase == "error" -> "重试提交"
                value.busy -> "正在提交…"
                else -> "提交投稿"
            }
        }
        if (!model.loggedIn()) {
            status.text = "请先返回主页登录 Iwara，再来上传视频"
            submit.isEnabled = false
        }
        // Cover the shared header's Back action as well as the system Back gesture.
        val backViews = ArrayList<View>()
        page.root.findViewsWithText(backViews, "‹  返回", View.FIND_VIEWS_WITH_TEXT)
        backViews.forEach { it.setOnClickListener { leave() } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }
    private fun leave() {
        saveDraft()
        if (!model.busy) { finish(); return }
        AlertDialog.Builder(this).setTitle("上传正在进行")
            .setMessage("可以在后台继续上传，也可暂停后稍后恢复。发布结果未知时仍需先确认我的作品。")
            .setPositiveButton("后台继续") { _, _ -> finish() }
            .setNeutralButton("暂停并返回") { _, _ -> model.pause(); finish() }.setNegativeButton("留在此页", null).show()
    }
    private fun selectFile(uri: Uri) {
        if (model.busy) return
        runCatching {
            runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            var name = "video.mp4"
            var bytes = -1L
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: name
                    if (!cursor.isNull(1)) bytes = cursor.getLong(1)
                }
            }
            require(name.endsWith(".mp4", true) || contentResolver.getType(uri) == "video/mp4") { "请选择 MP4 视频" }
            require(bytes != 0L) { "视频文件为空" }
            require(bytes <= 600L * 1024 * 1024) { "视频不能超过 600 MiB" }
            model.select(uri, name, bytes)
            updateFileLabel()
        }.onFailure { Toast.makeText(this, it.message ?: "无法读取视频", Toast.LENGTH_LONG).show() }
    }
    private fun updateFileLabel() {
        if (!::fileLabel.isInitialized) return
        fileLabel.text = if (model.uri == null) "尚未选择视频" else model.filename +
            if (model.size > 0) " · %.1f MiB".format(model.size / 1048576.0) else ""
    }
    private fun submitForm() {
        if (model.busy) return
        if (model.publicationUncertain) {
            AlertDialog.Builder(this).setTitle("已确认上次投稿未发布？")
                .setMessage("请先查看我的作品。只有确认没有该投稿后再重新提交。")
                .setPositiveButton("确认未发布，重试") { _, _ -> model.publicationUncertain = false; submitForm() }
                .setNegativeButton("返回检查", null).show()
            return
        }
        runCatching {
            require(model.uri != null) { "请先选择视频" }
            val draft = VideoUploadDraft(titleInput.text.toString(), bodyInput.text.toString(),
                VideoUploadDraft.parseTags(tagsInput.text.toString()), if (rating.selectedItemPosition == 1) "ecchi" else "general",
                visibility.selectedItemPosition == 1, visibility.selectedItemPosition == 2, rules.isChecked)
            draft.validate()
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 89)
            model.submit(draft)
        }.onFailure { status.text = it.message ?: "请检查投稿信息" }
    }
    private fun saveDraft() {
        if (!::titleInput.isInitialized || model.busy) return
        model.saveDraft(VideoUploadDraft(titleInput.text.toString(), bodyInput.text.toString(),
            VideoUploadDraft.parseTags(tagsInput.text.toString()), if (rating.selectedItemPosition == 1) "ecchi" else "general",
            visibility.selectedItemPosition == 1, visibility.selectedItemPosition == 2, rules.isChecked))
    }
    override fun onPause() { saveDraft(); super.onPause() }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("title", titleInput.text.toString()); out.putString("body", bodyInput.text.toString())
        out.putString("tags", tagsInput.text.toString()); out.putString("uri", model.uri?.toString())
        out.putInt("rating", rating.selectedItemPosition); out.putInt("visibility", visibility.selectedItemPosition)
        out.putBoolean("rules", rules.isChecked)
        out.putBoolean("publishing", model.state.value?.phase == "publishing" || model.publicationUncertain)
    }
    companion object {
        const val UPLOAD_URL = "https://www.iwara.tv/create/video"
        internal fun isOfficial(url: String?): Boolean = Uri.parse(url ?: "").let {
            it.scheme == "https" && (it.host == "iwara.tv" || it.host == "www.iwara.tv")
        }
    }
}
