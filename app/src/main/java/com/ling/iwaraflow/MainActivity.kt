package com.ling.iwaraflow

import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2

class MainActivity : AppCompatActivity() {
    private val api = IwaraApi()
    private lateinit var pager: ViewPager2
    private lateinit var loading: ProgressBar
    private lateinit var error: TextView
    private lateinit var adapter: VideoAdapter
    private var sort = "trending"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (android.os.Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.statusBars())
        else @Suppress("DEPRECATION") run { window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY }

        pager = findViewById(R.id.pager); loading = findViewById(R.id.loading); error = findViewById(R.id.error)
        adapter = VideoAdapter(api) { _, heart ->
            heart.text = "♥"; heart.setTextColor(0xFFFF365D.toInt())
            Toast.makeText(this, "已加入本地点赞（账号同步将在登录模块启用）", Toast.LENGTH_SHORT).show()
        }
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { adapter.setActive(position) }
        })

        val trending = findViewById<TextView>(R.id.tabTrending)
        val popular = findViewById<TextView>(R.id.tabPopular)
        val latest = findViewById<TextView>(R.id.tabLatest)
        fun select(s: String, selected: TextView) {
            sort = s
            listOf(trending, popular, latest).forEach { it.setTextColor(if (it === selected) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt()) }
            load()
        }
        trending.setOnClickListener { select("trending", trending) }
        popular.setOnClickListener { select("popularity", popular) }
        latest.setOnClickListener { select("date", latest) }
        load()
    }

    private fun load() {
        loading.visibility = View.VISIBLE; error.visibility = View.GONE
        api.getVideos(sort, limit = 24) { result -> runOnUiThread {
            loading.visibility = View.GONE
            result.onSuccess { list ->
                if (list.isEmpty()) showError("没有获取到视频") else { adapter.replace(list); pager.setCurrentItem(0, false) }
            }.onFailure { showError("Iwara 数据加载失败\n${it.message}\n\n请确认网络可以访问 iwara.tv") }
        }}
    }

    private fun showError(msg: String) { error.text = msg; error.visibility = View.VISIBLE }
    override fun onDestroy() { adapter.releaseAll(); super.onDestroy() }
}
