package vn.tramtruyen.reader.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPrefetchPolicyTest {
    @Test
    fun capcutNowPrefetchesInParallelMatchingProviderLanes() {
        // Provider CapCut chạy 3 task song song (semaphore) -> cửa sổ khớp 3.
        assertEquals(3, PlaybackPrefetchPolicy.parallelPrefetchCount("capcut:BV421_vivn_streaming"))
        assertEquals(4, PlaybackPrefetchPolicy.parallelPrefetchCount("edge:vi-VN-HoaiMyNeural"))
    }

    @Test
    fun nextAudioWarmsOnlyWithAFullSafetyBuffer() {
        val voice = "capcut:BV421_vivn_streaming"
        assertFalse(PlaybackPrefetchPolicy.shouldWarmSerializedNext(voice, 3, 4, true, false))
        assertTrue(PlaybackPrefetchPolicy.shouldWarmSerializedNext(voice, 4, 4, true, false))
        assertFalse(PlaybackPrefetchPolicy.shouldWarmSerializedNext(voice, 4, 4, false, false))
        assertFalse(PlaybackPrefetchPolicy.shouldWarmSerializedNext(voice, 4, 4, true, true))
    }
}
