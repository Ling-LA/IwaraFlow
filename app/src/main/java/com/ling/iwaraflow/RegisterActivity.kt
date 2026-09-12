package com.ling.iwaraflow

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

/**
 * 注册 Iwara 账号：把官网的注册页装在应用内打开。
 *
 * 官网注册要过人机验证，再到邮箱里点确认链接设置用户名和密码，这些步骤只有官网页面
 * 自己能完成，所以不另拼一套接口，而是直接内嵌 `www.iwara.tv/register`：在这里填完
 * 就是在官网注册了，之后回到登录窗口用同一个邮箱和密码登录即可。
 * 站内链接留在页面里，站外链接（邮箱服务等）交给系统浏览器。
 */
class RegisterActivity : AppCompatActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)
        web = findViewById(R.id.registerWeb)
        val progress = findViewById<ProgressBar>(R.id.registerProgress)
        val status = findViewById<TextView>(R.id.registerStatus)
        findViewById<View>(R.id.registerBack).setOnClickListener { finish() }
        findViewById<View>(R.id.registerOpenBrowser).setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web.url ?: REGISTER_URL))) }
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress >= 100) View.GONE else View.VISIBLE
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host.orEmpty().endsWith("iwara.tv")) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                status.text = "在官网页面填写邮箱完成注册，到邮箱确认并设好密码后，回到登录窗口登录。"
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })
        if (savedInstanceState == null) web.loadUrl(REGISTER_URL) else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val REGISTER_URL = "https://www.iwara.tv/register"
    }
}
