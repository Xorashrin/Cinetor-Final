"""Synthetic UNIT fixtures only; these tests are not Android containment evidence."""
import copy
import importlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parent
SCRATCH = Path.home() / ".hermes/cache/scratch"
STAGES = ["manifest", "probe_admission", "native_promise", "native_timeout", "native_oom", "native_stack",
          "native_output_limit", "private_file_denied", "socket_denied",
          "watchdog_death", "crash_death", "healthy_production_rebind"]
PROTOCOL = b"OK (1 test)\nINSTRUMENTATION_CODE: -1\n"


def unit_evidence():
    return {"test": "NativeContainmentTest", "status": "PASSED", "appUid": 10123,
            "appPid": 1234, "stages": [{"name": n, "status": "PASSED"} for n in STAGES],
            "observations": {"UNIT_ONLY": "synthetic, not device evidence"},
            "notVerified": ["UNIT fixture does not verify Android containment"]}


def unit_tar(entries):
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as bundle:
        for name, raw, kind in entries:
            member = tarfile.TarInfo(name)
            member.type = kind
            if kind == tarfile.REGTYPE:
                member.size = len(raw)
                bundle.addfile(member, io.BytesIO(raw))
            else:
                member.linkname = "../../UNIT-target"
                bundle.addfile(member)
    return stream.getvalue()


class NativeRunnerUnitTest(unittest.TestCase):
    def setUp(self):
        self.assertTrue((ROOT / "run_native_containment.py").is_file(),
                        "native containment runner is missing")
        self.runner = importlib.import_module("scripts.run_native_containment")
        SCRATCH.mkdir(parents=True, exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(prefix="UNIT-native-", dir=SCRATCH)
        self.addCleanup(self.tmp.cleanup)
        self.out = Path(self.tmp.name)
        self.raw = (json.dumps(unit_evidence(), indent=2) + "\n").encode()
        self.archive = unit_tar([("files/native-containment/result.json", self.raw, tarfile.REGTYPE)])
        self.calls = []

    def fake_adb(self, args, **kwargs):
        self.calls.append((args, kwargs))
        self.assertGreater(kwargs["timeout"], 0)
        self.assertLessEqual(kwargs["timeout"], 600)
        if "instrument" in args:
            return subprocess.CompletedProcess(args, 0, PROTOCOL)
        if "logcat" in args:
            kwargs["stdout"].write(b"UNIT synthetic logcat\n")
            return subprocess.CompletedProcess(args, 0, b"", b"")
        if "tar" in args:
            kwargs["stdout"].write(self.archive)
            return subprocess.CompletedProcess(args, 0, b"", b"")
        return subprocess.CompletedProcess(args, 0, b"", b"")

    def run_unit(self, fake=None):
        with patch.object(self.runner.subprocess, "run", side_effect=fake or self.fake_adb), \
                patch("sys.stdout", new_callable=io.StringIO), \
                patch("sys.stderr", new_callable=io.StringIO):
            return self.runner.main([str(self.out)])

    def test_unit_end_to_end_keeps_original_bytes_and_scoped_cleanup(self):
        (self.out / "result.json").write_bytes(b"UNIT stale result")
        self.assertEqual(self.run_unit(), 0)
        self.assertEqual((self.out / "result.json").read_bytes(), self.raw)
        self.assertEqual(self.calls[0][0], ["adb", "shell", "run-as", "com.cinator.app",
                                         "rm", "-rf", "files/native-containment"])
        instrumentation = self.calls[1][0]
        self.assertEqual(instrumentation[instrumentation.index("class") + 1],
                         "com.cinator.app.NativeContainmentTest")
        self.assertEqual((self.out / "instrumentation.txt").read_bytes(), PROTOCOL)
        self.assertTrue(any("logcat" in c[0] for c in self.calls))
        self.assertTrue(any("tar" in c[0] for c in self.calls))

    def test_unit_schema_rejects_missing_or_wrong_fields(self):
        base = unit_evidence()
        for key in base:
            data = copy.deepcopy(base)
            del data[key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                self.runner.validate_evidence(json.dumps(data).encode())
        for key, value in [("test", "OtherTest"), ("status", "FAILED"),
                           ("status", "RUNNING"), ("observations", {}),
                           ("observations", []), ("notVerified", "none"),
                           ("notVerified", [1]), ("failure", "UNIT failure")]:
            data = copy.deepcopy(base)
            data[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                self.runner.validate_evidence(json.dumps(data).encode())

    def test_unit_requires_exact_ten_unique_passing_stages(self):
        for stages in [[], None, {}, unit_evidence()["stages"][:-1],
                       unit_evidence()["stages"] + [{"name": "extra", "status": "PASSED"}],
                       unit_evidence()["stages"][:-1] + [unit_evidence()["stages"][0]]]:
            data = unit_evidence()
            data["stages"] = stages
            with self.subTest(stages=stages), self.assertRaises(ValueError):
                self.runner.validate_evidence(json.dumps(data).encode())
        for index in range(10):
            for mutation in [{"status": "FAILED"}, {"name": "unknown"},
                             {"exception": "UNIT failure"}, {"name": []}]:
                data = unit_evidence()
                data["stages"][index].update(mutation)
                with self.subTest(index=index, mutation=mutation), self.assertRaises(ValueError):
                    self.runner.validate_evidence(json.dumps(data).encode())

    def test_unit_identity_requires_exact_positive_integer_tokens(self):
        for key in ("appUid", "appPid"):
            for token in ("true", "false", "0", "-1", "null", '"123"',
                          "123.0", "123.000000000000000001", "1e2", "2147483648"):
                data = unit_evidence()
                data[key] = "UNIT_TOKEN"
                raw = json.dumps(data).replace('"UNIT_TOKEN"', token).encode()
                with self.subTest(key=key, token=token), self.assertRaises(ValueError):
                    self.runner.validate_evidence(raw)

    def test_unit_strict_json_rejects_duplicates_nonfinite_and_bad_input(self):
        for raw in [self.raw.replace(b'"appPid": 1234', b'"appPid": 1234, "appPid": 1234'),
                    self.raw.replace(b'"appPid": 1234', b'"appPid": NaN'),
                    self.raw.replace(b'"appPid": 1234', b'"appPid": Infinity'),
                    b"[]", b"null", self.raw + b"{}", b"\xff", b"[" * 2000,
                    b" " * (2 * 1024 * 1024 + 1)]:
            with self.subTest(raw=raw[:50]), self.assertRaises(ValueError):
                self.runner.validate_evidence(raw)

    def test_unit_optional_decimal_observation_is_preserved(self):
        raw = self.raw.replace(b'"observations": {', b'"observations": {"UNIT_metric": 1.000000000000000001,')
        self.runner.validate_evidence(raw)

    def test_unit_tar_rejects_unsafe_links_duplicates_and_unexpected_files(self):
        member = "files/native-containment/result.json"
        expected = (member, self.raw, tarfile.REGTYPE)
        for entries in [[], [(member, b"", tarfile.REGTYPE)],
                        [(member, b"x" * (2 * 1024 * 1024 + 1), tarfile.REGTYPE)],
                        [expected, expected], [expected, ("./" + member, self.raw, tarfile.REGTYPE)],
                        [(member, b"", tarfile.SYMTYPE)], [(member, b"", tarfile.LNKTYPE)],
                        [(member, b"", tarfile.FIFOTYPE)],
                        [("/" + member, self.raw, tarfile.REGTYPE)],
                        [("files/native-containment/../result.json", self.raw, tarfile.REGTYPE)],
                        [expected, ("files/native-containment/extra", b"UNIT", tarfile.REGTYPE)],
                        [("files/native-containment", b"", tarfile.DIRTYPE)] * 129 + [expected]]:
            path = self.out / "UNIT.tar"
            path.write_bytes(unit_tar(entries))
            with self.subTest(entries=len(entries)), self.assertRaises(ValueError):
                self.runner.read_device_result(path)

    def test_unit_tar_accepts_directory_and_dot_prefix_without_extracting(self):
        path = self.out / "UNIT.tar"
        path.write_bytes(unit_tar([("files", b"", tarfile.DIRTYPE),
                                   ("files/native-containment", b"", tarfile.DIRTYPE),
                                   ("./files/native-containment/result.json", self.raw, tarfile.REGTYPE)]))
        self.assertEqual(self.runner.read_device_result(path), self.raw)
        self.assertFalse((self.out / "files").exists())

    def test_unit_protocol_failures_still_collect_but_never_succeed(self):
        for protocol, code in [(b"", 0), (b"OK (1 test)\n", 0),
                               (PROTOCOL, 1), (PROTOCOL + PROTOCOL, 0),
                               (b"OK (2 tests)\nINSTRUMENTATION_CODE: -1\n", 0),
                               (b"FAILURES!!!\n" + PROTOCOL, 0),
                               (b"INSTRUMENTATION_STATUS_CODE: -2\n" + PROTOCOL, 0)]:
            self.calls.clear()
            def fake(args, **kwargs):
                if "instrument" in args:
                    return subprocess.CompletedProcess(args, code, protocol)
                return self.fake_adb(args, **kwargs)
            with self.subTest(protocol=protocol, code=code):
                self.assertEqual(self.run_unit(fake), 1)
                self.assertTrue(any("logcat" in c[0] for c in self.calls))
                self.assertTrue(any("tar" in c[0] for c in self.calls))

    def test_unit_cleanup_failure_prevents_instrumentation_but_collects(self):
        def fake(args, **kwargs):
            if "rm" in args:
                return subprocess.CompletedProcess(args, 1, b"", b"UNIT denied")
            self.assertNotIn("instrument", args)
            return self.fake_adb(args, **kwargs)
        self.assertEqual(self.run_unit(fake), 1)
        self.assertTrue(any("tar" in c[0] for c in self.calls))

    def test_unit_timeout_retains_partial_protocol_and_collects(self):
        def fake(args, **kwargs):
            if "instrument" in args:
                raise subprocess.TimeoutExpired(args, kwargs["timeout"], output=b"UNIT partial\n")
            return self.fake_adb(args, **kwargs)
        self.assertEqual(self.run_unit(fake), 1)
        self.assertEqual((self.out / "instrumentation.txt").read_bytes(), b"UNIT partial\n")
        self.assertTrue(any("tar" in c[0] for c in self.calls))

    def test_unit_missing_adb_removes_stale_json_and_fails(self):
        (self.out / "result.json").write_bytes(b"UNIT stale")
        (self.out / "private-evidence.tar").write_bytes(self.archive)
        calls = []
        def missing(args, **kwargs):
            calls.append(args)
            raise FileNotFoundError("UNIT missing adb")
        self.assertEqual(self.run_unit(missing), 1)
        self.assertFalse((self.out / "result.json").exists())
        self.assertTrue(any("logcat" in c for c in calls))
        self.assertTrue(any("tar" in c for c in calls))

    def test_unit_each_collection_failure_is_fatal_and_other_collection_runs(self):
        for target in ("tar", "logcat"):
            for mode in ("exit", "timeout"):
                self.calls.clear()
                def fake(args, **kwargs):
                    if target in args:
                        if mode == "timeout":
                            raise subprocess.TimeoutExpired(args, kwargs["timeout"])
                        return subprocess.CompletedProcess(args, 1, b"", b"UNIT denied")
                    return self.fake_adb(args, **kwargs)
                with self.subTest(target=target, mode=mode):
                    self.assertEqual(self.run_unit(fake), 1)
                    other = "logcat" if target == "tar" else "tar"
                    self.assertTrue(any(other in c[0] for c in self.calls))

    def test_unit_failed_device_json_kept_verbatim_without_success(self):
        raw = self.raw.replace(b'"status": "PASSED"', b'"status": "FAILED"', 1)
        self.archive = unit_tar([("files/native-containment/result.json", raw, tarfile.REGTYPE)])
        self.assertEqual(self.run_unit(), 1)
        self.assertEqual((self.out / "result.json").read_bytes(), raw)


if __name__ == "__main__":
    unittest.main()
