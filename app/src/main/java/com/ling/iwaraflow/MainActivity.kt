package com.ling.iwaraflow

import android.Manifest
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.util.Rational
import android.view.View
import android.view.WindowInsets
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2

/**
 * Legacy activity kept only as a rollback entry. The launcher uses MainActivityV3.
 * Keep its adapter wiring compile-compatible with the current player stack.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var api: IwaraApi
    private lateinit var history: HistoryStore
    private lateinit var prefs: AppPrefs
    private lateinit var recommender: RecommendationEngine
    private lateinit var mediaCache: MediaPreloadCache
    private lateinit var pager: ViewPager2
    private lateinit var loading: ProgressBar
    private lateinit var error: TextView
    private lateinit var adapter: VideoAdapter
    private lateinit var topBar: View

    private var mode = "recommend"
    private var currentPage = 0
    private var searchQuery: String? = null
    private var loadingMore = false
    private var pendingAdvanceAfterLoad = false
    private var pagingEnabled = true
    private val pageSize = 28

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        hideStatusBar()

        api = IwaraApi(this)
        history = HistoryStore(this)
        prefs = AppPrefs(this)
        recommender = RecommendationEngine(api, history)
        mediaCache = MediaPreloadCache(this)

        pager = findViewById(R.id.pager)
        loading = findViewById(R.id.loading)
        error = findViewById(R.id.error)
        topBar = findViewById(R.id.topBar)

        adapter = VideoAdapter(
            api = api,
            history = history,
            prefs = prefs,
            mediaCache = mediaCache,
            onDownload = ::enqueueDownload,
            onEnterPip = ::enterPip,
            onEnded = ::onVideoEnded,
            onNeedLogin = ::showLoginDialog
        )
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                adapter.setActive(position)
                if (pagingEnabled && position >= adapter.itemCount - 5) loadMore()
            }
        })

        setupTopBar()
        loadFeed(reset = true)
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.hide(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun setupTopBar() {
        val recommend = findViewById<TextView>(R.id.tabRecommend)
        val trending = findViewById<TextView>(R.id.tabTrending)
        val popular = findViewById<TextView>(R.id.tabPopular)
        val latest = findViewById<TextView>(R.id.tabLatest)
        val search = findViewById<TextView>(R.id.btnSearch)
        val menu = findViewById<TextView>(R.id.btnMenu)
        val tabs = listOf(recommend, trending, popular, latest)

        fun select(newMode: String, selected: TextView) {
            mode = newMode
            pagingEnabled = true
            searchQuery = null
            currentPage = 0
            tabs.forEach { tab ->
                tab.setTextColor(if (tab === selected) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt())
                tab.setTypeface(null, if (tab === selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            loadFeed(reset = true)
        }

        recommend.setOnClickListener { select("recommend", recommend) }
        trending.setOnClickListener { select("trending", trending) }
        popular.setOnClickListener { select("popularity", popular) }
        latest.setOnClickListener { select("date", latest) }
        search.setOnClickListener { showSearchDialog() }
        menu.setOnClickListener { showMainMenu() }
    }

    private fun loadFeed(reset: Boolean) {
        if (reset) {
            currentPage = 0
            loading.visibility = View.VISIBLE
            error.visibility = View.GONE
        }
        if (mode == "recommend" && searchQuery == null && currentPage == 0) {
            recommender.load(prefs.skipSeen) { result ->
                runOnUiThread {
                    loading.visibility = View.GONE
                    result.onSuccess { raw ->
                        val list = decorateAndFilter(raw)
                        if (list.isEmpty()) showError("没有可推荐的视频；如果开启了“跳过已看”，可以在设置里关闭后重试。")
                        else {
                            adapter.replace(list)
                            pager.setCurrentItem(0, false)
                            adapter.setActive(0)
                        }
                    }.onFailure { showError("推荐加载失败\n${it.message}") }
                }
            }
            return
        }

        val callback: (Result<List<VideoItem>>) -> Unit = { result ->
            runOnUiThread {
                loading.visibility = View.GONE
                loadingMore = false
                result.onSuccess { raw ->
                    val list = decorateAndFilter(raw)
                    if (reset) {
                        if (list.isEmpty()) showError("没有获取到视频")
                        else {
                            adapter.replace(list)
                            pager.setCurrentItem(0, false)
                            adapter.setActive(0)
                        }
                    } else {
                        val before = adapter.itemCount
                        adapter.append(list)
                        if (pendingAdvanceAfterLoad && adapter.itemCount > before) {
                            pendingAdvanceAfterLoad = false
                            pager.setCurrentItem(before, true)
                        }
                    }
                }.onFailure { if (reset) showError("Iwara 数据加载失败\n${it.message}\n\n请确认网络可以访问 iwara.tv") }
            }
        }

        val query = searchQuery
        if (!query.isNullOrBlank()) {
            api.searchVideos(query, currentPage, pageSize, callback)
        } else {
            val sort = if (mode == "recommend") "trending" else mode
            api.getVideos(sort, currentPage, pageSize, callback)
        }
    }

    private fun loadMore() {
        if (loadingMore || adapter.itemCount == 0) return
        loadingMore = true
        currentPage += 1
        loadFeed(reset = false)
    }

    private fun decorateAndFilter(raw: List<VideoItem>): List<VideoItem> {
        return raw.filter { item ->
            item.localFavorite = history.isLocalFavorite(item.id)
            !prefs.skipSeen || !history.isSeen(item.id)
        }
    }

    private fun onVideoEnded(position: Int) {
        if (position + 1 < adapter.itemCount) pager.setCurrentItem(position + 1, true)
        else {
            pendingAdvanceAfterLoad = true
            loadMore()
        }
    }

    private fun showSearchDialog() {
        val input = EditText(this).apply {
            hint = "视频标题、标签、作者关键词"
            setSingleLine(true)
            setPadding(48, 16, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("搜索 Iwara 视频")
            .setView(input)
            .setNegativeButton("取消", null)
            .setPositiveButton("搜索") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotBlank()) {
                    searchQuery = q
                    mode = "search"
                    pagingEnabled = true
                    currentPage = 0
                    loadFeed(reset = true)
                }
            }.show()
    }

    private fun showMainMenu() {
        val account = if (api.isLoggedIn()) "退出 Iwara 登录" else "登录 Iwara"
        val items = arrayOf(account, "浏览历史", "Iwara 收藏/点赞", "设置", "重新加载当前流")
        AlertDialog.Builder(this)
            .setTitle("IwaraFlow")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (api.isLoggedIn()) {
                        api.logout()
                        Toast.makeText(this, "已退出登录", Toast.LENGTH_SHORT).show()
                        loadFeed(reset = true)
                    } else showLoginDialog()
                    1 -> showHistoryDialog()
                    2 -> showRemoteFavorites()
                    3 -> showSettingsDialog()
                    4 -> loadFeed(reset = true)
                }
            }.show()
    }

    private fun showLoginDialog() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 8, 48, 0) }
        val email = EditText(this).apply {
            hint = "Iwara 邮箱"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }
        val password = EditText(this).apply {
            hint = "密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        container.addView(email)
        container.addView(password)
        val dialog = AlertDialog.Builder(this)
            .setTitle("登录 Iwara")
            .setMessage("密码仅用于本次 /user/login 请求，不会保存；登录 Token 使用 Android 加密存储。")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("登录", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mail = email.text.toString().trim()
                val pass = password.text.toString()
                if (mail.isBlank() || pass.isBlank()) {
                    Toast.makeText(this, "请输入邮箱和密码", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                api.login(mail, pass) { result ->
                    runOnUiThread {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
                        if (result.success) { dialog.dismiss(); loadFeed(reset = true) }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showHistoryDialog() {
        val list = history.recentHistory(100)
        if (list.isEmpty()) {
            Toast.makeText(this, "还没有浏览历史", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = list.map { "${it.title}\n@${it.author}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("浏览历史")
            .setItems(labels) { _, which -> openSingleVideo(list[which].id) }
            .setNegativeButton("关闭", null).show()
    }

    private fun showRemoteFavorites() {
        if (!api.isLoggedIn()) { showLoginDialog(); return }
        loading.visibility = View.VISIBLE
        api.getFavoriteVideos { result ->
            runOnUiThread {
                loading.visibility = View.GONE
                result.onSuccess { list ->
                    val decorated = decorateAndFilter(list).ifEmpty { list }
                    if (decorated.isEmpty()) Toast.makeText(this, "Iwara 收藏为空", Toast.LENGTH_SHORT).show()
                    else {
                        mode = "favorites"
                        pagingEnabled = false
                        searchQuery = null
                        adapter.replace(decorated)
                        pager.setCurrentItem(0, false)
                        adapter.setActive(0)
                    }
                }.onFailure { Toast.makeText(this, it.message ?: "收藏加载失败", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun openSingleVideo(videoId: String) {
        loading.visibility = View.VISIBLE
        api.getVideo(videoId) { result ->
            runOnUiThread {
                loading.visibility = View.GONE
                result.onSuccess { item ->
                    item.localFavorite = history.isLocalFavorite(item.id)
                    pagingEnabled = false
                    mode = "single"
                    searchQuery = null
                    adapter.replace(listOf(item))
                    pager.setCurrentItem(0, false)
                    adapter.setActive(0)
                }.onFailure { Toast.makeText(this, it.message ?: "视频加载失败", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showSettingsDialog() {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 12, 48, 8) }
        val skipSeen = CheckBox(this).apply { text = "推荐/热门流自动跳过已看视频"; isChecked = prefs.skipSeen }
        val autoNext = CheckBox(this).apply { text = "播放完毕自动进入下一条"; isChecked = prefs.autoNext }
        val autoPip = CheckBox(this).apply { text = "切到后台时自动进入画中画"; isChecked = prefs.autoPip }
        val qualityLabel = TextView(this).apply { text = "默认清晰度"; setPadding(0, 18, 0, 4) }
        val qualityValues = arrayOf("highest", "Source", "1080", "720", "540", "360")
        val qualityNames = arrayOf("最高可用/原画", "Source", "1080p", "720p", "540p", "360p")
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, qualityNames)
        spinner.setSelection(qualityValues.indexOf(prefs.defaultQuality).let { if (it >= 0) it else 0 })
        panel.addView(skipSeen); panel.addView(autoNext); panel.addView(autoPip); panel.addView(qualityLabel); panel.addView(spinner)
        AlertDialog.Builder(this)
            .setTitle("播放与推荐设置")
            .setView(panel)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                prefs.skipSeen = skipSeen.isChecked
                prefs.autoNext = autoNext.isChecked
                prefs.autoPip = autoPip.isChecked
                prefs.defaultQuality = qualityValues[spinner.selectedItemPosition]
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
                loadFeed(reset = true)
            }.show()
    }

    private fun enqueueDownload(item: VideoItem, source: VideoSource) {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 901)
            Toast.makeText(this, "请授予存储权限后再次点击下载", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val safeTitle = item.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(90).ifBlank { item.id }
            val safeQuality = source.name.replace(Regex("[^0-9A-Za-z_-]"), "_")
            val fileName = "${safeTitle}_${item.id}_${safeQuality}.mp4"
            val request = DownloadManager.Request(Uri.parse(source.url))
                .setTitle(item.title)
                .setDescription("IwaraFlow · ${source.name}")
                .setMimeType("video/mp4")
                .addRequestHeader("Referer", "https://www.iwara.tv/")
                .addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/152 Mobile Safari/537.36")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "IwaraFlow/$fileName")
            val downloadId = (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            history.recordInteraction(item, "download", 1.1)
            history.recordDownload(item, source.name, downloadId)
            Toast.makeText(this, "已加入系统下载：${source.name}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "下载创建失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        } catch (e: Exception) {
            Toast.makeText(this, "画中画启动失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (prefs.autoPip && adapter.isActivePlaying() && !isInPictureInPictureMode) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode)
        topBar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        adapter.setPipMode(isInPictureInPictureMode)
    }

    override fun onStart() {
        super.onStart()
        if (!isInPictureInPictureMode) adapter.resumeActive()
    }

    override fun onStop() {
        if (!isInPictureInPictureMode) adapter.pauseAll()
        super.onStop()
    }

    private fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        adapter.releaseAll()
        mediaCache.close()
        recommender.close()
        api.close()
        history.close()
        super.onDestroy()
    }
}
