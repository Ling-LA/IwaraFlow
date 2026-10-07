package com.ling.iwaraflow

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/** The official publishing form owns validation, upload credentials, encoding and submission. */
class UploadActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var chooser: ValueCallback<Array<Uri>>? = null
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        chooser?.onReceiveValue(uri?.let { arrayOf(it) }); chooser = null
    }
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val page = PageLayout(this, "上传视频")
        val card = page.card()
        card.addView(page.text("在官网表单选择视频并填写标题、简介和标签，提交后直接发布到 Iwara。首次使用需要在此登录官网。", 14f))
        card.addView(page.action("在浏览器打开官网上传页  ↗") {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UPLOAD_URL))) }
        })
        page.body.addView(card)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        page.body.addView(progress, LinearLayout.LayoutParams(-1, page.dp(3)))
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, value: Int) { progress.progress = value }
                override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>, params: FileChooserParams?): Boolean {
                    if (!isOfficial(web.url)) { callback.onReceiveValue(null); return true }
                    chooser?.onReceiveValue(null); chooser = callback
                    val types = params?.acceptTypes?.filter { it.startsWith("video/") || it.startsWith("image/") }?.toTypedArray()
                    filePicker.launch(if (types.isNullOrEmpty()) arrayOf("video/*") else types)
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (isOfficial(request.url.toString())) return false
                    if (request.isForMainFrame && request.url.scheme == "https") {
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
                    }
                    return request.isForMainFrame
                }
            }
        }
        page.body.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        if (state == null) web.loadUrl(UPLOAD_URL) else web.restoreState(state)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
    }
    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); web.saveState(out) }
    override fun onDestroy() { chooser?.onReceiveValue(null); chooser = null; web.destroy(); super.onDestroy() }
    companion object {
        const val UPLOAD_URL = "https://www.iwara.tv/upload"
        internal fun isOfficial(url: String?): Boolean = Uri.parse(url ?: "").let {
            it.scheme == "https" && (it.host == "iwara.tv" || it.host == "www.iwara.tv")
        }
    }
}
