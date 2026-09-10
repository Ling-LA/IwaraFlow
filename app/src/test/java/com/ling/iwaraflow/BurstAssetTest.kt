package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 收藏动画的素材来自素材站，原件在星身上斜压了一道水印——画成比星身暗一档的黄色，
 * 所以它在 GIF 调色板里是几个独立的颜色。去水印做的就是把这些调色板条目改回底色，
 * 一个像素都没重新编码。
 *
 * 这里守住这个结果：以后要是重新导入素材，忘了去水印，调色板里会再出现这些颜色。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BurstAssetTest {
    /** 星身底色 rgb(246,210,48)，水印把它压暗成了这几档。 */
    private val watermarkYellows = listOf(
        Triple(232, 199, 52), Triple(231, 198, 51), Triple(234, 201, 54),
        Triple(237, 205, 57), Triple(240, 207, 59)
    )

    /** 星星周围那圈小饰块底色 rgb(140,98,57)，水印压暗成这一档。 */
    private val watermarkBrown = Triple(135, 97, 60)

    private fun raw(resId: Int): ByteArray =
        RuntimeEnvironment.getApplication().resources.openRawResource(resId).use { it.readBytes() }

    private class Parsed(val colours: Set<Triple<Int, Int, Int>>, val frames: Int)

    /**
     * 走一遍 GIF 的块结构，收集所有调色板颜色和帧数。调色板是紧挨着的 RGB 三元组：
     * 全局的跟在逻辑屏幕描述符后面，每一帧还可以带一张自己的。
     */
    private fun parse(data: ByteArray): Parsed {
        fun u8(i: Int) = data[i].toInt() and 0xFF
        val colours = HashSet<Triple<Int, Int, Int>>()
        var frames = 0
        fun readTable(at: Int, entries: Int) {
            for (i in 0 until entries) {
                val p = at + i * 3
                colours += Triple(u8(p), u8(p + 1), u8(p + 2))
            }
        }
        var pos = 13
        val screenFlags = u8(10)
        if (screenFlags and 0x80 != 0) {
            val n = 2 shl (screenFlags and 7)
            readTable(pos, n)
            pos += n * 3
        }
        fun skipSubBlocks() {
            while (u8(pos) != 0) pos += 1 + u8(pos)
            pos += 1
        }
        while (pos < data.size) {
            val block = u8(pos)
            if (block == 0x3B) break
            when (block) {
                0x21 -> { pos += 2; skipSubBlocks() }          // 扩展块
                0x2C -> {                                       // 一帧
                    frames += 1
                    val imageFlags = u8(pos + 9)
                    pos += 10
                    if (imageFlags and 0x80 != 0) {
                        val n = 2 shl (imageFlags and 7)
                        readTable(pos, n)
                        pos += n * 3
                    }
                    pos += 1                                    // LZW 最小码长
                    skipSubBlocks()
                }
                else -> throw IllegalStateException("GIF 结构读不动了，位置 $pos 是 0x%02x".format(block))
            }
        }
        return Parsed(colours, frames)
    }

    @Test fun theFavouriteBurstShipsWithoutTheStockWatermark() {
        val colours = parse(raw(R.raw.favorite_burst)).colours
        assertTrue("调色板没读出来", colours.size > 16)
        (watermarkYellows + watermarkBrown).forEach { banned ->
            assertFalse("调色板里还有水印色 $banned，素材没去水印", banned in colours)
        }
        assertTrue("星身底色不该被一起改掉", Triple(246, 210, 48) in colours)
    }

    @Test fun bothBurstAssetsAreAnimatedGifs() {
        listOf(R.raw.like_burst to "点赞", R.raw.favorite_burst to "收藏").forEach { (res, label) ->
            val data = raw(res)
            assertEquals("$label 素材不是 GIF", "GIF89a", String(data.copyOfRange(0, 6)))
            assertTrue("$label 素材不是动图", parse(data).frames > 1)
        }
    }
}
