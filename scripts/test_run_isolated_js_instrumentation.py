"""Host-side validation tests, not Android E2E evidence."""
import importlib.util
import pathlib
import io
import json
import tarfile
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().with_name("run_isolated_js_instrumentation.py")


class InstrumentationResultTest(unittest.TestCase):
    def test_only_exactly_one_successful_test_is_accepted(self):
        self.assertTrue(SCRIPT.is_file(), "real instrumentation runner is missing")
        spec = importlib.util.spec_from_file_location("isolated_runner", SCRIPT)
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        runner.validate_instrumentation("INSTRUMENTATION_CODE: -1\nOK (1 test)\n", 0)
        for text, code in [
            ("OK (0 tests)", 0), ("OK (2 tests)", 0), ("", 0),
            ("FAILURES!!!\nOK (1 test)", 0),
            ("INSTRUMENTATION_FAILED: crashed\nOK (1 test)", 0),
            ("INSTRUMENTATION_STATUS_CODE: -2\nOK (1 test)", 0),
            ("OK (1 test)", 1),
            ("OK (1 test)", 0),
            ("OK (1 test)\nINSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: -1", 0),
            ("OK (1 test)\nOK (2 tests)", 0),
        ]:
            with self.subTest(text=text, code=code):
                with self.assertRaises(ValueError):
                    runner.validate_instrumentation(text, code)


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("isolated_runner", SCRIPT)
        self.runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.runner)

    def test_device_status_must_be_passed(self):
        self.runner.validate_evidence(b'{"status":"PASSED","test":"IsolatedQuickJsTest"}')
        for data in [{}, {"status": "FAILED"}, {"status": "RUNNING"},
                     {"status": "PASSED", "test": "OtherTest"}, []]:
            with self.subTest(data=data):
                with self.assertRaises(ValueError):
                    self.runner.validate_evidence(json.dumps(data).encode())

    def test_archive_bytes_preserved_without_extracting_paths(self):
        # Unit fixture only: never written to an E2E output directory.
        raw = b'{"status":"FAILED","test":"IsolatedQuickJsTest"}\n'
        scratch = pathlib.Path.home() / ".hermes/cache/scratch"
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as folder:
            archive = pathlib.Path(folder) / "fixture.tar"
            with tarfile.open(archive, "w") as bundle:
                member = tarfile.TarInfo("files/isolated-js-e2e/result.json")
                member.size = len(raw)
                bundle.addfile(member, io.BytesIO(raw))
            self.assertEqual(raw, self.runner.read_device_result(archive))
            with tarfile.open(archive, "w") as bundle:
                member = tarfile.TarInfo("files/isolated-js-e2e/result.json")
                member.type = tarfile.SYMTYPE
                member.linkname = "../../untrusted"
                bundle.addfile(member)
            with self.assertRaises(ValueError):
                self.runner.read_device_result(archive)


if __name__ == "__main__":
    unittest.main()
