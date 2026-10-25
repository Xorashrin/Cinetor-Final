package com.cinator.app.engines.nuvio

import java.security.MessageDigest

/**
 * Source-reviewed, authored CommonJS-shaped resolver; NOT the full Nuvio API.
 * No remote script admission and no execution occur here. Existing NuvioPluginPolicy
 * remains unchanged. The native host must retrieve this exact source by ID.
 *
 * JS signature: module.exports.getStreams(parentDocument, expectedUuid, preferredHeight).
 * parentDocument is a JSON STRING of the parent's already-reviewed Blender PeerTube
 * video response; expectedUuid is the exact canonical UUID; preferredHeight is an
 * integer EXACT match (no quality/title fallback). Result is a JSON STRING containing
 * zero or one {uuid, height, url, size} candidate objects, NOT trusted StreamSources.
 * The parent still validates content identity, provenance, rights, full-film duration,
 * byte/output budgets and exact candidate metadata before playback/download.
 *
 * A fresh native context loads get(ID), binds the three primitive values through its
 * data bridge, then evaluates INVOCATION. Never interpolate document data into source.
 * The fixed invocation reads __parentDocument, __expectedUuid, __preferredHeight.
 * No fetch, fs, settings, auth, asynchronous jobs or host capabilities are needed.
 * The host must independently enforce memory/deadline/IPC limits; JS guards are not
 * a sandbox. Character limit is 131072, at most 8 playlists and 256 total file rows.
 */
object ReviewedEmbeddedScript {
    const val BLENDER_RESOLVER_ID = "blender-reviewed-document-v1"
    const val BLENDER_RESOLVER_SHA256 = "4ef8dd159ce0b945a590db2b14f2a23c4b67be6a3161ca69018b15bed3bf29bc"
    const val INVOCATION = "module.exports.getStreams(__parentDocument, __expectedUuid, __preferredHeight)"

    /** Unknown IDs never load code; byte drift fails closed against the reviewed pin. */
    fun get(id: String): String? {
        if (id != BLENDER_RESOLVER_ID) return null
        check(isApproved(id, SOURCE)) { "Reviewed embedded resolver digest mismatch" }
        return SOURCE
    }

    /** Integrity check only, never an external-code execution/registration entry point. */
    fun isApproved(id: String, source: String): Boolean {
        if (id != BLENDER_RESOLVER_ID || source.length != SOURCE.length) return false
        val actual = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        val expected = ByteArray(32) { BLENDER_RESOLVER_SHA256.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return MessageDigest.isEqual(actual, expected)
    }

    // LF, UTF-8, no leading/trailing newline; pin changes require source review.
    private const val SOURCE = """"use strict";
var module = { exports: {} };
module.exports.getStreams = function (parentDocument, expectedUuid, preferredHeight) {
    if (typeof parentDocument !== "string" || parentDocument.length > 131072 ||
        typeof expectedUuid !== "string" || expectedUuid.length !== 36 ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(expectedUuid) ||
        !Number.isInteger(preferredHeight) || preferredHeight < 144 || preferredHeight > 2160) return "[]";
    var data;
    try { data = JSON.parse(parentDocument); } catch (ignored) { return "[]"; }
    if (!data || typeof data !== "object" || Array.isArray(data) ||
        data.uuid !== expectedUuid || data.isLocal !== true || data.isLive !== false ||
        data.url !== "https://video.blender.org/videos/watch/" + expectedUuid) return "[]";
    var groups = [];
    var total = 0;
    if (data.files !== undefined) {
        if (!Array.isArray(data.files)) return "[]";
        groups.push(data.files);
        total += data.files.length;
    }
    if (data.streamingPlaylists !== undefined) {
        if (!Array.isArray(data.streamingPlaylists) || data.streamingPlaylists.length > 8) return "[]";
        for (var p = 0; p < data.streamingPlaylists.length; p++) {
            var playlist = data.streamingPlaylists[p];
            if (!playlist || typeof playlist !== "object" || !Array.isArray(playlist.files)) return "[]";
            total += playlist.files.length;
            if (total > 256) return "[]";
            groups.push(playlist.files);
        }
    }
    if (total > 256) return "[]";
    var expectedUrl = "https://video.blender.org/object-storage/web_videos/" + expectedUuid + "-" + preferredHeight + ".mp4";
    var results = [];
    for (var g = 0; g < groups.length; g++) {
        for (var i = 0; i < groups[g].length; i++) {
            var file = groups[g][i];
            if (!file || typeof file !== "object" || file.height !== preferredHeight ||
                !file.resolution || file.resolution.id !== preferredHeight ||
                file.fileUrl !== expectedUrl || !Number.isSafeInteger(file.size) || file.size <= 0 ||
                file.hasAudio !== true || file.hasVideo !== true) continue;
            if (results.length === 0) results.push({uuid: expectedUuid, height: preferredHeight, url: file.fileUrl, size: file.size});
        }
    }
    return JSON.stringify(results);
};"""
}
