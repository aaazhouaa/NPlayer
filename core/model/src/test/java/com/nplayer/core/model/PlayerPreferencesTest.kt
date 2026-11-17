package com.nplayer.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPreferencesTest {
    @Test
    fun `existing preferences keep tunneled playback disabled`() {
        val preferences = Json.decodeFromString<PlayerPreferences>(
            string = """{"autoplay":false,"preferredAudioLanguage":"en"}""",
        )

        assertFalse(preferences.enableTunneledPlayback)
        assertFalse(preferences.autoplay)
        assertEquals(
            "en",
            preferences.preferredAudioLanguage,
        )
    }

    @Test
    fun `tunneled playback survives serialization`() {
        val preferences = PlayerPreferences(enableTunneledPlayback = true)
        val restored = Json.decodeFromString<PlayerPreferences>(
            Json.encodeToString(value = preferences),
        )

        assertTrue(restored.enableTunneledPlayback)
        assertEquals(
            preferences,
            restored,
        )
    }

    @Test
    fun `gesture defaults favor quicker seeking and 5x long press speed`() {
        val preferences = PlayerPreferences()

        assertEquals(
            2.0f,
            preferences.seekSensitivity,
        )
        assertTrue(preferences.useLongPressControls)
        assertEquals(
            5.0f,
            preferences.longPressControlsSpeed,
        )
    }
}
