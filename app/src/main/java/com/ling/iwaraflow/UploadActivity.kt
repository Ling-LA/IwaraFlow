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
        val page = PageLayout(this, UiText.text(R.string.ui_0b58562cdfb7, "上传视频"))
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
        info.addView(text(UiText.text(R.string.ui_44722ddc94e2, "使用当前 Iwara 账号投稿，文件与信息将直接提交至官网。")))
        info.addView(button(UiText.text(R.string.ui_16f152dad685, "查看上传任务")) { startActivity(Intent(this, UploadTasksActivity::class.java)) })
        choose = button(UiText.text(R.string.ui_4decf1225965, "选择 MP4 视频")) { picker.launch(arrayOf("video/mp4")) }
        info.addView(choose)
        fileLabel = text(UiText.text(R.string.ui_b9c49e7503de, "尚未选择视频"))
        info.addView(fileLabel)
        info.addView(page.text(UiText.text(R.string.ui_89d2256d36cc, "MP4（H.264 / AAC），至少 20 秒；普通账号上限 300 MiB，Premium 600 MiB。最终以官网校验为准。"), 13f))
        fun input(label: String, lines: Int, max: Int): EditText {
            info.addView(text(label))
            return EditText(this).apply {
                hint = label; minLines = lines; maxLines = if (lines == 1) 1 else 8
                if (lines == 1) setSingleLine(true)
                filters = arrayOf(InputFilter.LengthFilter(max))
                info.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
        }
        titleInput = input(UiText.text(R.string.ui_c3405f8c7d9d, "标题"), 1, 1000)
        bodyInput = input(UiText.text(R.string.ui_8131eafd0239, "简介"), 4, 50_000)
        tagsInput = input(UiText.text(R.string.ui_df6148590fb9, "标签（逗号分隔，例如 animation, music）"), 2, 2000)
        info.addView(text(UiText.text(R.string.ui_11674b63fd9a, "内容分级")))
        rating = Spinner(this).apply {
            adapter = ArrayAdapter(this@UploadActivity, android.R.layout.simple_spinner_dropdown_item, listOf(UiText.text(R.string.ui_c423d7eedeac, "全年龄"), UiText.text(R.string.ui_dd8479a6f3f7, "成人")))
            info.addView(this, LinearLayout.LayoutParams(-1, page.dp(48)))
        }
        info.addView(text(UiText.text(R.string.ui_4aae96e09494, "可见范围")))
        visibility = Spinner(this).apply {
            adapter = ArrayAdapter(this@UploadActivity, android.R.layout.simple_spinner_dropdown_item, listOf(UiText.text(R.string.ui_7a73baf0ce9a, "公开"), UiText.text(R.string.ui_15ff8d3abca1, "仅好友"), UiText.text(R.string.ui_97f58cd1b1c5, "不公开列出")))
            info.addView(this, LinearLayout.LayoutParams(-1, page.dp(48)))
        }
        rules = CheckBox(this).apply { text = UiText.text(R.string.ui_ce132c78a12b, "我已阅读并同意 Iwara 规则"); info.addView(this) }
        info.addView(page.action(UiText.text(R.string.ui_9d4f8f97aec6, "查看 Iwara 规则  ↗")) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.iwara.tv/rules"))) }
        })
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        info.addView(progress, LinearLayout.LayoutParams(-1, page.dp(5)))
        status = text(model.state.value?.message.orEmpty())
        info.addView(status)
        submit = button(UiText.text(R.string.ui_6d728c06dbad, "提交投稿"), true) { submitForm() }
        info.addView(submit)
        info.addView(page.action(UiText.text(R.string.ui_3f5d4ca5b7c7, "查看我的作品  ›")) { startActivity(Intent(this, MyActivity::class.java)) })
        val guidelines = page.card()
        panel.addView(guidelines)
        guidelines.addView(text(UiText.text(R.string.ui_bdbf6b79ebcd, "创作者须知")).apply { textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD) })
        guidelines.addView(text(UiText.text(R.string.ui_4e1a80335966, "文件要求\n• 格式：MP4，建议 H.264 视频 + AAC 音频\n• 大小：普通账号 300 MiB，Premium 600 MiB\n• 最短时长：20 秒\n• 最低分辨率：官网标注 960 × 720（总像素不少于 691200）\n其他编码或过高码率可能由官网重新编码。")))
        guidelines.addView(text(UiText.text(R.string.ui_f8c912c720ab, "参考码率（kb/s，30 fps / 60 fps）\n720p：4500 / 6300\n1080p：7500 / 10500\n1440p：11500 / 16100\n2160p：17000 / 23800")))
        fun officialLink(label: String, url: String) {
            guidelines.addView(page.action(label + "  ↗") {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            })
        }
        officialLink(UiText.text(R.string.ui_44c0968da6d0, "创作者常见问题"), "https://www.iwara.tv/page/creator-faq")
        officialLink(UiText.text(R.string.ui_f0a92bef5aaf, "视频渲染与压缩指南"), "https://www.iwara.tv/page/video-compression")
        guidelines.addView(text(UiText.text(R.string.ui_4327884e372f, "官网规则")).apply { textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD) })
        val rulesStatus = text(UiText.text(R.string.ui_c1bee29dd6c9, "正在读取官网规则…"))
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
        officialLink(UiText.text(R.string.ui_d168bb77191b, "查看官网规则原文"), "https://www.iwara.tv/rules")


        titleInput.setText(state?.getString("title") ?: model.draft?.title.orEmpty())
        bodyInput.setText(state?.getString("body") ?: model.draft?.body.orEmpty())
        tagsInput.setText(state?.getString("tags") ?: model.draft?.tags?.joinToString(", ").orEmpty())
        rating.setSelection(state?.getInt("rating") ?: if (model.draft?.rating == "ecchi") 1 else 0)
        visibility.setSelection(state?.getInt("visibility") ?: when { model.draft?.privateVideo == true -> 1; model.draft?.unlisted == true -> 2; else -> 0 })
        rules.isChecked = state?.getBoolean("rules") ?: model.draft?.rulesAgreement ?: false
        if (model.uri == null) state?.getString("uri")?.let { selectFile(Uri.parse(it)) }
        if (state?.getBoolean("publishing") == true && !model.busy && model.state.value?.videoId == null) {
            model.publicationUncertain = true
            model.state.value = UploadUiState("error", UiText.text(R.string.ui_6e7bacbdeb92, "上次发布中断，请先查看我的作品确认投稿结果"), uncertain = true)
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
            choose.text = if (value.videoId != null) UiText.text(R.string.ui_aa804de12cf6, "选择下一个视频") else UiText.text(R.string.ui_4decf1225965, "选择 MP4 视频")
            submit.isEnabled = editable && model.loggedIn()
            submit.text = when {
                value.videoId != null -> UiText.text(R.string.ui_bc37a6110a07, "已提交")
                value.uncertain -> UiText.text(R.string.ui_7d6ac6b0ff17, "确认投稿结果后重试")
                value.phase == "error" -> UiText.text(R.string.ui_8e864203241b, "重试提交")
                value.busy -> UiText.text(R.string.ui_26ef00b7d061, "正在提交…")
                else -> UiText.text(R.string.ui_6d728c06dbad, "提交投稿")
            }
        }
        if (!model.loggedIn()) {
            status.text = UiText.text(R.string.ui_fcbe470458f3, "请先返回主页登录 Iwara，再来上传视频")
            submit.isEnabled = false
        }
        // Cover the shared header's Back action as well as the system Back gesture.
        val backViews = ArrayList<View>()
        page.root.findViewsWithText(backViews, UiText.text(R.string.ui_29ebafb31c58, "‹  返回"), View.FIND_VIEWS_WITH_TEXT)
        backViews.forEach { it.setOnClickListener { leave() } }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
    }
    private fun leave() {
        saveDraft()
        if (!model.busy) { finish(); return }
        AlertDialog.Builder(this).setTitle(UiText.text(R.string.ui_63cbe931a70e, "上传正在进行"))
            .setMessage(UiText.text(R.string.ui_064830acc3e0, "可以在后台继续上传，也可暂停后稍后恢复。发布结果未知时仍需先确认我的作品。"))
            .setPositiveButton(UiText.text(R.string.ui_ac1613b06f9d, "后台继续")) { _, _ -> finish() }
            .setNeutralButton(UiText.text(R.string.ui_2ecc2d935831, "暂停并返回")) { _, _ -> model.pause(); finish() }.setNegativeButton(UiText.text(R.string.ui_c081e766e32a, "留在此页"), null).show()
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
            require(name.endsWith(".mp4", true) || contentResolver.getType(uri) == "video/mp4") { UiText.text(R.string.ui_6930cb81e85f, "请选择 MP4 视频") }
            require(bytes != 0L) { UiText.text(R.string.ui_c2a626a5cdc2, "视频文件为空") }
            require(bytes <= 600L * 1024 * 1024) { UiText.text(R.string.ui_d06cb73a3d67, "视频不能超过 600 MiB") }
            model.select(uri, name, bytes)
            updateFileLabel()
        }.onFailure { Toast.makeText(this, it.message ?: UiText.text(R.string.ui_dd800d35dd23, "无法读取视频"), Toast.LENGTH_LONG).show() }
    }
    private fun updateFileLabel() {
        if (!::fileLabel.isInitialized) return
        fileLabel.text = if (model.uri == null) UiText.text(R.string.ui_b9c49e7503de, "尚未选择视频") else model.filename +
            if (model.size > 0) " · %.1f MiB".format(model.size / 1048576.0) else ""
    }
    private fun submitForm() {
        if (model.busy) return
        if (model.publicationUncertain) {
            AlertDialog.Builder(this).setTitle(UiText.text(R.string.ui_ab92226110a0, "已确认上次投稿未发布？"))
                .setMessage(UiText.text(R.string.ui_6ed0c7435afe, "请先查看我的作品。只有确认没有该投稿后再重新提交。"))
                .setPositiveButton(UiText.text(R.string.ui_535b7b5781f3, "确认未发布，重试")) { _, _ -> model.publicationUncertain = false; submitForm() }
                .setNegativeButton(UiText.text(R.string.ui_84eef12f27a4, "返回检查"), null).show()
            return
        }
        runCatching {
            require(model.uri != null) { UiText.text(R.string.ui_5d994067311d, "请先选择视频") }
            val draft = VideoUploadDraft(titleInput.text.toString(), bodyInput.text.toString(),
                VideoUploadDraft.parseTags(tagsInput.text.toString()), if (rating.selectedItemPosition == 1) "ecchi" else "general",
                visibility.selectedItemPosition == 1, visibility.selectedItemPosition == 2, rules.isChecked)
            draft.validate()
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 89)
            model.submit(draft)
        }.onFailure { status.text = it.message ?: UiText.text(R.string.ui_86b20242665a, "请检查投稿信息") }
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
