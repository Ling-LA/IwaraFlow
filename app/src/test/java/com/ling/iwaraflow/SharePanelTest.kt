package com.ling.iwaraflow

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 分享面板列出能接收链接的应用，点谁就直接拉起谁（显式 Intent），不经过系统选择器——
 * 这样 QQ 这类应用会以自己的小窗卡片盖在本页上，视频在后面继续播。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SharePanelTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun app(packageName: String, activity: String, label: String) = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = activity
            applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
        }
        nonLocalizedLabel = label
    }

    private fun install(vararg apps: ResolveInfo) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        apps.forEach { shadowOf(context.packageManager).addResolveInfoForIntent(send, it) }
    }

    @Test fun commonSocialAppsComeFirstAndEachTargetIsAnExplicitIntent() {
        install(
            app("com.example.notes", "com.example.notes.Share", "Notes"),
            app("com.tencent.mobileqq", "com.tencent.mobileqq.activity.JumpActivity", "QQ"),
            app("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI", "微信")
        )
        val targets = SharePanel.targets(context, "标题\nhttps://www.iwara.tv/video/abc", "标题")
        assertEquals(listOf("com.tencent.mobileqq", "com.tencent.mm", "com.example.notes"), targets.map { it.packageName })
        val qq = targets.first()
        assertEquals("QQ", qq.label)
        assertEquals("com.tencent.mobileqq.activity.JumpActivity", qq.intent.component!!.className)
        assertEquals(Intent.ACTION_SEND, qq.intent.action)
        assertEquals("text/plain", qq.intent.type)
        assertEquals("标题\nhttps://www.iwara.tv/video/abc", qq.intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun theAppItselfIsNotOfferedAsATarget() {
        install(app(context.packageName, "com.ling.iwaraflow.MainActivityV3", "IwaraFlow"))
        assertTrue(SharePanel.targets(context, "x", "x").isEmpty())
    }

    @Test fun unknownAppsRankAfterTheKnownOnes() {
        assertTrue(SharePanel.rank("com.tencent.mobileqq") < SharePanel.rank("com.tencent.mm"))
        assertTrue(SharePanel.rank("com.tencent.mm") < SharePanel.rank("com.example.other"))
        assertEquals(SharePanel.rank("com.example.a"), SharePanel.rank("com.example.b"))
    }

    @Test fun nothingInstalledMeansNoTargets() {
        assertTrue(SharePanel.targets(context, "x", "x").isEmpty())
    }
}
