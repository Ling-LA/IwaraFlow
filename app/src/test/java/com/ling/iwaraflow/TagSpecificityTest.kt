package com.ling.iwaraflow

import org.junit.Assert.*
import org.junit.Test

class TagSpecificityTest {
    @Test fun broadTagsDoNotHideSelectiveInterests() {
        val evidence = TagSpecificity()
        repeat(100) { index ->
            evidence.observe("v$index", listOf("common") + if (index < 20) listOf("topic") else emptyList(), 1.0)
        }
        val weights = evidence.multipliers()
        assertEquals(0.35, weights.getValue("common"), 1e-9)
        assertTrue(weights.getValue("topic") > weights.getValue("common") * 2)
        assertTrue(weights.values.all { it in 0.35..1.0 })
    }

    @Test fun repeatActionsDoNotFabricateExposureAndAliasesShareOneWeight() {
        val evidence = TagSpecificity()
        evidence.observe("v1", listOf("Music", "music"), 1.0)
        val once = evidence.multipliers()
        repeat(50) { evidence.observe("v1", listOf("music"), 1.0) }
        assertEquals(once, evidence.multipliers())
        assertTrue(once.getValue("music") > 0.95)
        evidence.observe("search:query", listOf("music"), 1.0)
        assertEquals(once, evidence.multipliers())
    }

    @Test fun staleOrMissingExposureLeavesLongTermTasteIntact() {
        assertTrue(TagSpecificity().multipliers().isEmpty())
        val evidence = TagSpecificity()
        repeat(40) { evidence.observe("old$it", listOf("music"), 0.01) }
        assertTrue(evidence.multipliers().getValue("music") > 0.98)
    }
}
