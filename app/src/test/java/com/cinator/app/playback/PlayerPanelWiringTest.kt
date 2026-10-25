package com.cinator.app.playback

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Wiring guards, not an Android/device test. Device coverage is still required in CI. */
class PlayerPanelWiringTest {
    private fun panel(): String {
        val candidates = listOf(
            File("app/src/main/java/com/cinator/app/playback/CinatorPlayerPanel.kt"),
            File("src/main/java/com/cinator/app/playback/CinatorPlayerPanel.kt")
        )
        return candidates.first { it.isFile }.readText()
    }

    @Test fun `fullscreen reuses the one owned player with lifecycle cleanup`() {
        val source = panel()
        assertEquals("Only one player construction site", 1, Regex("ExoPlayer\\.Builder\\(").findAll(source).count())
        assertTrue("Internal fullscreen dialog required", source.contains("Dialog("))
        assertTrue(source.contains("PlayerView.switchTargetView("))
        assertTrue(source.contains("Lifecycle.Event.ON_STOP"))
        assertTrue(source.contains("Lifecycle.Event.ON_DESTROY"))
        assertTrue(source.contains("player.release()"))
        assertTrue(source.contains("previousOrientation"))
    }

    @Test fun `tracks come from Media3 groups not catalog subtitle metadata`() {
        val source = panel()
        assertTrue(source.contains("currentTracks"))
        assertTrue(source.contains("TrackSelectionOverride("))
        assertTrue(source.contains("setOverrideForType("))
        assertTrue(source.contains("setTrackTypeDisabled("))
        assertTrue(source.contains("clearOverridesOfType("))
        assertFalse(source.contains("stream.subtitleLang"))
        assertTrue(source.contains("Text(\"Off\")"))
    }

    @Test fun `slider only commits at drag completion and ticker never persists`() {
        val source = panel()
        val change = Regex("onValueChange = \\{([\\s\\S]*?)onValueChangeFinished").find(source)
        assertNotNull("Drag-finish callback required", change)
        assertFalse("Dragging must not issue seeks", change!!.groupValues[1].contains("seek"))
        val ticker = Regex("while \\(isActive\\) \\{([\\s\\S]*?)delay\\(500L\\)").find(source)
        assertNotNull(ticker)
        assertFalse("UI ticks must not write preferences", ticker!!.groupValues[1].contains("save"))
    }
}
