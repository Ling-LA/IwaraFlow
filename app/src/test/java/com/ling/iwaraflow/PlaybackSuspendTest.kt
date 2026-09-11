package com.ling.iwaraflow

import android.widget.FrameLayout
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 系统分享面板是半透明的：它盖上来时播放页只走 onPause，不走 onStop——因为页面还看得见。
 * 那一刻如果按“进后台”的做法把播放器释放掉，`playerView` 的 surface 一空画面就是纯黑，
 * 而面板连上半屏都没盖住，看上去就是视频莫名其妙黑了。
 *
 * 所以 onPause 只暂停、留住播放器和最后一帧；真的进了后台，onStop 再释放。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackSuspendTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private class Card(val adapter: VideoAdapter, val holder: VideoAdapter.Holder, val player: ExoPlayer)

    /** 造一张“正在播”的卡片：塞一个假播放器进去并标成 active。 */
    private fun playingCard(): Card {
        val adapter = VideoAdapter(
            mock(IwaraApi::class.java), mock(HistoryStore::class.java), AppPrefs(context),
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {}
        )
        adapter.replace(listOf(VideoItem("v1", "Fixture", "作者", emptyList(), 0)))
        val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        val player = mock(ExoPlayer::class.java)
        set(holder, "player", player)
        set(holder, "active", true)
        return Card(adapter, holder, player)
    }

    private fun set(target: Any, name: String, value: Any?) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)

    private fun get(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun playerOf(holder: VideoAdapter.Holder) = get(holder, "player")

    @Test fun suspendingKeepsThePlayerSoTheFrameStaysOnScreen() {
        val card = playingCard()
        try {
            card.adapter.suspendPlayback()
            assertNotNull("暂停不该把播放器丢掉——丢了画面就黑", playerOf(card.holder))
            verify(card.player, never()).release()
            verify(card.player).pause()
        } finally { card.adapter.releaseAll() }
    }

    @Test fun goingToTheBackgroundStillReleasesThePlayer() {
        val card = playingCard()
        try {
            card.adapter.pauseAll()
            assertNull("真的进后台要把解码器还给系统", playerOf(card.holder))
            verify(card.player).release()
        } finally { card.adapter.releaseAll() }
    }

    @Test fun comingBackResumesTheSamePlayerInsteadOfStartingOver() {
        val card = playingCard()
        try {
            card.adapter.suspendPlayback()
            val suspended = playerOf(card.holder)
            // 这里直接驱动卡片，不走 resumeActive：没有真正的 RecyclerView，
            // holder 的 bindingAdapterPosition 是 NO_POSITION，对不上号。
            set(card.adapter, "playbackEnabled", true)
            card.holder.setActive(true)
            assertSame("回来时应该接着用原来那个播放器", suspended, playerOf(card.holder))
            verify(card.player).play()
        } finally { card.adapter.releaseAll() }
    }

    /** onPause 之后紧接着 onStop（真的进后台）：先暂停再释放，顺序不能出错。 */
    @Test fun suspendThenStopStillReleases() {
        val card = playingCard()
        try {
            card.adapter.suspendPlayback()
            card.adapter.pauseAll()
            assertNull(playerOf(card.holder))
            verify(card.player).release()
        } finally { card.adapter.releaseAll() }
    }

    @Test fun suspendingACardThatIsNotPlayingDoesNothing() {
        val adapter = VideoAdapter(
            mock(IwaraApi::class.java), mock(HistoryStore::class.java), AppPrefs(context),
            mock(MediaPreloadCache::class.java), { _, _ -> }, {}, {}, {}
        )
        try {
            adapter.replace(listOf(VideoItem("v1", "Fixture", "作者", emptyList(), 0)))
            val holder = adapter.onCreateViewHolder(FrameLayout(context), 0)
            adapter.onBindViewHolder(holder, 0)
            adapter.suspendPlayback()
            assertNull(playerOf(holder))
        } finally { adapter.releaseAll() }
    }
}
