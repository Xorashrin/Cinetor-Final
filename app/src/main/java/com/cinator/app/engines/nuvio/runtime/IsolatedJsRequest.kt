package com.cinator.app.engines.nuvio.runtime

import com.cinator.app.engines.nuvio.ReviewedEmbeddedScript

/** Pure admission boundary. There is deliberately no caller-supplied JavaScript field. */
internal class IsolatedJsRequest private constructor(
    val requestId: String,
    val pluginId: String,
    val document: ByteArray,
    val uuid: String,
    val height: Int,
) {
    sealed class Admission {
        class Accepted(val request: IsolatedJsRequest) : Admission()
        class Rejected(val error: String) : Admission()
    }

    companion object {
        const val MAX_DOCUMENT_BYTES = 65_536
        const val MAX_SOURCE_BYTES = 131_072
        const val MAX_RESULT_BYTES = 262_144
        val KEYS: Set<String> = setOf("requestId", "pluginId", "sourceSha256", "document", "uuid", "height")
        private val canonicalUuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        fun isCanonicalUuid(value: String): Boolean = value.length == 36 && canonicalUuid.matches(value)

        fun isAllowedEnvelope(
            sendingUid: Int, ownerUid: Int, what: Int, version: Int, arg2: Int, flags: Int,
            hasObject: Boolean, hasReply: Boolean,
        ): Boolean = sendingUid == ownerUid && version == 1 && arg2 == 0 && flags == 0 && !hasObject &&
            ((what == 1 && hasReply) || (what == 2 && !hasReply))

        fun validate(fields: Map<String, Any?>): Admission {
            if (fields.keys != KEYS) return Admission.Rejected("INVALID_REQUEST")
            val requestId = fields["requestId"] as? String ?: return Admission.Rejected("INVALID_REQUEST")
            val pluginId = fields["pluginId"] as? String ?: return Admission.Rejected("INVALID_REQUEST")
            val digest = fields["sourceSha256"] as? String ?: return Admission.Rejected("INVALID_REQUEST")
            val document = fields["document"] as? ByteArray ?: return Admission.Rejected("INVALID_REQUEST")
            val uuid = fields["uuid"] as? String ?: return Admission.Rejected("INVALID_REQUEST")
            val height = fields["height"] as? Int ?: return Admission.Rejected("INVALID_REQUEST")
            if (!isCanonicalUuid(requestId) || !isCanonicalUuid(uuid) || height !in 144..2160) {
                return Admission.Rejected("INVALID_REQUEST")
            }
            if (document.size > MAX_DOCUMENT_BYTES) return Admission.Rejected("DOCUMENT_TOO_LARGE")
            if (pluginId != ReviewedEmbeddedScript.BLENDER_RESOLVER_ID ||
                digest != ReviewedEmbeddedScript.BLENDER_RESOLVER_SHA256) {
                return Admission.Rejected("UNKNOWN_SOURCE")
            }
            return Admission.Accepted(IsolatedJsRequest(requestId, pluginId, document.copyOf(), uuid, height))
        }
    }
}
