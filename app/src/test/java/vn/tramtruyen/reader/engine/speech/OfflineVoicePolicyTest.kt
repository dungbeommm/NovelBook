package vn.tramtruyen.reader.engine.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineVoicePolicyTest {
    @Test fun preservesEveryLegacyProvider() {
        listOf("gtts:vi", "gtts:vi-tld-com.vn", "edge:vi-VN-NamMinhNeural", "android:default", "capcut:x", "gemini:Kore", "zalo:1", "tiktok:BV074_streaming", "tiktok:BV075_streaming").forEach {
            assertEquals(it, OfflineVoicePolicy.migrate(it))
        }
    }
    @Test fun preservesOldOfflineIdsIncludingModelsWithoutLowVersion() {
        listOf("offline:banmai", "offline:ngochuyen", "offline:mytam2", "offline:mytam2794", "offline:vais1000medium").forEach {
            assertEquals(it, OfflineVoicePolicy.migrate(it))
        }
    }
    @Test fun keepsLowAndImportedVoices() {
        assertEquals("offline:low-banmai", OfflineVoicePolicy.migrate("offline:low-banmai"))
        assertEquals("offline:import-custom", OfflineVoicePolicy.migrate("offline:import-custom"))
    }
    @Test fun defaultsOnlyMissingOrMalformedKeys() {
        listOf(null, "", "unknown", "unknown:voice", "offline:").forEach {
            assertEquals(OfflineVoicePolicy.DEFAULT_VOICE, OfflineVoicePolicy.migrate(it))
        }
    }
}
