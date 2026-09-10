package com.ling.iwaraflow

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 点赞 / 收藏按钮用圆润的爱心和五角星，动画播在按钮自己的位置上——
 * 以前是屏幕正中央弹一个大 ♥，按按钮更是完全没有反馈。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReactionButtonTest {
    private fun item() = VideoItem("v1", "Fixture", "作者", emptyList(), 3)

    private class Card(
        val adapter: VideoAdapter,
        val holder: VideoAdapter.Holder,
        private val history: HistoryStore
    ) {
        val like: ImageView = holder.itemView.findViewById(R.id.like)
        val favorite: ImageView = holder.itemView.findViewById(R.id.favorite)
        val burst: ReactionBurstView = holder.itemView.findViewById(R.id.reactionBurst)
        fun close() {
            adapter.releaseAll()
            history.close()
        }
    }

    /** 收藏走的是真的 HistoryStore：是它把 localFavorite 写回视频对象的。 */
    private fun card(item: VideoItem): Card {
        val context = RuntimeEnvironment.getApplication()
        val api = mock(IwaraApi::class.java)
        `when`(api.isLoggedIn()).thenReturn(true)
        val history = HistoryStore(context)
        val adapter = VideoAdapter(api, history, AppPrefs(context),
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {})
        adapter.replace(listOf(item))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        // 没有布局就没有按钮位置，动画落在哪也就无从谈起。
        val root = holder.itemView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        root.layout(0, 0, 1080, 1920)
        return Card(adapter, holder, history)
    }

    @Test fun theButtonsUseTheRoundedHeartAndStar() {
        val card = card(item())
        try {
            assertEquals("未点赞用圆润爱心描边",
                R.drawable.ic_heart_rounded_outline, iconOf(card.like))
            assertEquals("未收藏用圆润五角星描边",
                R.drawable.ic_star_rounded_outline, iconOf(card.favorite))
        } finally { card.close() }
    }

    @Test fun favouritingFillsTheStarAndPlaysTheBurstOnTheButton() {
        val card = card(item())
        try {
            card.favorite.performClick()
            assertEquals("收藏后要变成实心五角星",
                R.drawable.ic_star_rounded, iconOf(card.favorite))
            assertBurstSitsOn(card.favorite, card.burst)
        } finally { card.close() }
    }

    @Test fun likingPlaysTheBurstOnTheLikeButtonNotInTheMiddleOfTheScreen() {
        val card = card(item())
        try {
            card.like.performClick()
            assertBurstSitsOn(card.like, card.burst)
            // 操作栏在右下角，屏幕正中央（540, 960）不可能落在按钮上。
            assertTrue("动画不该再固定在屏幕正中央：${card.burst.burstCenterX}",
                card.burst.burstCenterX > card.burst.width * 0.75f)
            assertTrue("动画不该再固定在屏幕正中央：${card.burst.burstCenterY}",
                card.burst.burstCenterY > card.burst.height * 0.5f)
        } finally { card.close() }
    }

    @Test fun theTwoButtonsBurstAtDifferentPlaces() {
        val card = card(item())
        try {
            card.like.performClick()
            val likeY = card.burst.burstCenterY
            card.favorite.performClick()
            // 收藏按钮排在点赞按钮下面，动画也该跟着下来。
            assertTrue("两个按钮的动画位置不能是同一个：赞 $likeY / 藏 ${card.burst.burstCenterY}",
                card.burst.burstCenterY > likeY)
        } finally { card.close() }
    }

    @Test fun aDoubleTapPlaysTheBurstWhereTheFingerWas() {
        val card = card(item())
        try {
            card.burst.playAt(240f, 900f, ReactionBurstView.Kind.LIKE)
            assertEquals(240f, card.burst.burstCenterX, 0.5f)
            assertEquals(900f, card.burst.burstCenterY, 0.5f)
        } finally { card.close() }
    }

    /** 按钮当前是哪个图标。ImageView 不回传资源 id，适配器把它记在 tag 上。 */
    private fun iconOf(button: ImageView): Int = button.getTag(R.id.reaction_icon) as Int

    /**
     * 动画中心必须落在按钮的方框内。按钮的位置这里自己从卡片根布局一层层加出来，
     * 不复用被测代码那套换算。
     */
    private fun assertBurstSitsOn(button: View, burst: ReactionBurstView) {
        assertTrue("按钮没有布局，测不出位置", button.width > 0 && button.height > 0)
        var left = 0f
        var top = 0f
        var node: View? = button
        while (node != null && node !== burst.parent) {
            left += node.left
            top += node.top
            node = node.parent as? View
        }
        assertNotNull("按钮不在动画层的同一个容器里", node)
        assertEquals("动画中心要对准按钮", left + button.width / 2f, burst.burstCenterX, 1f)
        assertEquals("动画中心要对准按钮", top + button.height / 2f, burst.burstCenterY, 1f)
    }
}
