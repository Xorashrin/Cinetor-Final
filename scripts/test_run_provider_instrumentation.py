#!/usr/bin/env python3
"""UNIT synthetic fixtures only; these are NOT Android/native execution evidence."""
import contextlib
import io
import json
from pathlib import Path
import runpy
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

RUNNER = Path(__file__).resolve().with_name("run_provider_instrumentation.py")
PROTOCOL = b"INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n"
MEMBER = "files/provider-e2e/provider-playback-UNIT.json"


def archive(raw, name=MEMBER, kind=tarfile.REGTYPE, duplicate=False):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as bundle:
        for _ in range(2 if duplicate else 1):
            entry = tarfile.TarInfo(name)
            entry.type = kind
            entry.size = len(raw) if kind == tarfile.REGTYPE else 0
            entry.linkname = "UNIT-target"
            bundle.addfile(entry, io.BytesIO(raw) if entry.size else None)
    return stream.getvalue()


class RunnerTest(unittest.TestCase):
    def run_runner(self, raw, *, protocol=PROTOCOL, cleanup_code=0, instrument_code=0,
                   timeout=False, collection_fail=False, collection_timeout=False, missing_adb=False, tar_bytes=None):
        calls = []
        with tempfile.TemporaryDirectory(prefix="provider-UNIT-") as directory:
            out = Path(directory)
            (out / "result.json").write_text('{"stale":true}')
            def adb(args, **kwargs):
                calls.append(args)
                self.assertGreater(kwargs.get("timeout", 0), 0)
                if missing_adb:
                    raise FileNotFoundError("UNIT adb missing")
                if collection_timeout and ("tar" in args or "logcat" in args):
                    raise subprocess.TimeoutExpired(args, 30)
                payload, code = b"", 0
                if "rm" in args:
                    code = cleanup_code
                elif "instrument" in args:
                    if timeout:
                        raise subprocess.TimeoutExpired(args, 1250, output=protocol)
                    payload, code = protocol, instrument_code
                elif "tar" in args:
                    payload = archive(raw) if tar_bytes is None else tar_bytes
                    code = int(collection_fail)
                elif "logcat" in args:
                    payload = b"UNIT synthetic log"
                else:
                    self.fail("Unexpected adb command: " + repr(args))
                sink = kwargs.get("stdout")
                if hasattr(sink, "write"):
                    sink.write(payload)
                    payload = b""
                if kwargs.get("text"):
                    return subprocess.CompletedProcess(args, code, payload.decode(), "")
                return subprocess.CompletedProcess(args, code, payload, b"")
            with patch("subprocess.run", side_effect=adb), patch.object(sys, "argv", [str(RUNNER), str(out)]), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                try:
                    runpy.run_path(str(RUNNER), run_name="__main__")
                    code = 0
                except SystemExit as stopped:
                    code = stopped.code
            files = {p.name: p.read_bytes() for p in out.iterdir() if p.is_file()}
            return code, files, calls

    def test_ok_protocol_without_native_evidence_cannot_pass(self):
        raw = json.dumps({"status": "PASSED", "test": "ProviderPlaybackE2ETest", "schemaVersion": 2, "nativeResults": []}).encode()
        code, files, calls = self.run_runner(raw)
        self.assertNotEqual(code, 0, "OK (1 test) alone must not manufacture a pass")
        self.assertNotIn(b"instrumentationPassed", files.get("result.json", b""))

    def test_import_is_safe(self):
        with patch("subprocess.run", side_effect=AssertionError("Import invoked adb")):
            module = runpy.run_path(str(RUNNER))
        self.assertTrue(callable(module.get("main")))

    def fixture(self):
        """Deliberately fake UNIT data, never saved as device evidence."""
        row = {"uuid": "UNIT-NOT-DEVICE", "height": 720, "size": 100, "url": "https://unit.invalid/film.mp4"}
        content = "blender:" + row["uuid"]
        identity = {"streamId": content + ":720", "sourceUrl": row["url"]}
        ready = {"playbackState": 3, "isPlaying": True, "surfaceViewShown": True,
                 "renderedOutputBufferCount": 10, "firstFrameEvents": 1, "playerErrorCode": None}
        stages = ["android_lossless_numeric_admission", "bundled_manifest", "catalog", "search",
                  "details", "resolve", "activity_launch", "full_download", "offline_rejects_network_uri",
                  "offline_transport", "online_ready_rendered", "online_advancing", "online_pause",
                  "online_seek_resume", "offline_ready_rendered", "offline_advancing", "offline_pause",
                  "offline_seek_resume"]
        return {"schemaVersion": 2, "test": "ProviderPlaybackE2ETest", "status": "PASSED",
                "UNIT_SYNTHETIC_NOT_DEVICE_EVIDENCE": True,
                "stages": [{"name": name, "status": "PASSED"} for name in stages],
                "nativeResults": [{"uid": 99001, "pid": 42, "engineVersion": "QuickJS 2026-06-04 (04be246)",
                                   "rawResultJson": json.dumps([row]), "candidates": [row]}],
                "observations": {
                    "nativeResolution": {"appUid": 10100, "appPid": 12, "observedCallCount": 1},
                    "resolve": {"streamId": identity["streamId"], "url": row["url"], "contentId": content,
                                "quality": "720p", "nativeObservedCallCount": 1, "matchedNativeCandidate": row},
                    "film": {"uuid": row["uuid"], "contentId": content, "streamUrl": row["url"], "expectedBytes": 100},
                    "onlineTransport": {**identity, "bytesRead": 2},
                    "downloadResult": {**identity, "status": "COMPLETED", "contentId": content,
                                       "providerId": "blender-open-films", "bytesDownloaded": 100,
                                       "totalBytes": 100, "localFile": "/UNIT/file.mp4"},
                    "offlineTransport": {**identity, "bytesRead": 2, "fileOpens": 1, "localFile": "/UNIT/file.mp4",
                                         "negativeProbeRejections": 1, "playbackForbiddenUriAttempts": 0},
                    "downloadIntegrity": {"localFile": "/UNIT/file.mp4", "fileBytes": 100, "bytesHashed": 100, "sha256": "a" * 64},
                    "onlineReady": dict(ready), "offlineReady": dict(ready)}}

    def test_unit_fixture_roundtrip_preserves_original_bytes(self):
        raw = (json.dumps(self.fixture(), indent=3) + "\n\n").encode()
        code, files, calls = self.run_runner(raw)
        self.assertEqual(code, 0)
        self.assertEqual(files["result.json"], raw)
        self.assertEqual(files["instrumentation.txt"], PROTOCOL)
        self.assertIn("rm", calls[0])
        self.assertIn("instrument", calls[1])

    def test_missing_and_contradictory_fields_fail_closed(self):
        changes = [
            (("status",), "FAILED"), (("schemaVersion",), 1), (("test",), "AnotherTest"),
            (("stages",), []), (("stages", 0, "status"), "RUNNING"),
            (("nativeResults",), []), (("nativeResults", 0, "uid"), 10100),
            (("nativeResults", 0, "pid"), 12), (("nativeResults", 0, "engineVersion"), "fake"),
            (("nativeResults", 0, "rawResultJson"), "[]"),
            (("nativeResults", 0, "rawResultJson"), "not json"),
            (("observations", "nativeResolution", "observedCallCount"), 2),
            (("observations", "resolve", "nativeObservedCallCount"), 0),
            (("observations", "resolve", "matchedNativeCandidate"), None),
            (("observations", "resolve", "contentId"), "wrong"),
            (("observations", "resolve", "quality"), "480p"),
            (("observations", "onlineTransport", "sourceUrl"), "wrong"),
            (("observations", "downloadResult", "streamId"), "wrong"),
            (("observations", "offlineTransport", "localFile"), "/other"),
            (("observations", "offlineTransport", "playbackForbiddenUriAttempts"), 1),
            (("observations", "downloadResult", "status"), "FAILED"),
            (("observations", "downloadIntegrity", "bytesHashed"), 1),
            (("observations", "onlineReady", "renderedOutputBufferCount"), 0),
            (("observations", "offlineReady", "playerErrorCode"), "DECODER_ERROR"),
        ]
        validator = runpy.run_path(str(RUNNER))["validate_evidence"]
        for path, replacement in changes:
            for remove in (False, True):
                with self.subTest(path=path, remove=remove):
                    fixture = self.fixture()
                    obj = fixture
                    for key in path[:-1]:
                        obj = obj[key]
                    if remove:
                        del obj[path[-1]]
                    else:
                        obj[path[-1]] = replacement
                    with self.assertRaises(ValueError):
                        validator(json.dumps(fixture).encode())

    def test_each_stage_required_and_duplicates_rejected(self):
        validator = runpy.run_path(str(RUNNER))["validate_evidence"]
        for index in range(len(self.fixture()["stages"])):
            fixture = self.fixture()
            del fixture["stages"][index]
            with self.subTest(missing=index), self.assertRaises(ValueError):
                validator(json.dumps(fixture).encode())
        fixture = self.fixture()
        fixture["stages"].append(fixture["stages"][0])
        with self.assertRaises(ValueError):
            validator(json.dumps(fixture).encode())

    def test_malformed_evidence_and_native_candidates_rejected(self):
        validator = runpy.run_path(str(RUNNER))["validate_evidence"]
        for raw in (b"null", b"[]", b"not-json", b"{", b"\xff"):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                validator(raw)
        for candidates in (None, {}, [None], [True], [{"uuid": "wrong"}], [1, 2]):
            fixture = self.fixture()
            fixture["nativeResults"][0]["rawResultJson"] = json.dumps(candidates)
            fixture["nativeResults"][0]["candidates"] = candidates
            with self.subTest(candidates=candidates), self.assertRaises(ValueError):
                validator(json.dumps(fixture).encode())

    def test_discrete_fields_reject_raw_wire_decimals(self):
        validator = runpy.run_path(str(RUNNER))["validate_evidence"]
        paths = [
            ("schemaVersion",),
            ("nativeResults", 0, "uid"), ("nativeResults", 0, "pid"),
            ("nativeResults", 0, "candidates", 0, "size"),
            ("nativeResults", 0, "candidates", 0, "height"),
            ("observations", "resolve", "matchedNativeCandidate", "size"),
            ("observations", "resolve", "matchedNativeCandidate", "height"),
        ] + [("observations", group, field) for group, fields in {
            "nativeResolution": ("appUid", "appPid", "observedCallCount"),
            "resolve": ("nativeObservedCallCount",),
            "film": ("expectedBytes",),
            "onlineTransport": ("bytesRead",),
            "downloadResult": ("bytesDownloaded", "totalBytes"),
            "downloadIntegrity": ("fileBytes", "bytesHashed"),
            "offlineTransport": ("bytesRead", "fileOpens", "negativeProbeRejections", "playbackForbiddenUriAttempts"),
            "onlineReady": ("playbackState", "renderedOutputBufferCount", "firstFrameEvents"),
            "offlineReady": ("playbackState", "renderedOutputBufferCount", "firstFrameEvents"),
        }.items() for field in fields]
        for path in paths:
            for suffix in (".0", ".0000000000000001"):
                with self.subTest(path=path, suffix=suffix):
                    fixture = json.loads(json.dumps(self.fixture()))
                    obj = fixture
                    for key in path[:-1]:
                        obj = obj[key]
                    token = str(obj[path[-1]]) + suffix
                    obj[path[-1]] = "UNIT_WIRE_NUMBER"
                    raw = json.dumps(fixture).replace('"UNIT_WIRE_NUMBER"', token).encode()
                    with self.assertRaises(ValueError):
                        validator(raw)

    def test_raw_native_numeric_tokens_rejected(self):
        validator = runpy.run_path(str(RUNNER))["validate_evidence"]
        for field, integer_token in (("size", "100"), ("height", "720")):
            for token in ("true", integer_token + ".0", integer_token + ".0000000000000001"):
                with self.subTest(field=field, token=token):
                    fixture = self.fixture()
                    reply = fixture["nativeResults"][0]
                    reply["rawResultJson"] = reply["rawResultJson"].replace(
                        '"' + field + '": ' + integer_token, '"' + field + '": ' + token)
                    with self.assertRaises(ValueError):
                        validator(json.dumps(fixture).encode())

    def test_boolean_identity_cannot_substitute_for_integer(self):
        validator = runpy.run_path(str(RUNNER))["validate_evidence"]
        fixture = self.fixture()
        row = fixture["nativeResults"][0]["candidates"][0]
        row["size"] = 1
        fixture["nativeResults"][0]["rawResultJson"] = json.dumps([row])
        for group, fields in {"film": ("expectedBytes",),
                              "downloadResult": ("bytesDownloaded", "totalBytes"),
                              "downloadIntegrity": ("fileBytes", "bytesHashed")}.items():
            for field in fields:
                fixture["observations"][group][field] = 1
        baseline = json.dumps(fixture)
        validator(baseline.encode())
        paths = [
            ("nativeResults", 0, "candidates", 0, "size"),
            ("observations", "resolve", "matchedNativeCandidate", "size"),
            ("observations", "film", "expectedBytes"),
            ("observations", "downloadResult", "bytesDownloaded"),
            ("observations", "downloadResult", "totalBytes"),
            ("observations", "downloadIntegrity", "fileBytes"),
            ("observations", "downloadIntegrity", "bytesHashed"),
            ("observations", "nativeResolution", "observedCallCount"),
            ("observations", "offlineTransport", "negativeProbeRejections"),
            ("observations", "offlineTransport", "playbackForbiddenUriAttempts"),
            ("observations", "onlineReady", "firstFrameEvents"),
        ]
        for path in paths:
            with self.subTest(path=path):
                obj = fixture = json.loads(baseline)
                for key in path[:-1]:
                    obj = obj[key]
                obj[path[-1]] = bool(obj[path[-1]])
                with self.assertRaises(ValueError):
                    validator(json.dumps(fixture).encode())

    def test_rounded_decimal_failure_preserves_wire_bytes(self):
        raw = json.dumps(self.fixture()).replace('"bytesDownloaded": 100',
                                                '"bytesDownloaded": 100.0000000000000001').encode()
        code, files, _ = self.run_runner(raw)
        self.assertNotEqual(code, 0)
        self.assertEqual(files["result.json"], raw)

    def test_lossless_json_allows_fractional_metadata(self):
        module = runpy.run_path(str(RUNNER))
        fixture = self.fixture()
        fixture["UNIT_optional_metric"] = 1.25
        module["validate_evidence"](json.dumps(fixture).encode())
        self.assertEqual(str(module["strict_json"]("100.0000000000000001")),
                         "100.0000000000000001")
        with self.assertRaises(ValueError):
            module["strict_json"]("1e999999999999999999999999999")

    def test_ambiguous_json_fails_closed(self):
        raw = json.dumps(self.fixture()).replace('"status": "PASSED"', '"status": "FAILED", "status": "PASSED"', 1).encode()
        code, _, _ = self.run_runner(raw)
        self.assertNotEqual(code, 0)

    def test_untrusted_archives_never_create_success(self):
        raw = json.dumps(self.fixture()).encode()
        cases = [b"not tar", archive(raw, duplicate=True), archive(raw, kind=tarfile.SYMTYPE),
                 archive(raw, kind=tarfile.LNKTYPE), archive(raw, name="../" + MEMBER),
                 archive(raw, name="/" + MEMBER), archive(raw, name="files/provider-e2e/wrong.json"),
                 archive(b""), archive(b"x" * (2 * 1024 * 1024 + 1))]
        for index, tar_bytes in enumerate(cases):
            with self.subTest(index=index):
                code, files, _ = self.run_runner(raw, tar_bytes=tar_bytes)
                self.assertNotEqual(code, 0)
                self.assertNotIn("result.json", files)

    def test_protocol_and_collection_errors_are_fatal(self):
        raw = json.dumps(self.fixture()).encode()
        cases = [{"protocol": b"OK (0 tests)"}, {"protocol": PROTOCOL * 2},
                 {"protocol": PROTOCOL + b"INSTRUMENTATION_STATUS_CODE: -3\n"},
                 {"protocol": PROTOCOL + b"INSTRUMENTATION_RESULT: shortMsg=crash\n"},
                 {"instrument_code": 1}, {"timeout": True}, {"cleanup_code": 1},
                 {"collection_fail": True}, {"collection_timeout": True}, {"missing_adb": True}]
        for options in cases:
            with self.subTest(options=options):
                code, files, calls = self.run_runner(raw, **options)
                self.assertNotEqual(code, 0)
                self.assertTrue(any("logcat" in call for call in calls))
                self.assertTrue(any("tar" in call for call in calls))
                if options.get("cleanup_code"):
                    self.assertFalse(any("instrument" in call for call in calls))
                if options.get("timeout"):
                    self.assertEqual(files["instrumentation.txt"], PROTOCOL)


if __name__ == "__main__":
    unittest.main()
