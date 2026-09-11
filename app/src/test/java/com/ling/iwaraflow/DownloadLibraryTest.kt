package com.ling.iwaraflow

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * “已下载”那一页：本地表记下过什么，系统下载器说现在还在不在。
 * 用户清掉下载列表或删掉文件以后，这一页不能还装作能播。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DownloadLibraryTest {
    private lateinit var history: HistoryStore

    @Before fun open() {
        history = HistoryStore(RuntimeEnvironment.getApplication())
    }

    @After fun close() {
        history.close()
    }

    private fun video(id: String, title: String = "片名 $id") =
        VideoItem(id, title, "作者$id", listOf("标签A", "标签B"), 7)

    @Test fun aDownloadIsRememberedWithItsQuality() {
        history.recordDownload(video("v1"), "Source 1080p", 42L)
        val records = history.downloadRecords()
        assertEquals(1, records.size)
        assertEquals("v1", records[0].item.id)
        assertEquals("片名 v1", records[0].item.title)
        assertEquals(listOf("标签A", "标签B"), records[0].item.tags)
        assertEquals("Source 1080p", records[0].quality)
        assertEquals(42L, records[0].downloadId)
    }

    @Test fun theSameVideoAtTwoQualitiesIsTwoEntries() {
        history.recordDownload(video("v1"), "540p", 1L)
        history.recordDownload(video("v1"), "1080p", 2L)
        assertEquals(2, history.downloadRecords().size)
    }

    @Test fun downloadingTheSameQualityAgainReplacesTheOldRow() {
        history.recordDownload(video("v1"), "1080p", 1L)
        history.recordDownload(video("v1"), "1080p", 9L)
        val records = history.downloadRecords()
        assertEquals(1, records.size)
        assertEquals("重新下载后要指向新的下载编号", 9L, records[0].downloadId)
    }

    @Test fun theNewestDownloadComesFirst() {
        history.recordDownload(video("old"), "1080p", 1L)
        Thread.sleep(5)
        history.recordDownload(video("new"), "1080p", 2L)
        assertEquals(listOf("new", "old"), history.downloadRecords().map { it.item.id })
    }

    @Test fun forgettingADownloadRemovesOnlyThatQuality() {
        history.recordDownload(video("v1"), "540p", 1L)
        history.recordDownload(video("v1"), "1080p", 2L)
        history.forgetDownload("v1", "540p")
        assertEquals(listOf("1080p"), history.downloadRecords().map { it.quality })
    }

    @Test fun aDownloadTheSystemNoLongerKnowsAboutIsNotOfferedAsPlayable() {
        history.recordDownload(video("v1"), "1080p", 12345L)
        val entries = DownloadLibrary.entries(RuntimeEnvironment.getApplication(), history)
        assertEquals(1, entries.size)
        assertEquals(DownloadLibrary.State.GONE, entries[0].state)
        assertNull("查不到的下载不该给出本地地址", entries[0].localUri)
        assertNotNull("这一行要说明它为什么播不了", entries[0].note())
    }

    @Test fun anEmptyLibraryDoesNotAskTheSystemAnything() {
        assertTrue(DownloadLibrary.entries(RuntimeEnvironment.getApplication(), history).isEmpty())
    }

    // ------------------------------------------------ 旧版本的下载补录

    @Test fun anOldDownloadFileNameGivesBackTheVideoIdAndQuality() {
        val parsed = DownloadLibrary.parseFileName("file:///storage/emulated/0/Download/IwaraFlow/%E6%A0%87%E9%A2%98_abc123_Source.mp4")!!
        assertEquals("标题", parsed.title)
        assertEquals("abc123", parsed.videoId)
        assertEquals("Source", parsed.quality)
    }

    @Test fun underscoresInsideTheTitleDoNotConfuseTheParser() {
        val parsed = DownloadLibrary.parseFileName("file:///x/My_Long_Title_v1_ab12cd_1080p.mp4")!!
        assertEquals("My_Long_Title_v1", parsed.title)
        assertEquals("ab12cd", parsed.videoId)
        assertEquals("1080p", parsed.quality)
    }

    @Test fun aFileNameThatIsNotOursIsIgnored() {
        assertNull(DownloadLibrary.parseFileName(""))
        assertNull(DownloadLibrary.parseFileName("file:///x/random.mp4"))
        assertNull(DownloadLibrary.parseFileName("file:///x/only_two.mp4"))
    }

    @Test fun fileNamesWithoutTheMp4SuffixStillParse() {
        val parsed = DownloadLibrary.parseFileName("content://downloads/all_downloads/%E7%89%87%E5%90%8D_id9_540p")!!
        assertEquals("id9", parsed.videoId)
        assertEquals("540p", parsed.quality)
    }

    @Test fun importingWithNothingInTheSystemDownloaderAddsNothing() {
        DownloadLibrary.importLegacyDownloads(RuntimeEnvironment.getApplication(), history)
        assertTrue(history.downloadRecords().isEmpty())
    }

    @Test fun sizesReadAsHumanNumbers() {
        assertEquals("", DownloadLibrary.formatSize(0))
        assertEquals("512 KB", DownloadLibrary.formatSize(512L * 1024))
        assertEquals("700 MB", DownloadLibrary.formatSize(700L * 1024 * 1024))
        assertEquals("2.5 GB", DownloadLibrary.formatSize((2.5 * (1L shl 30)).toLong()))
    }
}
