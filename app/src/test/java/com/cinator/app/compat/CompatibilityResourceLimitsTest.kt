package com.cinator.app.compat

import org.junit.Assert.*
import org.junit.Test

class CompatibilityResourceLimitsTest {
    private fun entry(id:String, reason:String="Review pending") = """{"id":"$id","ecosystem":"nuvio","name":"Test","status":"REFERENCE_ONLY","capability":{"catalog":false,"search":false,"details":false,"episodes":false,"directPlayback":false,"download":false,"externalPlayback":false},"reason":"$reason","evidenceUrl":null,"version":null}"""
    @Test fun rejectsOversizedInputBeforeParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            CompatibilityMatrixParser.parse(" ".repeat(1024*1024+1)+"{}")
        }
    }
    @Test fun rejectsExcessiveEntries() {
        val rows = (0..1024).joinToString(",") { entry("id$it") }
        assertThrows(IllegalArgumentException::class.java) { CompatibilityMatrixParser.parse("""{"schemaVersion":1,"entries":[$rows]}""") }
    }
    @Test fun rejectsOversizedStrings() {
        assertThrows(IllegalArgumentException::class.java) {
            CompatibilityMatrixParser.parse("""{"schemaVersion":1,"entries":[${entry("id", "x".repeat(8193))}]}""")
        }
    }
}
