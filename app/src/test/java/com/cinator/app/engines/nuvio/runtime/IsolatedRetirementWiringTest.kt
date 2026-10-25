package com.cinator.app.engines.nuvio.runtime

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** A wiring guard, not a device timing test. */
class IsolatedRetirementWiringTest {
    @Test fun responseDoesNotReplaceHardDeadlineWithAnEarlyKill() {
        val file = listOf(
            File("src/main/java/com/cinator/app/engines/nuvio/runtime/IsolatedQuickJsService.kt"),
            File("app/src/main/java/com/cinator/app/engines/nuvio/runtime/IsolatedQuickJsService.kt")
        ).first { it.exists() }
        val source = file.readText()
        val body = source.substringAfter("private fun scheduleRetirement() {").substringBefore("\n    }")
        assertFalse("A queued reply needs its existing hard deadline, not a 500 ms kill", body.contains("cancel(false)"))
        assertFalse(body.contains("500, TimeUnit.MILLISECONDS"))
        assertTrue(source.contains("Process.killProcess(Process.myPid())"))
    }
}
