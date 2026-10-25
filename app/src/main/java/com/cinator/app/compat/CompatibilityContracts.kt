package com.cinator.app.compat

enum class CompatibilityStatus {
    SUPPORTED_E2E,
    SUPPORTED_PROTOCOL,
    EXTERNAL_AUTHORIZED,
    REQUIRES_USER_SERVER,
    BLOCKED_SECURITY,
    BLOCKED_RIGHTS_OR_API,
    REFERENCE_ONLY
}

data class EngineCapability(
    val catalog: Boolean,
    val search: Boolean,
    val details: Boolean,
    val episodes: Boolean,
    val directPlayback: Boolean,
    val download: Boolean,
    val externalPlayback: Boolean
)

data class CompatibilityEntry(
    val id: String,
    val ecosystem: String,
    val name: String,
    val status: CompatibilityStatus,
    val capability: EngineCapability,
    val reason: String,
    val evidenceUrl: String?,
    val version: String?
)

data class CompatibilityMatrix(
    val schemaVersion: Int = 1,
    val entries: List<CompatibilityEntry>
)
