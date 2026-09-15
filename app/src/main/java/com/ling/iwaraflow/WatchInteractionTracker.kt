package com.ling.iwaraflow

/**
 * 一张卡片「看得怎么样」的全部记账：真播了多久、有没有播完、有没有往回拖、报没报错，
 * 以及离开这条视频时把这些折算成口味信号和曝光结果。
 *
 * 从 [VideoAdapter] 里拆出来的一块。播放器、手势、界面和“这条视频看得怎么样”
 * 本来就是两件事，混在一个一千多行的 ViewHolder 里谁也说不清。这里**一个 View 都不碰**，
 * 只认几个事件（起播 / 暂停 / READY / 播完 / 出错 / 往回拖），所以可以脱离播放器单独测。
 *
 * 用法：卡片绑定新视频时 [reset]，播放器状态变化时调对应的 note*，
 * 离开这条视频时调一次 [report]（重复调用只算第一次）。
 */
class WatchInteractionTracker(
    private val history: HistoryStore,
    /** 连着快速划走同类内容时回调，由调用方维护“上一条被划走的是什么”。 */
    private val onSkipStreak: (VideoItem) -> Unit,
    /** 这一条看得久：连续划走的链子断了。 */
    private val onWatched: () -> Unit
) {
    /** 这次绑定里真正播出画面的累计毫秒数（按墙钟算，暂停不计）。 */
    var playedMs = 0L
        private set
    private var playingSince = 0L
    private var readyOnce = false
    private var endedOnce = false
    private var hadError = false
    private var reported = false
    /** 这一条里主动往回拖过 / 点过后退：看了还想再看一遍，是正面信号。 */
    private var rewound = false

    fun reset() {
        playedMs = 0L; playingSince = 0L
        readyOnce = false; endedOnce = false; hadError = false; reported = false; rewound = false
    }

    fun notePlaying(isPlaying: Boolean) {
        val now = System.currentTimeMillis()
        if (isPlaying) playingSince = now
        else if (playingSince > 0L) { playedMs += now - playingSince; playingSince = 0L }
    }

    fun noteReady() { readyOnce = true }
    fun noteEnded() { endedOnce = true }
    fun noteError() { hadError = true }
    fun noteRewound() { rewound = true }

    /**
     * 离开这条视频时把观看行为记进画像：看得久（或看过一半）算喜欢，起播后很快划走算不喜欢。
     * 只有播放器真的 READY 过、没有报错才算数——加载失败后划走不是态度，是网络问题。
     *
     * [swipedAway] 为 false 时（切后台、开别的页面）只记正面信号，不记划走。
     * [impressionSession] 非空时顺带补上这条曝光的结果，见 [HistoryStore.noteImpressionOutcome]。
     */
    fun report(item: VideoItem, durationMs: Long, playing: Boolean, swipedAway: Boolean, impressionSession: String) {
        if (reported) return
        reported = true
        if (playing) notePlaying(false)
        if (hadError || !readyOnce) return
        val duration = durationMs.takeIf { it > 0L } ?: 0L
        // 兴趣是连续的：43 秒和 45 秒不该一个没信号、一个 +0.8。
        val interest = watchInterest(
            playedMs = playedMs,
            durationMs = duration,
            completed = endedOnce,
            repeated = item.resumePositionMs > 0L,
            rewound = rewound,
            reacted = item.liked || item.localFavorite
        )
        val skipped = interest <= -WATCH_SIGNAL_FLOOR && swipedAway
        when {
            interest >= WATCH_SIGNAL_FLOOR -> {
                history.recordInteractionAsync(item, "watch", interest)
                onWatched()
            }
            // 划走才算负反馈：切后台、开别的页面不是态度。
            skipped -> {
                history.recordInteractionAsync(item, HistoryStore.ACTION_SKIP, interest)
                onSkipStreak(item)
            }
        }
        // 曝光结果：记的是**真实播放毫秒数**，不是 history.last_position
        // （拖到 8 分钟看十秒，那个字段会显示看了八分钟）。
        if (impressionSession.isNotBlank()) {
            val played = playedMs
            history.post {
                history.noteImpressionOutcome(impressionSession, item.id, played, duration, endedOnce, skipped)
            }
        }
    }

    companion object {
        /** 起播后不到这么久就划走算“快速划走”（负反馈）。 */
        const val QUICK_SKIP_MS = 4_000L
        /** 绝对值不到这么多就当没有态度，不记进画像。 */
        const val WATCH_SIGNAL_FLOOR = 0.08
        /** 看多久算“不好不坏”的分界（秒）：比这短是负的，比这长是正的。 */
        const val NEUTRAL_WATCH_SECONDS = 10.0
        /** 时长项的尺度：ln(1 + 秒数 / 12) × 0.38。 */
        const val WATCH_TIME_SCALE = 12.0
        const val WATCH_TIME_WEIGHT = 0.38
        /** 看了多大比例：长视频看三成也是认真在看。 */
        const val WATCH_RATIO_WEIGHT = 0.9
        const val COMPLETED_BONUS = 0.35
        const val REPEAT_BONUS = 0.3
        const val REWIND_BONUS = 0.15
        const val REACTED_BONUS = 0.25
        /** 起播就划走的最大负分（刚起播就走是这么多，到 [QUICK_SKIP_MS] 线性归零）。 */
        const val QUICK_SKIP_PENALTY = 0.45
        /** 连续快速划走同类内容时，对共同的作者 / 标签额外的负反馈。 */
        const val SKIP_STREAK_WEIGHT = -0.5

        /**
         * 一次观看值多少兴趣。**连续值**，不再是“45 秒 +0.8 / 4 秒 −0.5”这种固定档位：
         * 43 秒和 45 秒不该一个没信号一个满分。
         *
         * 由这几项合成：真播了多久（对数增长，边际递减）、看了多大比例、是否播完、
         * 是否是重看、有没有往回拖着再看、看的过程中有没有点赞收藏；再减掉一个
         * “不好不坏”的基线（[NEUTRAL_WATCH_SECONDS] 秒）。起播没几秒就走额外扣分，越快越扣。
         */
        fun watchInterest(
            playedMs: Long,
            durationMs: Long,
            completed: Boolean = false,
            repeated: Boolean = false,
            rewound: Boolean = false,
            reacted: Boolean = false
        ): Double {
            val seconds = (playedMs.coerceAtLeast(0L)) / 1000.0
            val ratio = if (durationMs > 0L) (playedMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0
            val baseline = kotlin.math.ln(1.0 + NEUTRAL_WATCH_SECONDS / WATCH_TIME_SCALE) * WATCH_TIME_WEIGHT
            var score = kotlin.math.ln(1.0 + seconds / WATCH_TIME_SCALE) * WATCH_TIME_WEIGHT +
                ratio * WATCH_RATIO_WEIGHT - baseline
            if (completed) score += COMPLETED_BONUS
            if (repeated) score += REPEAT_BONUS
            if (rewound) score += REWIND_BONUS
            if (reacted) score += REACTED_BONUS
            // 起播就划走：越快越负，到 QUICK_SKIP_MS 这条线归零。看过一部分的不算。
            if (!completed && ratio < 0.2 && playedMs < QUICK_SKIP_MS) {
                score -= QUICK_SKIP_PENALTY * (1.0 - playedMs.toDouble() / QUICK_SKIP_MS)
            }
            return score
        }
    }
}
