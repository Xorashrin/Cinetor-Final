package com.cinator.app.compat

import java.net.URI
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object CompatibilityMatrixParser {
    private val matrixFields = setOf("schemaVersion", "entries")
    private val entryFields = setOf(
        "id", "ecosystem", "name", "status", "capability", "reason", "evidenceUrl", "version"
    )
    private val capabilityFields = setOf(
        "catalog", "search", "details", "episodes", "directPlayback", "download", "externalPlayback"
    )
    private val zeroCapabilityStatuses = setOf(
        CompatibilityStatus.BLOCKED_SECURITY,
        CompatibilityStatus.BLOCKED_RIGHTS_OR_API,
        CompatibilityStatus.REFERENCE_ONLY
    )

    fun parse(raw: String): CompatibilityMatrix {
        require(raw.length <= 1024 * 1024 && raw.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "Compatibility matrix exceeds byte budget" }
        try {
            StrictJson(raw).validate()
            val root = JSONObject(raw)
            root.requireOnly(matrixFields, "matrix")
            val schemaVersion = root.requiredInt("schemaVersion")
            require(schemaVersion == 1) { "Unsupported compatibility schema version: $schemaVersion" }
            val array = root.requiredArray("entries")
            require(array.length() <= 1024) { "Compatibility entry limit exceeded" }
            val entries = ArrayList<CompatibilityEntry>(array.length())
            val ids = HashSet<String>()
            for (index in 0 until array.length()) {
                val entry = parseEntry(array.requiredObject(index), index)
                require(ids.add(entry.id)) { "Duplicate compatibility entry id: ${entry.id}" }
                entries += entry
            }
            return CompatibilityMatrix(schemaVersion, entries)
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: JSONException) {
            throw IllegalArgumentException("Invalid compatibility matrix JSON", error)
        } catch (error: RuntimeException) {
            throw IllegalArgumentException("Invalid compatibility matrix", error)
        }
    }

    private fun parseEntry(json: JSONObject, index: Int): CompatibilityEntry {
        json.requireOnly(entryFields, "entry[$index]")
        val id = json.requiredText("id")
        val ecosystem = json.requiredText("ecosystem")
        val name = json.requiredText("name")
        val statusText = json.requiredText("status")
        val status = try {
            CompatibilityStatus.valueOf(statusText)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Unknown compatibility status: $statusText")
        }
        val capability = parseCapability(json.requiredObject("capability"), index)
        val reason = json.requiredText("reason")
        val evidenceUrl = json.optionalText("evidenceUrl")
        val version = json.optionalText("version")

        evidenceUrl?.let(::requireHttpsEvidenceUrl)
        if (status in zeroCapabilityStatuses) {
            require(!capability.any()) { "$status entries cannot declare capabilities" }
        }
        require(!capability.download || capability.directPlayback) {
            "Download capability requires directPlayback"
        }
        if (status == CompatibilityStatus.EXTERNAL_AUTHORIZED) {
            require(!capability.directPlayback && !capability.download) {
                "EXTERNAL_AUTHORIZED cannot declare native playback or download"
            }
        }
        if (status == CompatibilityStatus.SUPPORTED_E2E) {
            require(evidenceUrl != null) { "SUPPORTED_E2E requires evidenceUrl" }
            require(!version.isNullOrBlank()) { "SUPPORTED_E2E requires version" }
            require(capability.catalog) { "SUPPORTED_E2E requires catalog capability" }
            require(capability.search) { "SUPPORTED_E2E requires search capability" }
            require(capability.details) { "SUPPORTED_E2E requires details capability" }
            require(capability.directPlayback) { "SUPPORTED_E2E requires directPlayback capability" }
        }

        return CompatibilityEntry(id, ecosystem, name, status, capability, reason, evidenceUrl, version)
    }

    private fun parseCapability(json: JSONObject, index: Int): EngineCapability {
        json.requireOnly(capabilityFields, "entry[$index].capability")
        return EngineCapability(
            catalog = json.requiredBoolean("catalog"),
            search = json.requiredBoolean("search"),
            details = json.requiredBoolean("details"),
            episodes = json.requiredBoolean("episodes"),
            directPlayback = json.requiredBoolean("directPlayback"),
            download = json.requiredBoolean("download"),
            externalPlayback = json.requiredBoolean("externalPlayback")
        )
    }

    private fun requireHttpsEvidenceUrl(value: String) {
        val uri = try {
            URI(value)
        } catch (error: Exception) {
            throw IllegalArgumentException("Invalid evidence URL", error)
        }
        require(uri.isAbsolute && uri.scheme.equals("https", ignoreCase = true)) {
            "Evidence URL must use HTTPS"
        }
        require(!uri.host.isNullOrBlank()) { "Evidence URL must have a host" }
        require(uri.userInfo == null) { "Evidence URL must not contain user info" }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid evidence URL port" }
        require(!Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f|c2%[89][0-9a-f])", RegexOption.IGNORE_CASE).containsMatchIn(value)) {
            "Evidence URL contains encoded control characters"
        }
    }

    private fun EngineCapability.any() =
        catalog || search || details || episodes || directPlayback || download || externalPlayback

    private fun JSONObject.requireOnly(expected: Set<String>, location: String) {
        val actual = keys().asSequence().toSet()
        val unknown = actual - expected
        require(unknown.isEmpty()) { "Unknown fields in $location: ${unknown.joinToString()}" }
        val missing = expected - actual
        require(missing.isEmpty()) { "Missing fields in $location: ${missing.joinToString()}" }
    }

    private fun JSONObject.requiredText(name: String): String {
        require(has(name) && !isNull(name)) { "Missing string field: $name" }
        require(opt(name) is String) { "Field $name must be a string" }
        return getString(name).also {
            require(it.isNotBlank() && it.length <= 8192) { "Field $name must be nonblank and bounded" }
            require(!it.hasControlCharacters()) { "Field $name contains control characters" }
        }
    }

    private fun JSONObject.optionalText(name: String): String? {
        require(has(name)) { "Missing nullable string field: $name" }
        if (isNull(name)) return null
        require(opt(name) is String) { "Field $name must be a string or null" }
        return getString(name).also {
            require(it.isNotBlank() && it.length <= 8192) { "Field $name must be nonblank and bounded" }
            require(!it.hasControlCharacters()) { "Field $name contains control characters" }
        }
    }

    private fun JSONObject.requiredInt(name: String): Int {
        require(has(name) && opt(name) is Int) { "Field $name must be an integer" }
        return getInt(name)
    }

    private fun JSONObject.requiredBoolean(name: String): Boolean {
        require(has(name) && opt(name) is Boolean) { "Field $name must be a boolean" }
        return getBoolean(name)
    }

    private fun JSONObject.requiredArray(name: String): JSONArray {
        require(has(name) && opt(name) is JSONArray) { "Field $name must be an array" }
        return getJSONArray(name)
    }

    private fun JSONObject.requiredObject(name: String): JSONObject {
        require(has(name) && opt(name) is JSONObject) { "Field $name must be an object" }
        return getJSONObject(name)
    }

    private fun JSONArray.requiredObject(index: Int): JSONObject {
        require(opt(index) is JSONObject) { "Entry at index $index must be an object" }
        return getJSONObject(index)
    }

    private fun String.hasControlCharacters() = any { Character.isISOControl(it) }

    // Android's JSONTokener accepts non-JSON syntax and duplicate keys; reject it
    // before using JSONObject for typed extraction on either Android or the JVM.
    private class StrictJson(private val raw: String) {
        private var position = 0

        fun validate() {
            whitespace()
            value(0)
            whitespace()
            require(position == raw.length) { "Trailing compatibility JSON data" }
        }

        private fun value(depth: Int) {
            require(depth <= 64) { "Compatibility JSON nesting is too deep" }
            whitespace()
            when (peek()) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> throw IllegalArgumentException("Invalid JSON value at $position")
            }
            whitespace()
        }

        private fun objectValue(depth: Int) {
            expect('{')
            whitespace()
            if (take('}')) return
            val keys = HashSet<String>()
            do {
                whitespace()
                require(peek() == '"') { "JSON object keys must be quoted" }
                val start = position
                string()
                val key = JSONObject("{\"key\":${raw.substring(start, position)}}").getString("key")
                require(keys.add(key)) { "Duplicate JSON object key: $key" }
                require(!key.hasControlCharacters()) { "Control characters in JSON object key" }
                whitespace()
                expect(':')
                value(depth)
            } while (take(','))
            expect('}')
        }

        private fun arrayValue(depth: Int) {
            expect('[')
            whitespace()
            if (take(']')) return
            do {
                value(depth)
            } while (take(','))
            expect(']')
        }

        private fun string() {
            expect('"')
            while (position < raw.length) {
                val char = raw[position++]
                if (char == '"') return
                require(char.code >= 0x20) { "Unescaped control character in JSON string" }
                if (char == '\\') {
                    require(position < raw.length) { "Unterminated JSON escape" }
                    when (raw[position++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> repeat(4) {
                            require(peek() in '0'..'9' || peek() in 'a'..'f' || peek() in 'A'..'F') {
                                "Invalid Unicode escape"
                            }
                            position++
                        }
                        else -> throw IllegalArgumentException("Invalid JSON escape")
                    }
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun number() {
            take('-')
            if (!take('0')) {
                require(peek() in '1'..'9') { "Invalid JSON number" }
                digits()
            }
            if (take('.')) {
                require(peek() in '0'..'9') { "Invalid JSON decimal" }
                digits()
            }
            if (take('e') || take('E')) {
                if (!take('+')) take('-')
                require(peek() in '0'..'9') { "Invalid JSON exponent" }
                digits()
            }
        }

        private fun digits() {
            while (peek() in '0'..'9') position++
        }

        private fun literal(text: String) {
            require(raw.startsWith(text, position)) { "Invalid JSON literal" }
            position += text.length
        }

        private fun whitespace() {
            while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') position++
        }

        private fun peek(): Char? = raw.getOrNull(position)

        private fun take(char: Char): Boolean {
            if (peek() != char) return false
            position++
            return true
        }

        private fun expect(char: Char) {
            require(take(char)) { "Expected $char at $position" }
        }
    }
}
