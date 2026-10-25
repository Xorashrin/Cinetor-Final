#!/usr/bin/env python3
"""Run installed, opt-in InternetArchivePlaybackTest; retain original Android JSON.
No build/install or default activation. Usage: SCRIPT ABSOLUTE_OUTPUT_DIRECTORY.
"""
import argparse
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import tarfile
from typing import Any
if __package__:
    from .run_provider_instrumentation import strict_json, integer, require
    from .run_isolated_js_instrumentation import validate_instrumentation as base_protocol
else:
    from run_provider_instrumentation import strict_json, integer, require
    from run_isolated_js_instrumentation import validate_instrumentation as base_protocol

PACKAGE = "com.cinator.app"
TEST_CLASS = PACKAGE + ".InternetArchivePlaybackTest"
TEST_METHOD = "allowlistedFilm_adapterPlaybackDownloadAndFileOnly"
DEVICE_DIRECTORY = "files/archive-e2e"
RESULT_MEMBER = DEVICE_DIRECTORY + "/result.json"
MAX_RESULT_BYTES = 2 * 1024 * 1024
ITEM = "BigBuckBunny_328"
FILE_NAME = "BigBuckBunny_512kb.mp4"
CONTENT = "archive:" + ITEM
STREAM = CONTENT + ":" + FILE_NAME
PROVIDER = "internet-archive-acceptance"
URL = "https://archive.org/download/" + ITEM + "/" + FILE_NAME
SIZE = 43315070
SHA256 = "3aaecd80a8fa3cbe6df8e79364af0412b7da6fa423d14c8c6bd332b32d7626b7"
PIN = "Observed full-file retrieval SHA-256; not publisher hash"
SCOPE = "Real IA adapter and ProviderResolver; test-overlay Media3 controls; test-only DefaultHttpDataSource playback transport; full Cinator download; exact-file-only Media3; no default activation"
NOT_VERIFIED = ["Default provider activation", "Rights/credits review", "App navigation/player chrome",
                "Device-wide offline mode", "Audible audio", "Every frame/end credits",
                "Production playback transport/redirect policy"]
REQUIRED_STAGES = {"catalog", "search", "details", "resolve", "activity_launch", "online_transport",
                   "full_download", "offline_rejects_forbidden_uris", "offline_transport", "cleanup"} | {
    mode + "_" + stage for mode in ("online", "offline")
    for stage in ("ready_rendered", "advancing", "pause", "seek_resume")}


def validate_instrumentation(text, returncode):
    base_protocol(text, returncode)
    # Bind both start and successful finish to the selected exact test, not merely OK (1 test).
    events, event = [], {}
    pending, terminated = False, False
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("INSTRUMENTATION_"):
            require(not terminated, "Protocol record after terminal code")
        if line.startswith("INSTRUMENTATION_CODE:"):
            require(not pending and [code for code, _ in events] == [1, 0],
                    "Terminal code before complete start/finish pair")
            terminated = True
        if line.startswith("INSTRUMENTATION_STATUS:"):
            pending = True
        match = re.fullmatch(r"INSTRUMENTATION_STATUS: (class|test|numtests|current)=(.*)", line)
        if match:
            require(match[1] not in event, "Duplicate protocol identity field")
            event[match[1]] = match[2]
        code = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line.strip())
        if code:
            events.append((int(code[1]), event)); event = {}; pending = False
    require(terminated and not pending and not event, "Unfinished instrumentation event")
    require([code for code, _ in events] == [1, 0], "Expected one start and one successful finish")
    for _, row in events:
        require(row == {"class": TEST_CLASS, "test": TEST_METHOD, "numtests": "1", "current": "1"},
                "Instrumentation identity/count mismatch")


def read_device_result(archive):
    require(archive.stat().st_size <= 3 * 1024 * 1024, "Oversized evidence archive")
    raw = None
    with tarfile.open(archive, "r:") as bundle:
        for index, member in enumerate(bundle):
            require(index < 8, "Too many archive members")
            name = member.name.removeprefix("./")
            path = PurePosixPath(name)
            require(not path.is_absolute() and ".." not in path.parts, "Unsafe archive path")
            if member.isdir() and name.rstrip("/") in ("files", DEVICE_DIRECTORY):
                continue
            require(name == RESULT_MEMBER and member.isfile() and raw is None,
                    "Unexpected, linked or duplicate evidence member")
            require(0 < member.size <= MAX_RESULT_BYTES, "Invalid result size")
            source = bundle.extractfile(member)
            if source is None:
                raise ValueError("Unreadable evidence")
            with source: raw = source.read(MAX_RESULT_BYTES + 1)
            require(len(raw) == member.size, "Truncated evidence")
    require(raw is not None, "Missing device result")
    return raw


def validate_evidence(raw):
    result: Any = strict_json(raw)
    require(isinstance(result, dict) and result.get("status") == "PASSED" and not result.get("failure"), "Test did not pass")
    require(result.get("test") == "InternetArchivePlaybackTest" and type(result.get("schemaVersion")) is int
            and result["schemaVersion"] == 1, "Wrong test/schema")
    require(result.get("scope") == SCOPE and result.get("notVerified") == NOT_VERIFIED, "Wrong acceptance scope")
    require(integer(result.get("elapsedMs"), 1) and result["elapsedMs"] <= 900000 and
            integer(result.get("sdkInt"), 26), "Invalid device/budget evidence")
    stages = result.get("stages")
    require(isinstance(stages, list), "Missing stages")
    names = []
    for stage in stages:
        require(isinstance(stage, dict) and stage.get("status") == "PASSED" and not stage.get("exception"), "Non-passing stage")
        require(isinstance(stage.get("name"), str), "Invalid stage name")
        names.append(stage["name"])
    require(len(names) == len(set(names)) and set(names) == REQUIRED_STAGES, "Wrong or duplicated stage set")
    observations = result.get("observations")
    require(isinstance(observations, dict), "Missing observations")
    def obs(name):
        value = observations.get(name)
        require(isinstance(value, dict), "Missing observation: " + name)
        return value
    def exact_int(row, key, expected):
        require(type(row.get(key)) is int and row[key] == expected, "Wrong integer: " + key)
    film = obs("film")
    for key, expected in {"itemId": ITEM, "contentId": CONTENT, "providerId": PROVIDER, "streamId": STREAM,
                          "url": URL, "fileName": FILE_NAME, "expectedSha256": SHA256, "pinProvenance": PIN}.items():
        require(film.get(key) == expected, "Film pin mismatch: " + key)
    exact_int(film, "expectedBytes", SIZE); exact_int(film, "expectedDurationMs", 596416)
    catalog = obs("catalog")
    require(catalog.get("selectedId") == CONTENT and catalog.get("contentId") == CONTENT and
            catalog.get("providerId") == PROVIDER, "Wrong selected card namespace")
    resolved = obs("resolve")
    require(resolved.get("streamId") == STREAM and resolved.get("contentId") == CONTENT and
            resolved.get("providerId") == PROVIDER and resolved.get("url") == URL, "Wrong resolved tuple")
    require(integer(resolved.get("sourceCount"), 1) and resolved["sourceCount"] <= 3, "Wrong resolution count")
    metadata = resolved.get("metadataFile")
    require(isinstance(metadata, dict) and metadata.get("name") == FILE_NAME and
            (type(metadata.get("size")) is str and metadata["size"] == str(SIZE) or
             type(metadata.get("size")) is int and metadata["size"] == SIZE), "Wrong live metadata rendition")
    requests = result.get("httpRequests")
    require(isinstance(requests, list) and 5 <= len(requests) <= 24, "Missing bounded HTTP observations")
    successes = 0
    for request in requests:
        require(isinstance(request, dict) and request.get("url") == "https://archive.org/metadata/" + ITEM,
                "Wrong metadata request")
        if request.get("outcome") == "RESPONSE":
            successes += 1
            require(request.get("identifier") == ITEM and integer(request.get("utf8Bytes"), 1) and
                    request["utf8Bytes"] <= MAX_RESULT_BYTES and
                    re.fullmatch(r"[a-f0-9]{64}", str(request.get("metadataSha256", ""))), "Invalid HTTP response observation")
    require(successes >= 5, "No successful live metadata chain")
    for name in ("onlineTransport", "downloadResult", "offlineTransport"):
        value = obs(name)
        require(value.get("streamId") == STREAM and value.get("sourceUrl") == URL, "Cross-stage source mismatch")
        if name != "downloadResult": require(integer(value.get("bytesRead"), 1), "No transport bytes")
    require(obs("onlineTransport")["bytesRead"] <= 32 * 1024 * 1024, "Streaming exceeded budget")
    download, integrity, offline = obs("downloadResult"), obs("downloadIntegrity"), obs("offlineTransport")
    require(download.get("status") == "COMPLETED" and not download.get("error") and
            download.get("contentId") == CONTENT and download.get("providerId") == PROVIDER and
            integer(download.get("progressCallbackCount"), 1), "Download not complete")
    for row, keys in [(download, ("totalBytes", "bytesDownloaded")), (integrity, ("fileBytes", "bytesHashed"))]:
        for key in keys: exact_int(row, key, SIZE)
    path = download.get("localFile")
    require(isinstance(path, str) and path.startswith("/") and "/files/downloads/" in path and
            ".." not in PurePosixPath(path).parts and path.endswith(".mp4") and
            path == integrity.get("localFile") == offline.get("localFile"), "Offline file identity mismatch")
    require(integrity.get("sha256") == SHA256 and integrity.get("expectedSha256") == SHA256 and
            integrity.get("fullHashPinStatus") == "MATCHED" and integrity.get("pinProvenance") == PIN,
            "Full observed file pin mismatch")
    require(offline.get("implementation") == "FileDataSource only; exact canonical file" and
            integer(offline.get("fileOpens"), 1) and offline.get("deviceNetworkDisabled") is False,
            "Wrong file-only scope")
    exact_int(offline, "negativeProbeRejections", 2); exact_int(offline, "playbackForbiddenUriAttempts", 0)
    def snapshot(value):
        require(isinstance(value, dict), "Missing player snapshot")
        for field in ("elapsedRealtimeMs", "playbackState", "positionMs", "durationMs", "renderedOutputBufferCount",
                      "firstFrameEvents", "seekDiscontinuities", "height"):
            require(integer(value.get(field)), "Invalid player integer: " + field)
        require(value.get("playerErrorCode", "missing") is None and value.get("surfaceViewShown") is True and
                value.get("mime") == "video/avc" and value["height"] == 240 and
                abs(value["durationMs"] - 596416) <= 2000, "Invalid decoded film snapshot")
        for field in ("isPlaying", "playWhenReady"): require(type(value.get(field)) is bool, "Invalid player boolean")
        require(not value["isPlaying"] or (value["playbackState"] == 3 and value["playWhenReady"]),
                "Playing snapshot is not READY with playback intent")
        return value
    for mode in ("online", "offline"):
        ready = snapshot(obs(mode + "Ready"))
        require(ready["playbackState"] == 3 and ready["isPlaying"] and ready["renderedOutputBufferCount"] > 0 and
                ready["firstFrameEvents"] > 0, "No rendered video")
        advance, pause, seek = obs(mode + "Advance"), obs(mode + "Pause"), obs(mode + "SeekResume")
        before, after = snapshot(advance.get("before")), snapshot(advance.get("after"))
        require(after["elapsedRealtimeMs"] > before["elapsedRealtimeMs"], "Advance snapshots not chronological")
        require(after["isPlaying"] and after["positionMs"] >= before["positionMs"] + 2000 and
                after["renderedOutputBufferCount"] >= before["renderedOutputBufferCount"] + 10, "No decoded progression")
        before, after = snapshot(pause.get("before")), snapshot(pause.get("after"))
        require(not before["isPlaying"] and not before["playWhenReady"] and not after["isPlaying"] and
                not after["playWhenReady"] and abs(after["positionMs"] - before["positionMs"]) <= 100 and
                after["elapsedRealtimeMs"] >= before["elapsedRealtimeMs"] + 1400, "Pause not observed")
        before, sought, resumed = (snapshot(seek.get(key)) for key in ("before", "sought", "resumed"))
        require(before["elapsedRealtimeMs"] < sought["elapsedRealtimeMs"] < resumed["elapsedRealtimeMs"],
                "Seek/resume snapshots not chronological")
        exact_int(seek, "targetMs", 30000)
        require(sought["seekDiscontinuities"] > before["seekDiscontinuities"] and
                sought["playbackState"] == 3 and abs(sought["positionMs"] - 30000) <= 1000 and
                not sought["isPlaying"] and not sought["playWhenReady"] and resumed["isPlaying"] and
                resumed["positionMs"] >= 32000 and
                resumed["renderedOutputBufferCount"] >= sought["renderedOutputBufferCount"] + 10, "Seek/resume not observed")


def collect(out):
    errors = []
    for name, args in [
        ("logcat", ["adb", "logcat", "-d", "-v", "threadtime"]),
        ("private-evidence", ["adb", "exec-out", "run-as", PACKAGE, "tar", "-cf", "-", RESULT_MEMBER]),
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
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=950)
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
        errors.append(f"Invalid Archive evidence: {failure}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Validated Archive device evidence: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())