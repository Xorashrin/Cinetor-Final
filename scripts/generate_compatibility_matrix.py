#!/usr/bin/env python3
"""Generate the bundled-source compatibility inventory without network access.

A row is an inventory item, not a working-provider count. Repository endpoints,
nested descriptors, transport probes and fixtures never become playback evidence.
The generated asset is written only to an explicit path for parent review.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import tempfile
from urllib.parse import urlsplit

CAPABILITIES = (
    "catalog", "search", "details", "episodes", "directPlayback", "download",
    "externalPlayback",
)
REVIEWED_BLENDER_CANONICAL_SHA256 = (
    "e7fd1f9e0127463739322cdc604eabe50bf577d96c32774cf5b13b91dc5bede3"
)
RESERVED_ENGINE_IDS = {
    "stremio-http", "nuvio-js", "cloudstream-dex", "anime-direct",
    "kdrama-direct", "jellyfin", "nginx",
}


def _text(value, label):
    if (not isinstance(value, str) or not value.strip()
            or any(ord(char) < 32 or 127 <= ord(char) <= 159 for char in value)):
        raise ValueError(f"{label} must be a non-empty control-free string")
    return value


def _https(value, label):
    value = _text(value, label)
    parsed = urlsplit(value)
    if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
        raise ValueError(f"{label} must be an HTTPS URL without credentials")
    return value


def _object(value, label):
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an object")
    return value


def _list(value, label):
    if not isinstance(value, list):
        raise ValueError(f"{label} must be an array")
    return value


def _schema_one(value, label):
    _object(value, label)
    if type(value.get("schemaVersion")) is not int or value["schemaVersion"] != 1:
        raise ValueError(f"{label}.schemaVersion must be 1")


def entry(entry_id, ecosystem, name, status, reason, enabled=(), version=None, evidence_url=None):
    enabled = frozenset(enabled)
    unknown = enabled.difference(CAPABILITIES)
    if unknown:
        raise ValueError(f"unknown capabilities: {sorted(unknown)}")
    return {
        "id": _text(entry_id, "entry.id"),
        "ecosystem": _text(ecosystem, "entry.ecosystem"),
        "name": _text(name, "entry.name"),
        "status": status,
        "capability": {key: key in enabled for key in CAPABILITIES},
        "reason": _text(reason, "entry.reason"),
        "evidenceUrl": None if evidence_url is None else _https(evidence_url, "entry.evidenceUrl"),
        "version": None if version is None else _text(version, "entry.version"),
    }


def _validate_snapshot(snapshot):
    _schema_one(snapshot, "snapshot")
    references = _list(snapshot.get("entries"), "snapshot.entries")
    if type(snapshot.get("uniqueEntries")) is not int or snapshot["uniqueEntries"] != len(references):
        raise ValueError("snapshot unique-entry count does not match entries")
    actual_counts = Counter()
    snapshot_ids = set()
    for index, source in enumerate(references):
        source = _object(source, f"snapshot.entries[{index}]")
        _text(source.get("id"), f"snapshot.entries[{index}].id")
        snapshot_id = _text(source.get("snapshotId"), f"snapshot.entries[{index}].snapshotId")
        if snapshot_id in snapshot_ids:
            raise ValueError(f"duplicate snapshotId: {snapshot_id}")
        snapshot_ids.add(snapshot_id)
        ecosystem = _text(source.get("ecosystem"), f"snapshot.entries[{index}].ecosystem")
        _text(source.get("name"), f"snapshot.entries[{index}].name")
        endpoint = source.get("endpoint")
        if endpoint is not None:
            _https(endpoint, f"snapshot.entries[{index}].endpoint")
        actual_counts[ecosystem] += 1
    counts = snapshot.get("counts")
    if not isinstance(counts, dict) or any(type(value) is not int for value in counts.values()):
        raise ValueError("snapshot counts must be an object of integers")
    if dict(actual_counts) != counts:
        raise ValueError("snapshot ecosystem counts do not match entries")
    return references


def _validate_catalog(catalog):
    _schema_one(catalog, "catalog")
    _text(catalog.get("bundleVersion"), "catalog.bundleVersion")
    sources = _list(catalog.get("sources"), "catalog.sources")
    for index, source in enumerate(sources):
        source = _object(source, f"catalog.sources[{index}]")
        for key in ("id", "name", "engine"):
            _text(source.get(key), f"catalog.sources[{index}].{key}")
        if type(source.get("enabled")) is not bool:
            raise ValueError(f"catalog.sources[{index}].enabled must be boolean")
        endpoints = _list(source.get("endpoints"), f"catalog.sources[{index}].endpoints")
        for url_index, url in enumerate(endpoints):
            _https(url, f"catalog.sources[{index}].endpoints[{url_index}]")
    return sources


def _validate_films(films):
    _schema_one(films, "films")
    _text(films.get("providerId"), "films.providerId")
    _text(films.get("name"), "films.name")
    records = _list(films.get("films"), "films.films")
    for index, film in enumerate(records):
        film = _object(film, f"films.films[{index}]")
        _text(film.get("id"), f"films.films[{index}].id")
    return records


def _catalog_classification(source):
    engine = source["engine"].upper()
    if not source["enabled"]:
        return "REFERENCE_ONLY", (), "Disabled configured source; no active playback support claimed."
    if engine in ("CINEMETA", "TVMAZE"):
        return (
            "SUPPORTED_PROTOCOL", ("catalog", "search"),
            "Implemented catalog/search adapter only; upstream metadata APIs do not imply an "
            "implemented details/episodes adapter. No video license, native playback or download.",
        )
    if engine == "IPTV":
        return (
            "SUPPORTED_PROTOCOL", ("catalog", "search"),
            "M3U playlist protocol only; playlist CC0 metadata does not license broadcasts. "
            "No exact official-channel provenance plus Android playback proof reviewed; no "
            "channel E2E or recording claim.",
        )
    if engine in ("NUVIOJS", "NUVIO_JS"):
        return (
            "BLOCKED_SECURITY", (),
            "Untrusted arbitrary JavaScript; no reviewed isolated capability-safe runtime and "
            "signed plugin allowlist.",
        )
    if engine in ("CLOUDSTREAMDEX", "CLOUDSTREAM_DEX"):
        return (
            "BLOCKED_SECURITY", (),
            "Unsigned arbitrary .cs3/DEX Android code has no acceptable capability-safe host.",
        )
    if engine in ("JELLYFIN", "NGINX"):
        return (
            "REQUIRES_USER_SERVER", (),
            "Requires a user-owned authorized server endpoint and authentication; no default "
            "endpoint, credentials or bundled playback evidence.",
        )
    return "REFERENCE_ONLY", (), "Unreviewed configured source; configuration is not playback evidence."


def _protocol_documentation(engine):
    # Primary protocol documentation, never a descriptor endpoint or device proof.
    return {
        "CINEMETA": "https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/README.md",
        "STREMIO": "https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/README.md",
        "TVMAZE": "https://www.tvmaze.com/api",
        "IPTV": "https://github.com/iptv-org/iptv/blob/master/README.md",
    }.get(engine.upper())


def build_plugin_review_ledger(snapshot):
    """Account for each nested descriptor; do not invent executable artifacts.

    IDs are local descriptor identities, not executable digests or signatures.
    Repeated names and identical descriptors remain separate review obligations.
    """
    references = _validate_snapshot(snapshot)
    rows = []
    nuvio_count = 0
    for source in references:
        descriptors = _list(source.get("subplugins", []), "snapshot.subplugins")
        if source["ecosystem"] == "nuvio":
            nuvio_count += len(descriptors)
        occurrences = Counter()
        for descriptor in descriptors:
            descriptor = _object(descriptor, "subplugin")
            name = _text(descriptor.get("name"), "subplugin.name")
            canonical = json.dumps(descriptor, sort_keys=True, separators=(",", ":"),
                                   ensure_ascii=False).encode("utf-8")
            identity = hashlib.sha256(canonical).hexdigest()
            occurrences[identity] += 1
            rows.append({
                "id": f"{source['snapshotId']}:descriptor:{identity}:{occurrences[identity]}",
                "parentSnapshotId": source["snapshotId"],
                "ecosystem": source["ecosystem"],
                "name": name,
                "status": "UNREVIEWED",
                "license": None,
                "digest": None,
                "signer": None,
                "immutableVersion": None,
                "sourceCommit": None,
                "reproducibleBuildEvidence": None,
                "verifiedSignature": None,
                "releaseAuthorization": None,
                "revocationState": None,
                "licenseEvidence": None,
                "dependencyNotices": None,
                "distributionObligations": None,
                "securityReview": None,
                "approvedCapabilities": [],
                "mediaRightsEvidence": None,
                "territories": None,
                "playbackPermission": None,
                "downloadPermission": None,
                "attributionRequirements": None,
                "blockers": ["UNREVIEWED_ARTIFACT", "UNREVIEWED_LICENSE", "UNREVIEWED_RIGHTS", "NO_RUNTIME_E2E"],
                "reviewedAt": None,
                "recheckAt": None,
                "androidEvidence": None,
                "releaseCommit": None,
                "apkDigest": None,
            })
    if ("nuvioSubpluginDescriptors" in snapshot
            and snapshot["nuvioSubpluginDescriptors"] != nuvio_count):
        raise ValueError("snapshot nested descriptor count does not match entries")
    return {"schemaVersion": 1, "entries": sorted(rows, key=lambda row: row["id"])}


def render_plugin_review_ledger(ledger):
    return json.dumps(ledger, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def _is_reviewed_blender(films):
    canonical = json.dumps(
        films, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
    ).encode("utf-8")
    return (
        films["providerId"] == "blender-open-films"
        and hashlib.sha256(canonical).hexdigest() == REVIEWED_BLENDER_CANONICAL_SHA256
    )


def build_matrix(snapshot, catalog, films):
    references = _validate_snapshot(snapshot)
    sources = _validate_catalog(catalog)
    _validate_films(films)
    rows = []

    for source in references:
        rows.append(entry(
            source["snapshotId"], source["ecosystem"], source["name"], "REFERENCE_ONLY",
            "Bundled directory reference only; no reviewed provider rights, safe runtime or "
            "Android playback evidence. Nested descriptors are not additional supported "
            "providers. " + ("Endpoint unresolved; no URL inferred." if not source.get("endpoint")
                              else "Repository endpoint is not playback evidence."),
        ))

    for source in sources:
        status, capabilities, reason = _catalog_classification(source)
        rows.append(entry(
            source["id"], source["engine"].lower(), source["name"], status, reason,
            capabilities, "bundle:" + catalog["bundleVersion"],
            _protocol_documentation(source["engine"]) if status == "SUPPORTED_PROTOCOL" else None,
        ))

    if _is_reviewed_blender(films):
        rows.append(entry(
            films["providerId"], "blender", films["name"], "SUPPORTED_E2E",
            "Historical one-film Android E2E acceptance (onefilmE2E), v0.4.0-preview commit "
            "19683fb; not a current run. four-film provider transport-resolved, not four "
            "Android E2E films or providers. Download remains unclaimed here pending "
            "separate actual-file evidence review.",
            ("catalog", "search", "details", "directPlayback"), "19683fb",
            "https://github.com/Xorashrin/Cinetor-Final/releases/download/"
            "v0.4.0-preview/ANDROID-PROVIDER-EVIDENCE.json",
        ))
    else:
        rows.append(entry(
            films["providerId"], "open-film", films["name"], "REFERENCE_ONLY",
            "Film registry is not the parent-reviewed bundled Blender registry; fixture or changed "
            "content cannot inherit playback status.",
        ))

    rows.extend((
        entry(
            "stremio-http", "stremio", "Stremio HTTP adapter", "SUPPORTED_PROTOCOL",
            "Manifest/catalog/meta/stream protocol fixtures only; no default live direct-playback "
            "addon verified. Torrent hashes and external links are not direct streams.",
            ("catalog", "search", "details", "episodes"),
            evidence_url=_protocol_documentation("STREMIO"),
        ),
        entry(
            "nuvio-js", "nuvio", "Nuvio JavaScript runtime", "BLOCKED_SECURITY",
            "Untrusted arbitrary JavaScript; no reviewed isolated capability-safe runtime and "
            "signed plugin allowlist. Descriptors do not execute.",
        ),
        entry(
            "cloudstream-dex", "cloudstream", "CloudStream DEX runtime", "BLOCKED_SECURITY",
            "Unsigned arbitrary .cs3/DEX Android code has no acceptable capability-safe host. "
            "Descriptor/digest validation does not authorize execution.",
        ),
        entry(
            "anime-direct", "anime", "Anime native playback", "BLOCKED_RIGHTS_OR_API",
            "No reviewed authorized direct-stream API. Metadata and official external apps/embeds "
            "do not establish native episode playback.",
        ),
        entry(
            "kdrama-direct", "kdrama", "K-Drama native playback", "BLOCKED_RIGHTS_OR_API",
            "No reviewed authorized direct-stream API. Metadata and official external apps/embeds "
            "do not establish native episode playback.",
        ),
        entry(
            "jellyfin", "jellyfin", "Jellyfin user server", "REQUIRES_USER_SERVER",
            "Requires a user-owned server endpoint and authorization; no bundled server, "
            "credentials or default media playback evidence.",
        ),
        entry(
            "nginx", "nginx", "nginx user server", "REQUIRES_USER_SERVER",
            "Requires a user-owned authorized media server; directory parsing is not a "
            "preconfigured film/episode provider or playback proof.",
        ),
    ))

    ids = [row["id"] for row in rows]
    duplicate_ids = sorted(entry_id for entry_id, count in Counter(ids).items() if count > 1)
    if duplicate_ids:
        raise ValueError(f"duplicate compatibility IDs: {duplicate_ids}")
    return {"schemaVersion": 1, "entries": sorted(rows, key=lambda row: row["id"])}


def render_matrix(matrix):
    return json.dumps(matrix, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def _load(path, label):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read {label}: {error}") from error


def main(argv=None):
    root = Path(__file__).resolve().parents[1]
    assets = root / "app/src/main/assets"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path,
                        default=assets / "embedded_directory_snapshot.json")
    parser.add_argument("--catalog", type=Path, default=assets / "embedded_catalog.json")
    parser.add_argument("--films", type=Path, default=assets / "open_films.json")
    parser.add_argument("--output", type=Path, required=True,
                        help="explicit parent-review destination; never published implicitly")
    parser.add_argument("--plugin-ledger-output", type=Path,
                        help="optional explicit destination for the separate nested-plugin review ledger")
    parser.add_argument("--check", action="store_true",
                        help="fail if output differs; do not modify it")
    args = parser.parse_args(argv)
    args.output = args.output.resolve()
    if args.plugin_ledger_output is not None:
        args.plugin_ledger_output = args.plugin_ledger_output.resolve()
        if args.plugin_ledger_output == args.output:
            parser.error("matrix and plugin ledger outputs must be separate paths")
    try:
        snapshot = _load(args.snapshot, "snapshot")
        rendered = render_matrix(build_matrix(
            snapshot, _load(args.catalog, "catalog"), _load(args.films, "films"),
        ))
        ledger_rendered = (render_plugin_review_ledger(build_plugin_review_ledger(snapshot))
                           if args.plugin_ledger_output is not None else None)
        if args.check:
            if not args.output.is_file() or args.output.read_text(encoding="utf-8") != rendered:
                parser.error(f"stale or missing output: {args.output}")
            if args.plugin_ledger_output is not None and (
                    not args.plugin_ledger_output.is_file()
                    or args.plugin_ledger_output.read_text(encoding="utf-8") != ledger_rendered):
                parser.error(f"stale or missing output: {args.plugin_ledger_output}")
            return 0
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(
            "w", encoding="utf-8", dir=args.output.parent, prefix=args.output.name + ".",
            delete=False,
        ) as temporary:
            temporary.write(rendered)
            temporary_path = Path(temporary.name)
        temporary_path.replace(args.output)
        if args.plugin_ledger_output is not None and ledger_rendered is not None:
            args.plugin_ledger_output.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile(
                "w", encoding="utf-8", dir=args.plugin_ledger_output.parent,
                prefix=args.plugin_ledger_output.name + ".", delete=False,
            ) as temporary:
                temporary.write(ledger_rendered)
                ledger_temporary_path = Path(temporary.name)
            ledger_temporary_path.replace(args.plugin_ledger_output)
    except (OSError, UnicodeError, ValueError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
