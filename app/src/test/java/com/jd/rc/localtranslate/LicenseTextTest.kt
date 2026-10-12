package com.jd.rc.localtranslate

import com.jd.rc.localtranslate.ui.licenseTextBlocks
import org.junit.Assert.*
import org.junit.Test

class LicenseTextTest {
    @Test fun emptyAndShortNoticesRemainExact() {
        assertTrue(licenseTextBlocks("").isEmpty())
        assertEquals(listOf("license\nsource"), licenseTextBlocks("license\nsource"))
    }
    @Test fun megabyteNoticeIsVirtualizedWithoutLosingCharacters() {
        val text = "Copyright 2026 upstream\n".repeat(60000)
        val blocks = licenseTextBlocks(text)
        assertEquals(text, blocks.joinToString(""))
        assertTrue(blocks.size > 500)
        assertTrue(blocks.all { it.length <= 2048 })
        assertTrue(blocks.dropLast(1).all { it.endsWith('\n') })
    }
    @Test fun aSingleLongLineStillHasBoundedLayoutBlocks() {
        val text = "x".repeat(100000)
        val blocks = licenseTextBlocks(text)
        assertEquals(text, blocks.joinToString(""))
        assertTrue(blocks.all { it.length <= 2048 })
    }
    @Test fun surrogatePairsAreNotCutAcrossBlocks() {
        val text = "x".repeat(2047) + "\uD83D\uDD0E" + "y".repeat(4096)
        val blocks = licenseTextBlocks(text)
        assertEquals(text, blocks.joinToString(""))
        assertTrue(blocks.all { !it.last().isHighSurrogate() && !it.first().isLowSurrogate() })
    }
}
