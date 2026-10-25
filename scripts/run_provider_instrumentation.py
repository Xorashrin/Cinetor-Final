#!/usr/bin/env python3
"""Run installed provider Android test; validate and retain original device evidence.

Usage: python3 scripts/run_provider_instrumentation.py OUTPUT_DIRECTORY
Uses adb device selection (including ANDROID_SERIAL). Does not build or install.
"""
import argparse
from decimal import Decimal, DecimalException
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile

if __package__:
    from .run_isolated_js_instrumentation import validate_instrumentation
else:
    from run_isolated_js_instrumentation import validate_instrumentation

PACKAGE = "com.cinator.app"
TEST_CLASS = PACKAGE + ".ProviderPlaybackE2ETest"
DEVICE_DIRECTORY = "files/provider-e2e"
MAX_RESULT_BYTES = 2 * 1024 * 1024
REQUIRED_STAGES = {
    "android_lossless_numeric_admission", "bundled_manifest", "catalog", "search",
    "details", "resolve", "activity_launch", "full_download",
    "offline_rejects_network_uri", "offline_transport",
} | {f"{mode}_{stage}" for mode in ("online", "offline")
     for stage in ("ready_rendered", "advancing", "pause", "seek_resume")}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def read_device_result(archive):
    """Read one bounded regular JSON member, never extract tar paths/links."""
    raw = None
    with tarfile.open(archive, "r:") as bundle:
        for index, member in enumerate(bundle):
            require(index < 128, "Too many archive members")
            name = member.name.removeprefix("./")
            path = PurePosixPath(name)
            require(not path.is_absolute() and ".." not in path.parts,
                    "Unsafe archive path")
            if member.isdir() and name.rstrip("/") in ("files", DEVICE_DIRECTORY):
                continue
            require(re.fullmatch(r"files/provider-e2e/provider-playback-[^/]+\.json", name)
                    and member.isfile() and raw is None,
                    "Missing, unexpected, linked or ambiguous device result")
            require(0 < member.size <= MAX_RESULT_BYTES, "Empty or oversized device result")
            source = bundle.extractfile(member)
            if source is None:
                raise ValueError("Unreadable device result")
            with source:
                raw = source.read(MAX_RESULT_BYTES + 1)
            require(len(raw) == member.size, "Truncated device result")
    require(raw is not None, "Missing device result")
    return raw


def strict_json(raw):
    def unique_object(pairs):
        obj = {}
        for key, value in pairs:
            require(key not in obj, "Duplicate JSON key: " + key)
            obj[key] = value
        return obj
    def reject_constant(value):
        raise ValueError("Non-finite JSON number: " + value)
    try:
        return json.loads(raw, object_pairs_hook=unique_object, parse_constant=reject_constant,
                          parse_float=Decimal)
    except RecursionError as failure:
        raise ValueError("JSON nesting limit exceeded") from failure
    except DecimalException as failure:
        raise ValueError("Invalid JSON decimal") from failure


def validate_candidate(row):
    # Validate every copy before equality: Python considers True == 1 == Decimal("1").
    require(isinstance(row, dict) and set(row) == {"uuid", "height", "size", "url"} and
            isinstance(row["uuid"], str) and row["uuid"] and
            integer(row["height"], 144) and row["height"] <= 2160 and
            integer(row["size"], 1) and isinstance(row["url"], str) and row["url"],
            "Malformed native candidate")


def validate_evidence(raw):
    result = strict_json(raw)
    require(isinstance(result, dict), "Evidence must be an object")
    require(result.get("status") == "PASSED" and not result.get("failure"), "Device test did not pass")
    require(result.get("test") == "ProviderPlaybackE2ETest" and
            type(result.get("schemaVersion")) is int and result["schemaVersion"] == 2,
            "Wrong evidence test or schema")
    stages = result.get("stages")
    require(isinstance(stages, list) and stages, "Missing stages")
    names = []
    for stage in stages:
        require(isinstance(stage, dict) and stage.get("status") == "PASSED" and
                isinstance(stage.get("name"), str) and not stage.get("exception"), "Non-passing stage")
        names.append(stage["name"])
    require(len(set(names)) == len(names) and REQUIRED_STAGES <= set(names), "Missing or duplicate stages")
    observations = result.get("observations")
    require(isinstance(observations, dict), "Missing observations")
    def observation(name):
        value = observations.get(name)
        require(isinstance(value, dict), "Missing observation: " + name)
        return value
    native = result.get("nativeResults")
    require(isinstance(native, list) and native, "Missing native results")
    resolution = observation("nativeResolution")
    resolved = observation("resolve")
    app_uid, app_pid = resolution.get("appUid"), resolution.get("appPid")
    require(integer(app_uid) and integer(app_pid, 1), "Missing app process identity")
    for count in (resolution.get("observedCallCount"), resolved.get("nativeObservedCallCount")):
        require(integer(count, 1) and count == len(native), "Native call count mismatch")
    candidates = []
    for reply in native:
        require(isinstance(reply, dict), "Malformed native result")
        require(integer(reply.get("uid")) and reply["uid"] != app_uid and
                integer(reply.get("pid"), 1) and reply["pid"] != app_pid,
                "Native result is not from isolated process")
        require(reply.get("engineVersion") == "QuickJS 2026-06-04 (04be246)", "Wrong native engine")
        require(isinstance(reply.get("rawResultJson"), str), "Missing raw native result")
        rows = strict_json(reply["rawResultJson"])
        emitted = reply.get("candidates")
        require(isinstance(rows, list) and len(rows) <= 1 and
                isinstance(emitted, list) and len(emitted) == len(rows),
                "Native raw/candidate mismatch")
        for row in rows + emitted:
            validate_candidate(row)
        require(rows == emitted, "Native raw/candidate mismatch")
        candidates.extend(rows)
    matched = resolved.get("matchedNativeCandidate")
    validate_candidate(matched)
    require(candidates.count(matched) == 1,
            "Selected source has no unique native candidate")
    content_id = "blender:" + matched["uuid"]
    stream_id = content_id + ":" + str(matched["height"])
    url = matched["url"]
    require(resolved.get("contentId") == content_id and resolved.get("streamId") == stream_id and
            resolved.get("quality") == str(matched["height"]) + "p" and resolved.get("url") == url,
            "Resolved source tuple differs from native result")
    film = observation("film")
    require(film.get("uuid") == matched["uuid"] and film.get("contentId") == content_id and
            film.get("streamUrl") == url and integer(film.get("expectedBytes"), 1) and
            film["expectedBytes"] == matched["size"],
            "Native source differs from audited film")
    for name in ("onlineTransport", "downloadResult", "offlineTransport"):
        item = observation(name)
        require(item.get("streamId") == stream_id and item.get("sourceUrl") == url,
                name + " source identity mismatch")
        require(integer(item.get("bytesRead"), 1) if name != "downloadResult" else
                item.get("status") == "COMPLETED" and not item.get("error") and
                item.get("contentId") == content_id and item.get("providerId") == "blender-open-films" and
                integer(item.get("bytesDownloaded"), 1) and integer(item.get("totalBytes"), 1) and
                item["bytesDownloaded"] == matched["size"] and item["totalBytes"] == matched["size"],
                name + " transport did not complete")
    download, offline, integrity = observation("downloadResult"), observation("offlineTransport"), observation("downloadIntegrity")
    require(isinstance(download.get("localFile"), str) and download["localFile"].startswith("/") and
            download["localFile"] == offline.get("localFile") == integrity.get("localFile"),
            "Offline file differs from downloaded file")
    require(integer(integrity.get("fileBytes"), 1) and integer(integrity.get("bytesHashed"), 1) and
            integrity["fileBytes"] == matched["size"] and integrity["bytesHashed"] == matched["size"] and
            re.fullmatch(r"[a-fA-F0-9]{64}", str(integrity.get("sha256", ""))), "Missing full download integrity")
    require(integer(offline.get("fileOpens"), 1) and
            integer(offline.get("negativeProbeRejections"), 1) and offline["negativeProbeRejections"] == 1 and
            integer(offline.get("playbackForbiddenUriAttempts")) and offline["playbackForbiddenUriAttempts"] == 0, "Offline transport was not file-only")
    for mode in ("online", "offline"):
        ready = observation(mode + "Ready")
        require(integer(ready.get("playbackState")) and ready["playbackState"] == 3 and
                ready.get("isPlaying") is True and
                ready.get("surfaceViewShown") is True and "playerErrorCode" in ready and ready["playerErrorCode"] is None and
                integer(ready.get("renderedOutputBufferCount"), 1) and integer(ready.get("firstFrameEvents"), 1),
                mode + " has no rendered playback evidence")


def collect(out):
    errors = []
    for name, args in [
        ("logcat", ["adb", "logcat", "-d", "-v", "threadtime"]),
        ("private-evidence", ["adb", "exec-out", "run-as", PACKAGE, "tar", "-cf", "-", DEVICE_DIRECTORY]),
    ]:
        destination = out / ("logcat.txt" if name == "logcat" else "private-evidence.tar")
        try:
            with destination.open("wb") as sink:
                run = subprocess.run(args, stdout=sink, stderr=subprocess.PIPE, timeout=30)
            (out / (name + ".stderr.txt")).write_bytes(run.stderr)
            if run.returncode:
                errors.append(f"{name} collection exited {run.returncode}")
        except (OSError, subprocess.TimeoutExpired) as failure:
            errors.append(f"{name} collection failed: {failure}")
            (out / (name + ".stderr.txt")).write_text(str(failure) + "\n")
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output_directory", type=Path)
    out = parser.parse_args(argv).output_directory.resolve()
    out.mkdir(parents=True, exist_ok=True)
    (out / "result.json").unlink(missing_ok=True)
    (out / "instrumentation.txt").write_bytes(b"")
    text, returncode, errors = "", None, []
    try:
        clear = subprocess.run(["adb", "shell", "run-as", PACKAGE, "rm", "-rf", DEVICE_DIRECTORY],
                               capture_output=True, timeout=30)
        (out / "cleanup.txt").write_bytes(clear.stdout + clear.stderr)
        require(clear.returncode == 0, "Cannot clear stale device evidence")
        run = subprocess.run(["adb", "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
                              PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=1250)
        (out / "instrumentation.txt").write_bytes(run.stdout)
        text, returncode = run.stdout.decode("utf-8", errors="replace"), run.returncode
    except subprocess.TimeoutExpired as failure:
        raw = failure.stdout or b""
        (out / "instrumentation.txt").write_bytes(raw)
        text = raw.decode("utf-8", errors="replace")
        errors.append(f"Device command timed out after {failure.timeout}s")
    except (OSError, ValueError) as failure:
        errors.append(str(failure))
    finally:
        print(text, end="", flush=True)
        errors.extend(collect(out))
    try:
        validate_instrumentation(text, returncode)
    except ValueError as failure:
        errors.append(str(failure))
    try:
        raw = read_device_result(out / "private-evidence.tar")
        # Preserve test-owned bytes, including FAILED evidence; never synthesize a summary.
        (out / "result.json").write_bytes(raw)
        validate_evidence(raw)
    except (OSError, ValueError, tarfile.TarError) as failure:
        errors.append(f"Invalid provider evidence: {failure}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Validated provider device evidence: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
