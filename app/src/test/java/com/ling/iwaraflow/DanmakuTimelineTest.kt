package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test

class DanmakuTimelineTest {
    private fun comment(id: String, text: String = id) = DanmakuTimeline.Comment(id, text)

    @Test fun aSingleCommentNeverRepeatsAcrossLanesOrAfterCompletion() {
        val timeline = DanmakuTimeline()
        timeline.setComments(listOf(comment("only")))
        assertEquals(listOf("only"), timeline.frames(0, 6, 4000).map { it.comment.key })
        assertEquals(1, timeline.frames(2000, 6, 4000).size)
        for (time in 4000L..120_000L step 1000) assertTrue(timeline.frames(time, 6, 4000).isEmpty())
        assertFalse(timeline.hasRemaining())
    }

    @Test fun eachCommentGetsOnePassEvenWhenDensityAndSpeedChange() {
        val timeline = DanmakuTimeline()
        timeline.setComments((0..9).map { comment(it.toString()) })
        val starts = mutableListOf<String>()
        for (time in 0L..100_000L step 100) {
            val frames = timeline.frames(time, if (time < 6000) 3 else 2, if (time < 8000) 4000 else 6000)
            starts += frames.filter { it.fraction == 0f }.map { it.comment.key }
        }
        assertEquals(10, starts.size)
        assertEquals((0..9).map { it.toString() }.toSet(), starts.toSet())
        assertFalse(timeline.hasRemaining())
    }

    @Test fun refreshAndNewCommentDoNotReplayOldComments() {
        val timeline = DanmakuTimeline()
        timeline.setComments(listOf(comment("first")))
        timeline.frames(0, 1, 4000); timeline.frames(4000, 1, 4000)
        timeline.setComments(listOf(comment("first", "updated text"), comment("new")))
        assertEquals(listOf("new"), timeline.frames(5000, 1, 4000).map { it.comment.key })
        assertTrue(timeline.frames(9000, 1, 4000).isEmpty())
        timeline.setComments(listOf(comment("new"), comment("first")))
        assertTrue(timeline.frames(10_000, 1, 4000).isEmpty())
    }

    @Test fun seeksPreserveConsumedCommentsWhileExplicitRestartAllowsAnotherPass() {
        val timeline = DanmakuTimeline()
        timeline.setComments(listOf(comment("one"), comment("two")))
        timeline.frames(0, 1, 4000)
        timeline.rebase()
        assertEquals("two", timeline.frames(0, 1, 4000).single().comment.key)
        timeline.rebase()
        assertTrue(timeline.frames(0, 1, 4000).isEmpty())
        timeline.reset()
        assertEquals("one", timeline.frames(0, 1, 4000).single().comment.key)
        timeline.clear()
        assertFalse(timeline.hasRemaining())
    }

    @Test fun identityIsTheCommentIdAndDuplicateApiRowsAreIgnored() {
        val timeline = DanmakuTimeline()
        timeline.setComments(listOf(comment("a", "same"), comment("a", "same"), comment("b", "same")))
        assertEquals("a", timeline.frames(0, 1, 4000).single().comment.key)
        assertEquals("b", timeline.frames(4000, 1, 4000).single().comment.key)
        assertTrue(timeline.frames(8000, 1, 4000).isEmpty())
    }
}
