package com.cinator.app.ui

import org.junit.Assert.*
import org.junit.Test

class DownloadUiGateTest {
    @Test fun duplicateWorkIsBlockedAndTerminalWinsOverQueuedProgress() {
        val gate = DownloadUiGate()
        assertTrue(gate.begin("file"))
        assertFalse(gate.begin("file"))
        assertTrue(gate.acceptProgress("file", 1_000L))
        assertFalse(gate.acceptProgress("file", 1_050L))
        assertTrue(gate.acceptProgress("file", 1_300L))
        gate.finish("file")
        assertFalse(gate.acceptProgress("file", 2_000L))
        assertTrue(gate.begin("file"))
    }
}
