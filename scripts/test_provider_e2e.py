"""TEST-ONLY synthetic evidence: never real provider/playback proof."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from typing import Any
import unittest

SCRIPT = Path(__file__).with_name("provider_e2e.py")
STAGES = ("catalog", "search", "details", "resolve", "stream_transport", "android_play",
          "android_pause", "android_seek", "download", "offline_play")


class EvidenceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="test-only-provider-e2e-",
                                             dir=os.environ.get("TMPDIR"))
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def report(self):
        case = dict(providerId="test-only-provider", version="fixture-v1",
                    mediaId="fixture-media", contentId="fixture-content")
        case["stages"] = {s: {"status": "not_tested", "evidence": []} for s in STAGES}
        return {"schemaVersion": 1, "evidenceKind": "test_only", "providers": [case]}

    def run_report(self, report):
        path = self.root / "report.json"
        path.write_text(json.dumps(report), encoding="utf-8")
        proc = subprocess.run([sys.executable, str(SCRIPT), "--report", str(path)],
                              text=True, capture_output=True)
        self.assertTrue(proc.stdout.strip(), proc.stderr)
        return proc.returncode, json.loads(proc.stdout)

    def test_not_tested_report_is_valid_but_not_accepted(self):
        code, result = self.run_report(self.report())
        self.assertEqual(code, 1)
        self.assertTrue(result["valid"])
        self.assertFalse(result["accepted"])
        self.assertEqual(result["summary"]["playback_verified"], 0)
        self.assertEqual(result["summary"]["case_count"], 1)

    def pass_stage(self, report, stage, kind=None):
        case = report["providers"][0]
        kinds = {"stream_transport": "transport_probe", "download": "android_download",
                 "offline_play": "android_offline_playback"}
        kind = kind or kinds.get(stage, "android_instrumentation" if stage.startswith("android_")
                                 else "provider_probe")
        path = self.root / (stage + ".test-only.txt")
        path.write_text("TEST-ONLY SYNTHETIC FIXTURE; NOT REAL EVIDENCE", encoding="utf-8")
        evidence = {k: case[k] for k in ("providerId", "version", "mediaId", "contentId")}
        evidence.update(path=path.name, kind=kind)
        case["stages"][stage] = {"status": "passed", "evidence": [evidence]}

    def test_transport_cannot_count_as_playback(self):
        report = self.report()
        for stage in STAGES[:5]:
            self.pass_stage(report, stage)
        _, result = self.run_report(report)
        self.assertTrue(result["valid"])
        self.assertEqual(result["summary"]["stage_counts"]["stream_transport"]["passed"], 1)
        self.assertEqual(result["summary"]["playback_verified"], 0)
        self.assertFalse(result["accepted"])

    def test_pass_requires_existing_nonempty_identity_bound_evidence(self):
        for change in ("missing", "empty", "directory", "identity", "traversal", "kind", "no_evidence"):
            with self.subTest(change=change):
                report = self.report()
                self.pass_stage(report, "android_play")
                record = report["providers"][0]["stages"]["android_play"]
                ev = record["evidence"][0]
                if change == "missing":
                    ev["path"] = "does-not-exist"
                elif change == "empty":
                    (self.root / ev["path"]).write_text("")
                elif change == "directory":
                    ev["path"] = "."
                elif change == "identity":
                    ev["contentId"] = "other-content"
                elif change == "traversal":
                    ev["path"] = "../unrelated-evidence"
                elif change == "kind":
                    ev["kind"] = "transport_probe"
                else:
                    record["evidence"] = []
                code, result = self.run_report(report)
                self.assertEqual(code, 2)
                self.assertFalse(result["valid"])
                self.assertFalse(result["accepted"])
                self.assertEqual(result["summary"]["playback_verified"], 0)

    def test_all_synthetic_passes_are_not_real_verification(self):
        report = self.report()
        for stage in STAGES:
            self.pass_stage(report, stage)
        code, result = self.run_report(report)
        self.assertEqual(code, 1)
        self.assertTrue(result["valid"])
        self.assertFalse(result["accepted"])
        self.assertEqual(result["summary"]["playback_verified"], 0)
        self.assertEqual(result["summary"]["offline_verified"], 0)

    def test_rejects_malformed_and_inflated_reports(self):
        reports = [None, [], {}, {"schemaVersion": 1, "evidenceKind": "real", "providers": []}]
        for change in ("stage", "status", "id", "version", "duplicates", "summary", "claim", "schema", "evidence"):
            report = self.report()
            case = report["providers"][0]
            if change == "stage":
                del case["stages"]["android_seek"]
            elif change == "status":
                case["stages"]["android_play"]["status"] = "verified"
            elif change == "id":
                case["providerId"] = "   "
            elif change == "version":
                case["version"] = 4
            elif change == "duplicates":
                report["providers"].append(case.copy())
            elif change == "summary":
                report["summary"] = {"playback_verified": 99}
            elif change == "claim":
                report["playback_verified"] = True
            elif change == "schema":
                report["schemaVersion"] = True
            else:
                case["stages"]["catalog"]["evidence"] = "not-a-list"
            reports.append(report)
        for report in reports:
            with self.subTest(report=report):
                code, result = self.run_report(report)
                self.assertEqual(code, 2)
                self.assertFalse(result["valid"])

    def test_missing_and_invalid_json_are_machine_readable(self):
        path = self.root / "bad.json"
        for content in (None, "{broken", '{"schemaVersion":1,"schemaVersion":2}', '{"x":NaN}'):
            if content is not None:
                path.write_text(content, encoding="utf-8")
            proc = subprocess.run([sys.executable, str(SCRIPT), "--report", str(path)],
                                  text=True, capture_output=True)
            self.assertEqual(proc.returncode, 2)
            result = json.loads(proc.stdout)
            self.assertFalse(result["valid"])
            self.assertTrue(result["errors"])

    def test_failed_and_unsupported_remain_unaccepted(self):
        for status in ("failed", "unsupported"):
            report = self.report()
            report["providers"][0]["stages"]["android_play"]["status"] = status
            code, result = self.run_report(report)
            self.assertEqual(code, 1)
            self.assertTrue(result["valid"])
            self.assertEqual(result["summary"]["stage_counts"]["android_play"][status], 1)
            self.assertFalse(result["accepted"])

    def test_acceptance_and_counts_in_memory_only(self):
        # TEST-ONLY branch simulation. Never write/print a successful "real" report.
        import copy
        import importlib.util
        spec = importlib.util.spec_from_file_location("provider_e2e_test_subject", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        report = self.report()
        report["notes"] = "TEST-ONLY in-memory branch simulation; not real playback evidence"
        report["evidenceKind"] = "real"
        for stage in STAGES[:5]:
            self.pass_stage(report, stage)
        result = module.validate_report(report, self.root)
        self.assertTrue(result["valid"])
        self.assertEqual(result["summary"]["transport_verified"], 1)
        self.assertEqual(result["summary"]["playback_verified"], 0)
        for stage in STAGES[5:8]:
            self.pass_stage(report, stage)
        result = module.validate_report(report, self.root)
        self.assertEqual(result["summary"]["playback_verified"], 1)
        self.assertFalse(result["accepted"])
        self.pass_stage(report, "download")
        result = module.validate_report(report, self.root)
        self.assertEqual(result["summary"]["offline_verified"], 0)
        self.pass_stage(report, "offline_play")
        result = module.validate_report(report, self.root)
        self.assertTrue(result["accepted"])
        self.assertEqual(result["summary"]["e2e_verified"], 1)
        # A second media case must not inflate the provider count or hide a gap.
        second = copy.deepcopy(report["providers"][0])
        second["mediaId"] = "second-test-only-media"
        for record in second["stages"].values():
            for ev in record["evidence"]:
                ev["mediaId"] = second["mediaId"]
        report["providers"].append(second)
        result = module.validate_report(report, self.root)
        self.assertEqual(result["summary"]["case_count"], 2)
        self.assertEqual(result["summary"]["provider_count"], 1)
        self.assertEqual(result["summary"]["playback_verified"], 1)
        second["stages"]["android_seek"] = {"status": "not_tested", "evidence": []}
        result = module.validate_report(report, self.root)
        self.assertEqual(result["summary"]["playback_verified"], 0)
        self.assertEqual(result["summary"]["e2e_verified"], 0)
        self.assertFalse(result["accepted"])

    def test_rejects_absolute_symlink_and_special_file_evidence(self):
        outside = tempfile.TemporaryDirectory(prefix="test-only-outside-", dir=self.root.parent)
        self.addCleanup(outside.cleanup)
        target = Path(outside.name) / "test-only.txt"
        target.write_text("TEST-ONLY", encoding="utf-8")
        (self.root / "link").symlink_to(target)
        os.mkfifo(self.root / "pipe")
        for path in (str(target), "link", "pipe"):
            report = self.report()
            self.pass_stage(report, "catalog")
            report["providers"][0]["stages"]["catalog"]["evidence"][0]["path"] = path
            code, result = self.run_report(report)
            self.assertEqual(code, 2)
            self.assertFalse(result["valid"])

    def test_wrong_json_types_fail_closed_without_tracebacks(self):
        import copy
        import runpy
        validate = runpy.run_path(str(SCRIPT))["validate_report"]
        template = self.report()
        self.pass_stage(template, "catalog")
        locations = (("evidenceKind",), ("providers",), ("providers", 0),
                     ("providers", 0, "providerId"), ("providers", 0, "stages"),
                     ("providers", 0, "stages", "catalog"),
                     ("providers", 0, "stages", "catalog", "status"),
                     ("providers", 0, "stages", "catalog", "evidence"),
                     ("providers", 0, "stages", "catalog", "evidence", 0),
                     ("providers", 0, "stages", "catalog", "evidence", 0, "path"),
                     ("providers", 0, "stages", "catalog", "evidence", 0, "kind"))
        for location in locations:
            for value in (None, True, 1, [], {}):
                with self.subTest(location=location, value=value):
                    report = copy.deepcopy(template)
                    node: Any = report
                    for key in location[:-1]:
                        node = node[key]
                    node[location[-1]] = value
                    result = validate(report, self.root)
                    self.assertFalse(result["valid"])
                    self.assertFalse(result["accepted"])
                    self.assertEqual(result["summary"]["playback_verified"], 0)


    def test_android_stages_require_exact_identity_and_stage_kind(self):
        # Fixtures exercise provenance validation, not real Android execution.
        for stage in STAGES[5:]:
            for field in ("providerId", "version", "mediaId", "contentId", "kind"):
                for mutation in ("missing", "different"):
                    with self.subTest(stage=stage, field=field, mutation=mutation):
                        report = self.report()
                        self.pass_stage(report, stage)
                        artifact = report["providers"][0]["stages"][stage]["evidence"][0]
                        if mutation == "missing":
                            del artifact[field]
                        else:
                            artifact[field] = "different-card-content-or-provenance"
                        code, result = self.run_report(report)
                        self.assertEqual(code, 2)
                        self.assertFalse(result["valid"])
                        self.assertFalse(result["accepted"])
                        for metric in ("transport_verified", "playback_verified",
                                       "offline_verified", "e2e_verified"):
                            self.assertEqual(result["summary"][metric], 0)

    def test_nonpassed_stages_do_not_bypass_artifact_validation(self):
        for status in ("failed", "unsupported", "not_tested"):
            with self.subTest(status=status):
                report = self.report()
                self.pass_stage(report, "android_seek")
                record = report["providers"][0]["stages"]["android_seek"]
                record["status"] = status
                code, result = self.run_report(report)
                self.assertEqual(code, 1)
                self.assertTrue(result["valid"])
                record["evidence"][0]["path"] = "uncollected-android-evidence"
                code, result = self.run_report(report)
                self.assertEqual(code, 2)
                self.assertFalse(result["valid"])
                self.assertFalse(result["accepted"])

    def test_no_episode_is_not_an_extra_required_stage_or_a_pass_status(self):
        # Film/no-episode cases still use all ten named stages. An unavailable
        # action uses unsupported/not_tested, never a novel successful status.
        report = self.report()
        report["providers"][0]["notes"] = "TEST-ONLY film fixture; no episodes"
        code, result = self.run_report(report)
        self.assertEqual(code, 1)
        self.assertTrue(result["valid"])
        report["providers"][0]["stages"]["resolve"]["status"] = "noepisode"
        code, result = self.run_report(report)
        self.assertEqual(code, 2)
        self.assertFalse(result["valid"])
        report["providers"][0]["stages"]["resolve"]["status"] = "unsupported"
        report["providers"][0]["stages"]["episodes"] = {"status": "unsupported", "evidence": []}
        code, result = self.run_report(report)
        self.assertEqual(code, 2)
        self.assertFalse(result["valid"])

    def test_distinct_cards_content_and_versions_do_not_inflate_counts_in_memory(self):
        import copy
        import runpy
        validate = runpy.run_path(str(SCRIPT))["validate_report"]
        template = self.report()
        for stage in STAGES:
            self.pass_stage(template, stage)
        # Branch simulation only: NEVER serialize or present this as real proof.
        template["evidenceKind"] = "real"
        template["notes"] = "TEST-ONLY in-memory aggregation simulation"
        for identity_field in ("mediaId", "contentId", "version"):
            for stage in STAGES:
                for status in ("not_tested", "failed", "unsupported"):
                    with self.subTest(identity_field=identity_field, stage=stage, status=status):
                        report = copy.deepcopy(template)
                        second = copy.deepcopy(report["providers"][0])
                        second[identity_field] = "different-test-only-" + identity_field
                        for record in second["stages"].values():
                            for artifact in record["evidence"]:
                                artifact[identity_field] = second[identity_field]
                        report["providers"].append(second)
                        result = validate(report, self.root)
                        self.assertTrue(result["valid"])
                        self.assertEqual(result["summary"]["case_count"], 2)
                        self.assertEqual(result["summary"]["provider_count"], 1)
                        self.assertEqual(result["summary"]["e2e_verified"], 1)
                        second["stages"][stage] = {"status": status, "evidence": []}
                        result = validate(report, self.root)
                        self.assertTrue(result["valid"])
                        self.assertFalse(result["accepted"])
                        self.assertEqual(result["summary"]["e2e_verified"], 0)
                        for metric, required in (("transport_verified", STAGES[:5]),
                                                 ("playback_verified", STAGES[:8]),
                                                 ("offline_verified", STAGES)):
                            self.assertEqual(result["summary"][metric], int(stage not in required))
                        other = copy.deepcopy(template["providers"][0])
                        other["providerId"] = "other-test-only-provider"
                        for record in other["stages"].values():
                            for artifact in record["evidence"]:
                                artifact["providerId"] = other["providerId"]
                        report["providers"].append(other)
                        result = validate(report, self.root)
                        self.assertEqual(result["summary"]["case_count"], 3)
                        self.assertEqual(result["summary"]["provider_count"], 2)
                        self.assertEqual(result["summary"]["e2e_verified"], 1)
                        self.assertFalse(result["accepted"])

    def test_synthetic_real_label_only_simulates_contract_not_runtime_truth(self):
        import runpy
        validate = runpy.run_path(str(SCRIPT))["validate_report"]
        report = self.report()
        for stage in STAGES:
            self.pass_stage(report, stage)
        result = validate(report, self.root)
        self.assertTrue(result["valid"])
        self.assertFalse(result["accepted"])
        for metric in ("transport_verified", "playback_verified", "offline_verified", "e2e_verified"):
            self.assertEqual(result["summary"][metric], 0)
        # Intentionally demonstrates the declared-provenance trust boundary,
        # not an authenticator. No successful real report is written or printed.
        report["evidenceKind"] = "real"
        result = validate(report, self.root)
        self.assertTrue(result["accepted"])
        self.assertIn("artifact contract", result["acceptance_reason"])
        self.assertIn("declared provenance only", result["verification_scope"])
        self.assertIn("not independent playback authentication", result["verification_scope"])
        self.assertEqual(result["summary"]["stage_counts_basis"], "declared statuses per supplied case")


if __name__ == "__main__":
    unittest.main()
