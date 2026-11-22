package vn.tramtruyen.reader.presentation

import vn.tramtruyen.reader.model.ColorMode
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStoreTest {
    @Test
    fun legacyDefaultMigratesToSystemWallpaper() {
        assertEquals(
            ColorMode.SYSTEM_WALLPAPER,
            resolveColorMode(storedName = "DEFAULT", legacyMonet = false),
        )
    }

    @Test
    fun legacyMonetPreferenceKeepsCustomSeed() {
        assertEquals(
            ColorMode.CUSTOM_SEED,
            resolveColorMode(storedName = null, legacyMonet = true),
        )
    }

    @Test
    fun currentColorModesRoundTrip() {
        ColorMode.entries.forEach { mode ->
            assertEquals(mode, resolveColorMode(mode.name, legacyMonet = false))
        }
    }
}
