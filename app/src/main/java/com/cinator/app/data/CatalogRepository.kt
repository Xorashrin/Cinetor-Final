package com.cinator.app.data

import com.cinator.app.domain.FeedSection
import com.cinator.app.domain.MediaCard
import java.net.URI
import java.net.URLEncoder

class CatalogRepository(
    val bundle: EmbeddedCatalog,
    val http: TextHttpClient = BoundedHttpClient()
) {
    fun getSourcesForSection(section: FeedSection): List<EmbeddedSource> =
        bundle.sources.filter { it.section == section && it.enabled }

    fun load(section: FeedSection, query: String = ""): CatalogLoadResult {
        val search = query.trim()
        val sources = getSourcesForSection(section)
        val items = mutableListOf<MediaCard>()
        val failures = mutableListOf<String>()
        for (source in sources) {
            val progress = SourceProgress()
            if (source.engine == "OPEN_VIDEO") {
                progress.attempt {
                    items.addAll(matching(OpenVideoCatalogParser.parse("", section, source.id, source.name), search))
                }
            } else {
                // Registry endpoints are additive categories/queries, not mirrors.
                val endpoints = if (source.engine == "TVMAZE" && search.isNotEmpty())
                    listOf("https://api.tvmaze.com/search/shows?q=${encodePath(search)}") else source.endpoints.distinct()
                if (endpoints.isEmpty()) progress.attempt { error("Missing endpoints") }
                for (endpoint in endpoints) progress.attempt {
                    require(UrlPolicyValidator.isSafeHttpsUrl(endpoint)) { "Unsafe catalog endpoint" }
                    if (source.engine == "CINEMETA" && URI(endpoint).path.endsWith("/manifest.json")) {
                        loadManifest(source, endpoint, section, search, progress, items)
                    } else {
                        val parsed = parsePayload(source, section, http.get(endpoint))
                        items.addAll(if (source.engine == "TVMAZE") parsed else matching(parsed, search))
                    }
                }
            }
            if (progress.failed > 0) {
                // Exactly one diagnostic per affected provider. Never echo endpoints or exception messages.
                failures.add("${source.id}: ${progress.failed}/${progress.attempted} requests failed; completed results retained")
            }
        }
        return CatalogLoadResult(items.filter { it.section == section }.distinctBy { it.providerId to it.id }, failures, sources.size)
    }

    private class SourceProgress {
        var attempted = 0
        var failed = 0
        fun attempt(block: () -> Unit) {
            attempted++
            if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Catalog request cancelled")
            try {
                block()
            } catch (cancelled: java.util.concurrent.CancellationException) {
                throw cancelled
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw java.util.concurrent.CancellationException("Catalog request cancelled")
            } catch (error: Exception) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Catalog request cancelled")
                failed++
            }
        }
    }

    private fun parsePayload(source: EmbeddedSource, section: FeedSection, payload: String): List<MediaCard> =
        when (source.engine) {
            "CINEMETA" -> CinemetaCatalogParser.parse(payload, section, source.id, source.name)
            "TVMAZE" -> TVMazeCatalogParser.parse(payload, section, source.id, source.name)
            "IPTV" -> MultiCategoryIptvParser.parse(payload, section, source.id, source.name)
            else -> throw IllegalArgumentException("Unsupported catalog engine")
        }

    private fun loadManifest(
        source: EmbeddedSource, endpoint: String, section: FeedSection, query: String,
        progress: SourceProgress, items: MutableList<MediaCard>
    ) {
        val manifest = CinemetaManifestParser.parse(http.get(endpoint))
        val suppliedExtras = if (query.isEmpty()) emptySet() else setOf("search")
        val declarations = manifest.catalogs.filter {
            it.type in setOf("movie", "series") && suppliedExtras.containsAll(it.extraRequired) &&
                (query.isEmpty() || "search" in it.extraSupported)
        }
        require(declarations.isNotEmpty()) { "No catalog supports this request" }
        val base = URI(endpoint).resolve("./").toString()
        for (catalog in declarations) progress.attempt {
            val extra = if (query.isEmpty()) "" else "/search=${encodePath(query)}"
            val url = "${base}catalog/${encodePath(catalog.type)}/${encodePath(catalog.id)}$extra.json"
            require(UrlPolicyValidator.isSafeHttpsUrl(url)) { "Unsafe catalog route" }
            items.addAll(CinemetaCatalogParser.parse(http.get(url), section, source.id, source.name))
        }
    }

    private fun matching(items: List<MediaCard>, query: String): List<MediaCard> = if (query.isEmpty()) items else items.filter {
        it.title.contains(query, true) || it.synopsis.contains(query, true) || it.genres.any { genre -> genre.contains(query, true) }
    }

    private fun encodePath(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    companion object {
        /** Caller must read the asset registry; there is no second hard-coded catalog. */
        fun createDefault(bundledJson: String): CatalogRepository = CatalogRepository(EmbeddedCatalogParser.parse(bundledJson))
    }
}
