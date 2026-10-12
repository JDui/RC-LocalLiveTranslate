package com.jd.rc.localtranslate

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import com.jd.rc.localtranslate.ui.*
import org.junit.Assert.*
import org.junit.Test

class FoldLayoutTest {
    private fun plan(folds: List<WindowFold>, width: Int = 900, height: Int = 900, origin: IntOffset = IntOffset.Zero) =
        calculatePanes(width, height, origin, folds, 8, 280, 240)
    @Test fun normalWindowHasSinglePane() {
        val p = plan(emptyList()); assertFalse(p.split); assertEquals(IntRect(0, 0, 900, 900), p.primary)
    }
    @Test fun flatUnoccludedCreaseIsNotAnObstacle() {
        assertFalse(plan(listOf(WindowFold(IntRect(450, 0, 450, 900), FoldAxis.VERTICAL, false, false))).split)
    }
    @Test fun bookFoldKeepsSafetyMargins() {
        val p = plan(listOf(WindowFold(IntRect(450, 0, 450, 900), FoldAxis.VERTICAL, true, false)))
        assertTrue(p.split); assertEquals(442, p.primary.right); assertEquals(458, p.controls.left)
    }
    @Test fun tabletopFoldUsesUpperAndLowerPanes() {
        val p = plan(listOf(WindowFold(IntRect(0, 450, 900, 450), FoldAxis.HORIZONTAL, true, false)))
        assertTrue(p.split); assertEquals(442, p.primary.bottom); assertEquals(458, p.controls.top)
    }
    @Test fun fullOcclusionIsAvoidedEvenWithoutSeparation() {
        val p = plan(listOf(WindowFold(IntRect(440, 0, 460, 900), FoldAxis.VERTICAL, false, true)))
        assertEquals(432, p.primary.right); assertEquals(468, p.controls.left)
    }
    @Test fun systemInsetsTranslateWindowCoordinates() {
        val p = plan(listOf(WindowFold(IntRect(0, 480, 900, 500), FoldAxis.HORIZONTAL, true, true)), height = 850, origin = IntOffset(0, 30))
        assertEquals(442, p.primary.bottom); assertEquals(478, p.controls.top)
    }
    @Test fun narrowSideFallsBackToOneSafePane() {
        val p = plan(listOf(WindowFold(IntRect(100, 0, 120, 900), FoldAxis.VERTICAL, true, true)))
        assertFalse(p.split); assertEquals(IntRect(128, 0, 900, 900), p.primary)
    }
    @Test fun foldsOutsideWindowAndAtBoundaryDoNotSplit() {
        val folds = listOf(WindowFold(IntRect(950, 0, 970, 900), FoldAxis.VERTICAL, true, true),
            WindowFold(IntRect(0, 0, 0, 900), FoldAxis.VERTICAL, true, false))
        assertEquals(IntRect(0, 0, 900, 900), plan(folds).primary)
    }
    @Test fun multipleHingesNeverCreateSpanningPane() {
        val p = plan(listOf(WindowFold(IntRect(450, 0, 470, 900), FoldAxis.VERTICAL, true, true),
            WindowFold(IntRect(0, 450, 900, 470), FoldAxis.HORIZONTAL, true, true)))
        assertTrue(p.split)
        listOf(p.primary, p.controls).forEach { assertTrue(it.right <= 442 || it.left >= 478); assertTrue(it.bottom <= 442 || it.top >= 478) }
    }
    @Test fun expandedFlatContentUsesBothPanesAfterNavigationInsets() {
        val full = PanePlan(IntRect(116, 0, 900, 900), IntRect(116, 0, 900, 900), false, rail = true)
        val p = contentPanes(full, 900, 900, 360, 240, 24)
        assertTrue(p.split); assertEquals(116, p.primary.left); assertEquals(900, p.controls.right)
        assertEquals(24, p.controls.left - p.primary.right); assertFalse(p.avoidsFold)
    }
    @Test fun compactContentDoesNotForceTwoNarrowColumns() {
        assertFalse(contentPanes(null, 412, 915, 360, 240, 24).split)
        assertFalse(contentPanes(null, 900, 200, 360, 240, 24).split)
    }
    @Test fun physicalHingeContentIsNotSplitAgain() {
        val existing = plan(listOf(WindowFold(IntRect(440, 0, 460, 900), FoldAxis.VERTICAL, true, true)))
        assertEquals(existing, contentPanes(existing, 900, 900, 360, 240, 24))
    }
    @Test fun increasedFontWidthFallsBackWithoutLosingSafeRegion() {
        val full = PanePlan(IntRect(116, 0, 900, 900), IntRect(116, 0, 900, 900), false, rail = true)
        assertEquals(full, contentPanes(full, 900, 900, 400, 360, 24))
    }
}
