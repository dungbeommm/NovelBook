package vn.tramtruyen.reader.presentation

import vn.tramtruyen.reader.presentation.motion.UiPerformancePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiPerformancePolicyTest {
    @Test
    fun pagerSwipeKeepsAnimationWithoutCompetingWithNestedLists() {
        assertEquals(1, UiPerformancePolicy.PAGER_PRECOMPOSE_PAGES)
        assertFalse(UiPerformancePolicy.pagerTouchSwipeEnabled(
            hasNestedHorizontalContent = true,
            overlayVisible = false,
        ))
        assertTrue(UiPerformancePolicy.pagerTouchSwipeEnabled(
            hasNestedHorizontalContent = false,
            overlayVisible = false,
        ))
        assertFalse(UiPerformancePolicy.pagerTouchSwipeEnabled(
            hasNestedHorizontalContent = false,
            overlayVisible = true,
        ))
    }

    @Test
    fun pagerPositionIsContinuousAndBounded() {
        assertEquals(0.45f, UiPerformancePolicy.continuousTabPosition(0, 0.45f, 3), 0.001f)
        assertEquals(0.70f, UiPerformancePolicy.continuousTabPosition(1, -0.30f, 3), 0.001f)
        assertEquals(2f, UiPerformancePolicy.continuousTabPosition(2, 0.8f, 3), 0.001f)
    }

    @Test
    fun listCoversDoNotAllocateCrossfadeLayers() {
        assertEquals(0, UiPerformancePolicy.coverCrossfadeMs(128))
        assertEquals(0, UiPerformancePolicy.coverCrossfadeMs(420))
        assertEquals(160, UiPerformancePolicy.coverCrossfadeMs(900))
    }

    @Test
    fun longKaraokeJumpSkipsIntermediateComposition() {
        assertEquals(76, UiPerformancePolicy.karaokePreJumpIndex(80, 2))
        assertNull(UiPerformancePolicy.karaokePreJumpIndex(10, 2))
        assertEquals(0, UiPerformancePolicy.karaokePreJumpIndex(2, 30))
    }
}
