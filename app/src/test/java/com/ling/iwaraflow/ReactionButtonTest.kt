package com.ling.iwaraflow

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 点赞 / 收藏按钮用圆润的爱心和五角星，动画播在按钮自己的位置上——
 * 以前是屏幕正中央弹一个大 ♥，按按钮更是完全没有反馈。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReactionButtonTest {
    private fun item() = VideoItem("v1", "Fixture", "作者", emptyList(), 3)

    private class Card(val adapter: VideoAdapter, val holder: VideoAdapter.Holder) {
        val like: ImageView = holder.itemView.findViewById(R.id.like)
        val favorite: ImageView = holder.itemView.findViewById(R.id.favorite)
        val burst: ReactionBurstView = holder.itemView.findViewById(R.id.reactionBurst)
    }

    private fun card(item: VideoItem, history: HistoryStore = mock(HistoryStore::class.java)): Card {
        val context = RuntimeEnvironment.getApplication()
        val api = mock(IwaraApi::class.java)
        `when`(api.isLoggedIn()).thenReturn(true)
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
        return Card(adapter, holder)
    }

    @Test fun theButtonsUseTheRoundedHeartAndStar() {
        val card = card(item())
        try {
            assertEquals("未点赞用圆润爱心描边",
                R.drawable.ic_heart_rounded_outline, shadowOf(card.like).imageResourceId)
            assertEquals("未收藏用圆润五角星描边",
                R.drawable.ic_star_rounded_outline, shadowOf(card.favorite).imageResourceId)
        } finally { card.adapter.releaseAll() }
    }

    @Test fun favouritingFillsTheStarAndPlaysTheBurstOnTheButton() {
        val history = mock(HistoryStore::class.java)
        `when`(history.isLocalFavorite(anyString())).thenReturn(false)
        val card = card(item(), history)
        try {
            card.favorite.performClick()
            assertEquals("收藏后要变成实心五角星",
                R.drawable.ic_star_rounded, shadowOf(card.favorite).imageResourceId)
            assertBurstSitsOn(card.favorite, card.burst)
        } finally { card.adapter.releaseAll() }
    }

    @Test fun likingPlaysTheBurstOnTheLikeButtonNotInTheMiddleOfTheScreen() {
        val card = card(item())
        try {
            card.like.performClick()
            assertBurstSitsOn(card.like, card.burst)
            assertNotEquals("动画不该再固定在屏幕正中央",
                card.burst.height / 2f, card.burst.burstCenterY, 1f)
        } finally { card.adapter.releaseAll() }
    }

    @Test fun aDoubleTapPlaysTheBurstWhereTheFingerWas() {
        val card = card(item())
        try {
            card.burst.playAt(240f, 900f, ReactionBurstView.Kind.LIKE)
            assertEquals(240f, card.burst.burstCenterX, 0.5f)
            assertEquals(900f, card.burst.burstCenterY, 0.5f)
        } finally { card.adapter.releaseAll() }
    }

    /** 动画中心必须落在按钮的方框内。 */
    private fun assertBurstSitsOn(button: View, burst: ReactionBurstView) {
        val buttonAt = IntArray(2)
        val burstAt = IntArray(2)
        button.getLocationOnScreen(buttonAt)
        burst.getLocationOnScreen(burstAt)
        val left = (buttonAt[0] - burstAt[0]).toFloat()
        val top = (buttonAt[1] - burstAt[1]).toFloat()
        assertTrue("按钮没有布局，测不出位置", button.width > 0 && button.height > 0)
        assertEquals("动画中心要对准按钮", left + button.width / 2f, burst.burstCenterX, 1f)
        assertEquals("动画中心要对准按钮", top + button.height / 2f, burst.burstCenterY, 1f)
    }
}
