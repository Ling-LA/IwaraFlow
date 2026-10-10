package com.ling.iwaraflow

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Records existing production views on API 36. Only data is synthetic; no real account or writes. */
@RunWith(AndroidJUnit4::class)
class PromoRecordingTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val export = "/sdcard/Download/IwaraFlow-Android16-promo"
    private fun main(block: () -> Unit) = ins.runOnMainSync(block)
    private fun field(target: Any, name: String): java.lang.reflect.Field {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try { return type.getDeclaredField(name).apply { isAccessible = true } }
            catch (_: NoSuchFieldException) { type = type.superclass }
        }
        error("Missing ${target.javaClass.simpleName}.$name")
    }
    private fun get(target: Any, name: String) = field(target, name).get(target)
    private fun set(target: Any, name: String, value: Any?) = field(target, name).set(target, value)
    private fun invoke(target: Any, name: String, vararg args: Any) = target.javaClass.declaredMethods
        .single { it.name == name }.apply { isAccessible = true }.invoke(target, *args)
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        ins.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun views(v: View): List<View> = buildList {
        add(v); if (v is ViewGroup) repeat(v.childCount) { addAll(views(v.getChildAt(it))) }
    }
    private fun clickText(activity: Activity, label: String) = main {
        val view = views(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == label }
        assertTrue(view.performClick())
    }
    private fun launch(type: Class<out Activity>): Activity = ins.startActivitySync(
        Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also { ins.waitForIdleSync() }
    private fun asset(name: String): File = File(context.filesDir, "promo-$name").also { out ->
        ins.context.assets.open("promo/$name").use { input -> out.outputStream().use { input.copyTo(it) } }
    }
    private fun record(name: String, seconds: Int, action: () -> Unit = {}) {
        ins.waitForIdleSync()
        val descriptor = ins.uiAutomation.executeShellCommand(
            "screenrecord --size 1080x1920 --bit-rate 9000000 --time-limit $seconds $export/$name.mp4")
        val start = SystemClock.elapsedRealtime()
        SystemClock.sleep(700)
        action()
        val remaining = seconds * 1000L + 600 - (SystemClock.elapsedRealtime() - start)
        if (remaining > 0) SystemClock.sleep(remaining)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        val bytes = shell("wc -c $export/$name.mp4").trim().substringBefore(' ').toLongOrNull() ?: 0L
        assertTrue("Recording $name is empty", bytes > 10_000)
        val screenshot = ins.uiAutomation.takeScreenshot()
        val local = File(context.getExternalFilesDir(null), "$name.png")
        local.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
        shell("cp ${local.absolutePath} $export/$name.png")
    }
    private fun gesture(x: Float, y: Float, x2: Float = x, y2: Float = y, duration: Long) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, progress: Float) {
            val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                x + (x2-x)*progress, y + (y2-y)*progress, 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            ins.sendPointerSync(event); event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, 0f)
        val steps = (duration / 20).toInt().coerceAtLeast(1)
        repeat(steps) { SystemClock.sleep(20); send(MotionEvent.ACTION_MOVE, (it+1f)/steps) }
        send(MotionEvent.ACTION_UP, 1f)
    }
    private fun hold(view: View, duration: Long) {
        val xy = IntArray(2); var x = 0f; var y = 0f
        main { view.getLocationOnScreen(xy); x = xy[0]+view.width/2f; y = xy[1]+view.height/2f }
        gesture(x, y, duration = duration)
    }
    private fun neutralApi(api: IwaraApi) {
        api.cancelPendingRequests()
        // A transport fixture supplies response data, never rewrites or overlays UI.
        set(api, "client", OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().url.encodedPath.endsWith("/comments"))
                """{"count":3,"results":[{"id":"c1","body":"海风与音乐，刚刚好。","user":{"id":"p1","name":"听风的人","username":"wind"}},{"id":"c2","body":"喜欢这一刻的光影。","user":{"id":"p2","name":"光影笔记","username":"light"}},{"id":"c3","body":"让喜欢，流动起来。","user":{"id":"p3","name":"音乐漫游","username":"music"}}]}"""
            else """{"count":0,"results":[]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Local demo fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
    }
    private fun currentHolder(home: MainActivityV3): VideoAdapter.Holder {
        var result: VideoAdapter.Holder? = null
        val until = SystemClock.uptimeMillis()+15000
        while (SystemClock.uptimeMillis()<until) {
            main {
                val pager=home.findViewById<ViewPager2>(R.id.pager)
                result=(pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(pager.currentItem) as? VideoAdapter.Holder
            }
            if (result != null) return result!!
            SystemClock.sleep(40)
        }
        error("Player card did not bind")
    }
    private fun awaitPlaying(home: MainActivityV3) {
        val until=SystemClock.uptimeMillis()+30000
        while(SystemClock.uptimeMillis()<until) {
            var ready=false
            val holder=currentHolder(home)
            main { (get(holder,"player") as? ExoPlayer)?.let { it.repeatMode=Player.REPEAT_MODE_ONE; it.play(); ready=it.isPlaying && it.videoSize.width>0 } }
            if(ready)return
            SystemClock.sleep(60)
        }
        error("Local example did not play")
    }
    @Test fun recordAndroid16FeatureTour() {
        assertEquals(36, Build.VERSION.SDK_INT)
        shell("mkdir -p $export")
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        context.getSharedPreferences(AppPrefs.FILE,0).edit()
            .putBoolean(OverlayPermissionPrompt.KEY_SHOWN,true).putBoolean(PlaybackGuide.key(false),true)
            .putBoolean(PlaybackGuide.key(true),true).putBoolean("auto_open_clipboard_links",false)
            .putBoolean("auto_next",false).putBoolean("danmaku_enabled",false).commit()
        val day=asset("day.mp4");val night=asset("night.mp4")
        val dayThumb=asset("day.jpg");val nightThumb=asset("night.jpg");val avatar=asset("avatar.png")
        fun video(id: String, title: String, nightTime: Boolean=false)=VideoItem(id,title,"光影漫游",listOf("animation","music","nature"),128,
            views=12000,createdAt=1791244800000L,authorId="demo-author",authorUsername="sample_creator",
            thumbnailUrl=Uri.fromFile(if(nightTime)nightThumb else dayThumb).toString(),
            sources=listOf(VideoSource("1080p",Uri.fromFile(if(nightTime)night else day).toString(),1080)),
            description="原创中性示例：用光影、海风和音乐，记录片刻宁静。")
        val items=listOf(video("promo-day","海风与光 · 原创动画"),video("promo-night","月色与海 · 音乐漫游",true))
        val session=SecureSessionStore(context);session.clearAuthentication()
        val home=launch(MainActivityV3::class.java) as MainActivityV3
        main {
            set(home,"requestSerial",(get(home,"requestSerial") as Int)+100)
            set(home,"pagingEnabled",false)
            neutralApi(get(home,"api") as IwaraApi)
            home.findViewById<View>(R.id.loading).visibility=View.GONE
            home.findViewById<View>(R.id.error).visibility=View.GONE
            val adapter=get(home,"adapter") as VideoAdapter;adapter.replace(items);adapter.setActive(0)
        }
        session.refreshToken="LOCAL-PROMO-NOT-A-REAL-ACCOUNT"
        session.accessToken="e30.eyJleHAiOjQxMDI0NDQ4MDB9.ZGVtby1ub3Qtc2lnbmVk"
        awaitPlaying(home)
        record("01-feed",10) {
            SystemClock.sleep(2600)
            gesture(460f,1450f,460f,450f,420)
            awaitPlaying(home)
        }
        record("02-reaction",10) {
            SystemClock.sleep(1400)
            hold(currentHolder(home).itemView.findViewById(R.id.like),2300)
            SystemClock.sleep(1800)
            main { assertTrue(items[1].liked);assertTrue(items[1].localFavorite) }
        }
        record("03-danmaku",10) {
            main { AppPrefs(context).danmakuEnabled=true; (get(home,"adapter") as VideoAdapter).applyDisplayPrefs() }
            SystemClock.sleep(2100)
            main { currentHolderView(home).findViewById<View>(R.id.comments).performClick() }
            SystemClock.sleep(1500)
            main { home.findViewById<View>(R.id.panelTabDanmaku).performClick() }
        }
        main { (get(home,"comments") as CommentsHost).close() }
        main { PlaybackGuide.show(home,false) }
        record("04-guide",7)
        main { PlaybackGuide.dismiss(home) }
        HistoryStore(context).use { h ->
            h.setManualTagPreference("music",1);h.setManualTagPreference("animation",1)
            listOf("music","animation","nature","art").forEachIndexed { i, tag ->
                repeat(3) { h.recordInteraction(video("interest-$i-$it","示例作品").copy(tags=listOf(tag)),"like",1.0) }
            }
        }
        val interest=launch(InterestActivity::class.java)
        record("05-interest",10) {
            SystemClock.sleep(1200)
            main { views(interest.window.decorView).filterIsInstance<EditText>().first().setText("nature") }
            SystemClock.sleep(600);clickText(interest,"添加感兴趣")
            SystemClock.sleep(2100);clickText(interest,"系统兴趣")
        }
        main { interest.finish() }
        val search=launch(SearchActivity::class.java) as SearchActivity
        main {
            neutralApi(get(search,"api") as IwaraApi)
            set(search,"querySerial",(get(search,"querySerial") as Int)+100)
            val plan=SearchQuery(listOf(listOf("音乐","music","音楽"),listOf("动画","animation","アニメ")))
            set(search,"query","音乐 动画");set(search,"queries",plan.seeds)
            set(search,"searchPlan",plan);set(search,"availablePlan",plan);set(search,"expansionStarted",true)
            (get(search,"input") as EditText).setText("音乐 动画")
            val tab=get(search,"videoTab")!!
            val results=listOf(video("s1","音乐与动画 · 光影"),video("s2","Music in animation",true),video("s3","音楽とアニメ"))
            listOf("loaded","items","playable").forEach { name ->
                @Suppress("UNCHECKED_CAST") val list=get(tab,name) as MutableList<VideoItem>;list.clear();list.addAll(results)
            }
            set(tab,"started",true)
            (get(search,"videoAdapter") as AuthorVideoListAdapter).notifyDataSetChanged();invoke(search,"updateStatus")
        }
        record("06-search",10) {
            SystemClock.sleep(2800);main { invoke(search,"showMatchOptions") }
            SystemClock.sleep(2500);ins.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        }
        main { search.finish() };session.clearAuthentication()
        val my=launch(MyActivity::class.java) as MyActivity
        main {
            (get(my,"api") as IwaraApi).close();set(my,"more",false)
            my.bindProfile(IwaraAuthor("demo-author","光影漫游","sample_creator","分享音乐、动画与生活中的光。",Uri.fromFile(avatar).toString()))
            @Suppress("UNCHECKED_CAST") val videos=get(my,"videos") as MutableList<VideoItem>
            videos.addAll(items+video("third","日光之下 · 新的旅程"))
            (get(my,"listAdapter") as AuthorVideoListAdapter).notifyDataSetChanged()
            my.findViewById<TextView>(R.id.authorStatus).text="作品 3 条"
        }
        record("07-profile",7)
        main { my.findViewById<View>(R.id.authorShare).performClick() };SystemClock.sleep(1000)
        record("08-upload",8) {
            SystemClock.sleep(3200)
            gesture(500f,1560f,500f,830f,600)
        }
        shell("getprop ro.build.version.release").also { assertTrue(it.trim().startsWith("16")) }
        println("PROMO VERIFIED: API ${Build.VERSION.SDK_INT}, Android ${Build.VERSION.RELEASE}, native UI recordings complete; synthetic data only.")
    }
    private fun currentHolderView(home: MainActivityV3): View {
        val pager=home.findViewById<ViewPager2>(R.id.pager)
        return ((pager.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(pager.currentItem) as VideoAdapter.Holder).itemView
    }
}
