package com.ling.iwaraflow

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.SystemClock
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional physical-device probe; the QA package never reads the signed app's private storage. */
class PhoneUiProbeTest {
    @Test fun captureVisibleApplicationHierarchy() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("phoneProbe") == "true")
        capture()
    }

    fun capture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun appRoots() = (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow))
            .filter { it.packageName?.toString() in setOf("com.ling.iwaraflow", "com.ling.iwaraflow.qa") }
            .distinctBy { it.windowId }
        var roots = appRoots()
        val deadline = SystemClock.uptimeMillis() + 3000
        while (roots.isEmpty() && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100); roots = appRoots()
        }
        assertTrue("No IwaraFlow window is currently visible", roots.isNotEmpty())
        val output = File(instrumentation.targetContext.filesDir, "phone-ui.xml")
        output.outputStream().use { stream ->
            val xml = Xml.newSerializer().apply { setOutput(stream, "UTF-8"); startDocument("UTF-8", true); startTag(null, "hierarchy") }
            fun write(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 80) return
                val bounds = Rect().also(node::getBoundsInScreen)
                xml.startTag(null, "node")
                xml.attribute(null, "text", if (node.isPassword) "" else node.text?.toString().orEmpty())
                xml.attribute(null, "content-desc", if (node.isPassword) "" else node.contentDescription?.toString().orEmpty())
                xml.attribute(null, "resource-id", node.viewIdResourceName.orEmpty())
                xml.attribute(null, "bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
                xml.attribute(null, "password", node.isPassword.toString())
                xml.attribute(null, "clickable", node.isClickable.toString())
                xml.attribute(null, "checked", node.isChecked.toString())
                repeat(node.childCount) { index -> node.getChild(index)?.let { child -> write(child, depth + 1) } }
                xml.endTag(null, "node")
            }
            roots.forEach { write(it, 0) }
            xml.endTag(null, "hierarchy"); xml.endDocument()
        }
    }
}
