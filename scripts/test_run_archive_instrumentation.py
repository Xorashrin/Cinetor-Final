"""Synthetic UNIT fixtures only; never Android execution evidence."""
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from typing import Any
sys.path.insert(0, str(Path(__file__).resolve().parent))

class ArchiveRunnerTest(unittest.TestCase):
    def runner(self):
        path = Path(__file__).resolve().with_name("run_archive_instrumentation.py")
        self.assertTrue(path.exists(), "Archive runner is not implemented")
        import run_archive_instrumentation
        return run_archive_instrumentation

    def test_exact_regular_member_and_original_bytes(self):
        runner = self.runner()
        raw = b'{ "status": "FAILED", "unitOnly": true }\n'
        for name, kind, duplicate, valid in [
            ("files/archive-e2e/result.json", tarfile.REGTYPE, False, True),
            ("files/archive-e2e/other.json", tarfile.REGTYPE, False, False),
            ("files/archive-e2e/result.json", tarfile.SYMTYPE, False, False),
            ("files/archive-e2e/result.json", tarfile.REGTYPE, True, False),
            ("../result.json", tarfile.REGTYPE, False, False),
        ]:
            with self.subTest(name=name, kind=kind, duplicate=duplicate), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / "unit.tar"
                with tarfile.open(path, "w") as bundle:
                    for _ in range(2 if duplicate else 1):
                        item = tarfile.TarInfo(name); item.type = kind
                        item.size = len(raw) if kind == tarfile.REGTYPE else 0
                        item.linkname = "UNIT-not-a-target"
                        bundle.addfile(item, io.BytesIO(raw) if item.size else None)
                if valid:
                    self.assertEqual(runner.read_device_result(path), raw)
                else:
                    with self.assertRaises(ValueError): runner.read_device_result(path)

    def test_test_only_transport_scope_is_explicit(self):
        r = self.runner()
        label = "Production playback transport/redirect policy"
        self.assertIn(label, r.NOT_VERIFIED)
        self.assertIn("test-only DefaultHttpDataSource playback transport", r.SCOPE)
        self.assertIn(label, self.fixture()["notVerified"])
        android = (Path(__file__).resolve().parents[1] / "app/src/androidTest/java/com/cinator/app/InternetArchivePlaybackTest.kt").read_text()
        self.assertIn('"' + label + '"', android)
        self.assertIn(r.SCOPE, android)
        value = self.fixture()
        value["notVerified"] = [item for item in value["notVerified"] if item != label]
        with self.assertRaises(ValueError): r.validate_evidence(json.dumps(value).encode())

    def test_empty_pass_claim_is_rejected(self):
        runner = self.runner()
        with self.assertRaises(ValueError):
            runner.validate_evidence(b'{"test":"InternetArchivePlaybackTest","status":"PASSED"}')

    def fixture(self) -> dict[str, Any]:
        """Synthetic validator fixture, not a device result."""
        r = self.runner()
        def snap(position=1000, elapsed=1000, playing=True, frames=30, seeks=0):
            return dict(elapsedRealtimeMs=elapsed, playbackState=3, positionMs=position,
                        durationMs=596416, renderedOutputBufferCount=frames, firstFrameEvents=1,
                        seekDiscontinuities=seeks, height=240, mime="video/avc", playerErrorCode=None,
                        surfaceViewShown=True, isPlaying=playing, playWhenReady=playing)
        path = "/data/user/0/com.cinator.app/files/downloads/v2/home/UNIT.mp4"
        identity = dict(streamId=r.STREAM, sourceUrl=r.URL)
        o: dict[str, Any] = dict(
            film=dict(itemId=r.ITEM, contentId=r.CONTENT, providerId=r.PROVIDER, streamId=r.STREAM,
                      url=r.URL, fileName=r.FILE_NAME, expectedBytes=r.SIZE, expectedDurationMs=596416,
                      expectedSha256=r.SHA256, pinProvenance=r.PIN),
            catalog=dict(selectedId=r.CONTENT, contentId=r.CONTENT, providerId=r.PROVIDER),
            resolve=dict(streamId=r.STREAM, contentId=r.CONTENT, providerId=r.PROVIDER, url=r.URL,
                         sourceCount=1, metadataFile=dict(name=r.FILE_NAME, size=str(r.SIZE))),
            onlineTransport=dict(**identity, bytesRead=10000),
            downloadResult=dict(**identity, status="COMPLETED", contentId=r.CONTENT, providerId=r.PROVIDER,
                                progressCallbackCount=2, totalBytes=r.SIZE, bytesDownloaded=r.SIZE, localFile=path),
            downloadIntegrity=dict(fileBytes=r.SIZE, bytesHashed=r.SIZE, sha256=r.SHA256,
                                   expectedSha256=r.SHA256, fullHashPinStatus="MATCHED", pinProvenance=r.PIN, localFile=path),
            offlineTransport=dict(**identity, localFile=path, bytesRead=1000, fileOpens=2,
                                  implementation="FileDataSource only; exact canonical file", deviceNetworkDisabled=False,
                                  negativeProbeRejections=2, playbackForbiddenUriAttempts=0))
        for mode in ("online", "offline"):
            o[mode + "Ready"] = snap()
            o[mode + "Advance"] = dict(before=snap(), after=snap(3000, 3000, frames=60))
            o[mode + "Pause"] = dict(before=snap(3000, 4000, False), after=snap(3000, 5500, False))
            o[mode + "SeekResume"] = dict(targetMs=30000, before=snap(3000, 6000, False),
                                          sought=snap(30000, 7000, False, seeks=1),
                                          resumed=snap(32000, 9000, True, frames=60, seeks=1))
        return dict(status="PASSED", test="InternetArchivePlaybackTest", schemaVersion=1, scope=r.SCOPE,
                    notVerified=["Default provider activation", "Rights/credits review", "App navigation/player chrome",
                                 "Device-wide offline mode", "Audible audio", "Every frame/end credits",
                                 "Production playback transport/redirect policy"],
                    elapsedMs=20000, sdkInt=35, observations=o,
                    stages=[dict(name=name, status="PASSED") for name in sorted(r.REQUIRED_STAGES)],
                    httpRequests=[dict(url="https://archive.org/metadata/" + r.ITEM, outcome="RESPONSE",
                                       identifier=r.ITEM, utf8Bytes=2000, metadataSha256="a"*64) for _ in range(5)])

    def protocol(self):
        r = self.runner()
        rows = []
        for code in (1, 0):
            rows.extend(["INSTRUMENTATION_STATUS: class=" + r.TEST_CLASS,
                         "INSTRUMENTATION_STATUS: test=" + r.TEST_METHOD,
                         "INSTRUMENTATION_STATUS: numtests=1", "INSTRUMENTATION_STATUS: current=1",
                         "INSTRUMENTATION_STATUS_CODE: " + str(code)])
        return "\n".join(rows) + "\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n"

    def test_positive_control_and_adversarial_mutations(self):
        import copy
        r = self.runner()
        value = self.fixture()
        r.validate_evidence(json.dumps(value).encode())
        edits = [
            (("scope",), "wrong"), (("test",), "ProviderPlaybackE2ETest"), (("status",), "RUNNING"),
            (("schemaVersion",), True), (("observations", "film", "expectedBytes"), True),
            (("observations", "downloadIntegrity", "sha256"), "0"*64),
            (("observations", "downloadIntegrity", "pinProvenance"), "publisher hash"),
            (("observations", "offlineTransport", "localFile"), "/different.mp4"),
            (("observations", "offlineTransport", "playbackForbiddenUriAttempts"), 1),
            (("observations", "resolve", "contentId"), "wrong:namespace"),
            (("observations", "onlineReady", "renderedOutputBufferCount"), 0),
            (("observations", "offlineSeekResume", "resumed", "positionMs"), 1000),
            (("stages",), []), (("httpRequests",), []),
        ]
        for keys, replacement in edits:
            with self.subTest(keys=keys):
                broken = copy.deepcopy(value)
                row: Any = broken
                for key in keys[:-1]: row = row[key]
                row[keys[-1]] = replacement
                with self.assertRaises(ValueError): r.validate_evidence(json.dumps(broken).encode())
        raw = json.dumps(value).replace('"expectedBytes": 43315070', '"expectedBytes": 43315070.000000001')
        with self.assertRaises(ValueError): r.validate_evidence(raw.encode())

    def test_snapshot_playing_consistency_and_chronology(self):
        import copy
        r = self.runner()
        value = self.fixture()
        r.validate_evidence(json.dumps(value).encode())
        for mode in ("online", "offline"):
            playing_paths = [(mode + "Ready",), (mode + "Advance", "before"),
                             (mode + "Advance", "after"), (mode + "SeekResume", "resumed")]
            for path in playing_paths:
                for field, invalid in (("playbackState", 1), ("playWhenReady", False)):
                    with self.subTest(path=path, field=field):
                        broken = copy.deepcopy(value)
                        row = broken["observations"]
                        for key in path: row = row[key]
                        row[field] = invalid
                        with self.assertRaises(ValueError): r.validate_evidence(json.dumps(broken).encode())
            for group, first, second in (("Advance", "before", "after"),
                                         ("SeekResume", "before", "sought"),
                                         ("SeekResume", "sought", "resumed")):
                for delta in (0, -1):
                    with self.subTest(mode=mode, group=group, second=second, delta=delta):
                        broken = copy.deepcopy(value)
                        row = broken["observations"][mode + group]
                        row[second]["elapsedRealtimeMs"] = row[first]["elapsedRealtimeMs"] + delta
                        with self.assertRaises(ValueError): r.validate_evidence(json.dumps(broken).encode())
            broken = copy.deepcopy(value)
            broken["observations"][mode + "Advance"]["after"].update(
                playbackState=1, isPlaying=True, playWhenReady=False, elapsedRealtimeMs=0)
            with self.subTest(mode=mode, reproduced=True), self.assertRaises(ValueError):
                r.validate_evidence(json.dumps(broken).encode())

    def test_protocol_requires_exact_test_and_count(self):
        r = self.runner(); protocol = self.protocol()
        r.validate_instrumentation(protocol, 0)
        for invalid in ("OK (1 test)\nINSTRUMENTATION_CODE: -1\n",
                        protocol.replace(r.TEST_CLASS, "wrong.Class"),
                        protocol.replace("numtests=1", "numtests=2"),
                        protocol.replace("STATUS_CODE: 0", "STATUS_CODE: -2")):
            with self.subTest(protocol=invalid), self.assertRaises(ValueError):
                r.validate_instrumentation(invalid, 0)

    def test_protocol_rejects_unfinished_or_misordered_records(self):
        r = self.runner()
        protocol = self.protocol()
        summary = protocol.replace("OK (1 test)",
            "INSTRUMENTATION_RESULT: stream=\n\nTime: 1.234\n\nOK (1 test)")
        summary = summary.replace("INSTRUMENTATION_STATUS: class=",
            "INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\nINSTRUMENTATION_STATUS: stream=.\nINSTRUMENTATION_STATUS: class=")
        r.validate_instrumentation(summary, 0)
        terminal = "INSTRUMENTATION_CODE: -1\n"
        start_end = protocol.index("INSTRUMENTATION_STATUS_CODE: 1\n") + len("INSTRUMENTATION_STATUS_CODE: 1\n")
        cases = {
            "trailing_identity": protocol + "INSTRUMENTATION_STATUS: class=wrong\n",
            "trailing_unknown_status": protocol + "INSTRUMENTATION_STATUS: stream=.\n",
            "trailing_status_code": protocol + "INSTRUMENTATION_STATUS_CODE: 0\n",
            "trailing_result": protocol + "INSTRUMENTATION_RESULT: stream=extra\n",
            "unfinished_identity": protocol.replace(terminal, "INSTRUMENTATION_STATUS: class=wrong\n" + terminal),
            "unfinished_unknown_status": protocol.replace(terminal, "INSTRUMENTATION_STATUS: stream=.\n" + terminal),
            "terminal_first": terminal + protocol.replace(terminal, ""),
            "terminal_between_events": protocol[:start_end] + terminal + protocol[start_end:].replace(terminal, ""),
            "terminal_inside_event": protocol.replace("INSTRUMENTATION_STATUS_CODE: 0\n", terminal + "INSTRUMENTATION_STATUS_CODE: 0\n").removesuffix(terminal),
            "duplicate_run": protocol + protocol,
            "trailing_start": protocol + protocol[:start_end],
        }
        for name, invalid in cases.items():
            with self.subTest(case=name), self.assertRaises(ValueError):
                r.validate_instrumentation(invalid, 0)

    def test_runner_preserves_failed_json_and_targets_exact_member(self):
        import contextlib
        import subprocess
        from unittest.mock import patch
        r = self.runner()
        raw = b'{ "test": "InternetArchivePlaybackTest", "status": "FAILED" }\n'
        calls = []
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp)
            (out / "result.json").write_bytes(b"STALE")
            def adb(args, **kwargs):
                calls.append(args)
                self.assertGreater(kwargs["timeout"], 0)
                payload = b""
                if "instrument" in args: payload = self.protocol().encode()
                if "tar" in args:
                    self.assertEqual(args[-1], r.RESULT_MEMBER)
                    buffer = io.BytesIO()
                    with tarfile.open(fileobj=buffer, mode="w") as bundle:
                        item = tarfile.TarInfo(r.RESULT_MEMBER); item.size = len(raw)
                        bundle.addfile(item, io.BytesIO(raw))
                    payload = buffer.getvalue()
                sink = kwargs.get("stdout")
                if sink is not None and hasattr(sink, "write"): sink.write(payload); payload = b""
                return subprocess.CompletedProcess(args, 0, payload, b"")
            with patch("subprocess.run", side_effect=adb), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(r.main([str(out)]), 1)
            self.assertEqual((out / "result.json").read_bytes(), raw)
            self.assertEqual(calls[0][-1], r.DEVICE_DIRECTORY)

if __name__ == "__main__": unittest.main()
