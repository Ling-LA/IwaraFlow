package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 诊断信息太长，复制成文本发到 QQ 会被截断，所以改成写成 txt 文件转发。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DiagnosticsFileTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun theReportIsWrittenAsATextFileInTheSharedCacheFolder() {
        val file = NavigationDiagnostics.writeReportFile(context, "第一行\n第二行")
        assertTrue(file.exists())
        assertEquals("第一行\n第二行", file.readText())
        assertEquals("diagnostics", file.parentFile!!.name)
        assertEquals(File(context.cacheDir, "diagnostics"), file.parentFile)
        assertTrue("文件名要带扩展名，否则有的应用不认", file.name.endsWith(".txt"))
        assertTrue("文件名里带版本号和“诊断”", file.name.contains("诊断"))
    }

    @Test fun onlyTheNewestFewReportsAreKept() {
        val written = (1..8).map { NavigationDiagnostics.writeReportFile(context, "报告 $it") }
        val remaining = File(context.cacheDir, "diagnostics").listFiles()!!
        assertTrue("最多留 5 份，实际 ${remaining.size}", remaining.size <= 5)
        assertTrue("刚写的那份必须还在", written.last().exists())
    }

    @Test fun twoReportsInTheSameSecondDoNotOverwriteEachOther() {
        val a = NavigationDiagnostics.writeReportFile(context, "a")
        val b = NavigationDiagnostics.writeReportFile(context, "b")
        assertNotEquals(a.name, b.name)
    }
}
