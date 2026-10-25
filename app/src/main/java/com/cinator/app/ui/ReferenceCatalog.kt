package com.cinator.app.ui

import org.json.JSONObject

data class ReferenceEntry(
    val id: String,
    val name: String,
    val ecosystem: String,
    val endpoint: String?,
    val status: String,
    val runtimeEnabled: Boolean,
    val note: String,
    val descriptors: List<String>,
    val searchText: String
)

data class ReferenceSnapshot(
    val capturedAt: String,
    val coverage: String,
    val entries: List<ReferenceEntry>
) {
    val ecosystemCounts: Map<String, Int> = entries.groupingBy { it.ecosystem }.eachCount()
    val descriptorCount: Int = entries.sumOf { it.descriptors.size }

    fun search(query: String): List<ReferenceEntry> {
        val needle = query.trim()
        return if (needle.isEmpty()) entries else entries.filter {
            it.searchText.contains(needle, ignoreCase = true)
        }
    }

    fun page(query: String, page: Int, pageSize: Int): List<ReferenceEntry> {
        require(page >= 0 && pageSize in 1..100)
        return search(query).drop(page * pageSize).take(pageSize)
    }
}

/** Parses the bundled audit snapshot only. It never fetches endpoints or loads plugin code. */
object ReferenceCatalog {
    fun parse(raw: String): ReferenceSnapshot {
        val root = JSONObject(raw)
        require(root.getInt("schemaVersion") == 1) { "Unsupported reference schema" }
        val array = root.getJSONArray("entries")
        val byId = linkedMapOf<String, ReferenceEntry>()
        for (index in 0 until array.length()) {
            val value = array.getJSONObject(index)
            val status = value.getString("status")
            val enabled = value.optBoolean("runtimeEnabled", false)
            require(status == "REFERENCE_ONLY" && !enabled) { "Reference catalog must remain metadata-only" }
            val descriptors = value.optJSONArray("subplugins")
            val descriptorNames = if (descriptors == null) emptyList() else (0 until descriptors.length()).map {
                val descriptor = descriptors.getJSONObject(it)
                "${descriptor.optString("name")} — ${descriptor.optString("description")} ${descriptor.optJSONArray("languages") ?: ""}"
            }
            val entry = ReferenceEntry(
                id = value.getString("id"),
                name = value.getString("name"),
                ecosystem = value.getString("ecosystem"),
                endpoint = value.optString("endpoint", "").takeIf(String::isNotBlank),
                status = status,
                runtimeEnabled = false,
                note = value.optString("note", "Directory reference; runtime disabled."),
                descriptors = descriptorNames,
                searchText = listOf(
                    value.getString("id"), value.getString("name"), value.getString("ecosystem"),
                    value.optString("endpoint"), value.optString("note"), descriptorNames.joinToString(" "),
                    value.optJSONArray("aliases")?.toString().orEmpty()
                ).joinToString(" ")
            )
            byId.putIfAbsent(entry.id, entry)
        }
        return ReferenceSnapshot(root.optString("capturedAt"), root.optString("coverage"), byId.values.toList())
    }
}
