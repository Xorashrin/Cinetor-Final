#!/usr/bin/env python3
"""Offline compatibility inventory tests; fixtures are not playback evidence."""
import copy
import importlib.util
import subprocess
import sys
import tempfile
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/generate_compatibility_matrix.py"
ASSETS = ROOT / "app/src/main/assets"


def load_generator():
    spec = importlib.util.spec_from_file_location("compat_generator", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class CompatibilityMatrixTest(unittest.TestCase):
    def test_real_bundle_covers_every_reference_without_count_inflation(self):
        self.assertTrue(SCRIPT.is_file(), "compatibility matrix generator is missing")
        generator = load_generator()
        snapshot = json.loads((ASSETS / "embedded_directory_snapshot.json").read_text())
        catalog = json.loads((ASSETS / "embedded_catalog.json").read_text())
        films = json.loads((ASSETS / "open_films.json").read_text())
        matrix = generator.build_matrix(snapshot, catalog, films)
        self.assertEqual(matrix["schemaVersion"], 1)
        rows = {e["id"]: e for e in matrix["entries"]}
        self.assertEqual(len(rows), len(matrix["entries"]))
        self.assertEqual(len(snapshot["entries"]), 79)
        self.assertEqual(len(rows), 79 + len(catalog["sources"]) + 1 + 7)
        for reference in snapshot["entries"]:
            row = rows[reference["snapshotId"]]
            self.assertEqual(row["status"], "REFERENCE_ONLY")
            self.assertFalse(any(row["capability"].values()))
            self.assertIsNone(row["evidenceUrl"])
        for source in catalog["sources"]:
            self.assertIn(source["id"], rows)
        self.assertEqual(len(films["films"]), 4)
        self.assertEqual([e["id"] for e in matrix["entries"]
                          if e["status"] == "SUPPORTED_E2E"], ["blender-open-films"])
        self.assertEqual(rows["stremio-http"]["status"], "SUPPORTED_PROTOCOL")
        self.assertEqual(rows["iptv-org-public"]["status"], "SUPPORTED_PROTOCOL")
        for entry_id in ("nuvio-js", "cloudstream-dex"):
            self.assertEqual(rows[entry_id]["status"], "BLOCKED_SECURITY")
        for entry_id in ("anime-direct", "kdrama-direct"):
            self.assertEqual(rows[entry_id]["status"], "BLOCKED_RIGHTS_OR_API")
        for entry_id in ("jellyfin", "nginx"):
            self.assertEqual(rows[entry_id]["status"], "REQUIRES_USER_SERVER")
        self.assertFalse(any(e["capability"]["download"] for e in matrix["entries"]))
        self.assertEqual(generator.render_matrix(matrix), generator.render_matrix(
            generator.build_matrix(snapshot, catalog, films)))


class FixtureAndCliTest(unittest.TestCase):
    def setUp(self):
        self.generator = load_generator()
        self.snapshot = {"schemaVersion": 1, "uniqueEntries": 1,
                         "counts": {"nuvio": 1}, "entries": [
            {"id": "nuvio:repo:fixture", "snapshotId": "reference-fixture",
             "ecosystem": "nuvio", "name": "Fixture",
             "endpoint": None, "status": "SUPPORTED_E2E", "runtimeEnabled": True,
             "plugins": [{"name": "one"}, {"name": "two"}]}]}
        self.catalog = {"schemaVersion": 1, "bundleVersion": "fixture", "sources": [
            {"id": "fixture-provider", "name": "Fixture metadata", "engine": "TVMAZE",
             "enabled": True, "endpoints": ["https://example.invalid/one",
                                              "https://example.invalid/two"]}]}
        self.films = {"schemaVersion": 1, "providerId": "fixture-films",
                      "name": "Fixture films", "films": [{"id": "fixture"}]}

    def build(self):
        return self.generator.build_matrix(self.snapshot, self.catalog, self.films)

    def test_fixtures_are_never_playback_evidence(self):
        rows = {e["id"]: e for e in self.build()["entries"]}
        self.assertEqual(len(rows), 10)
        self.assertEqual(rows["fixture-films"]["status"], "REFERENCE_ONLY")
        self.assertEqual(rows["reference-fixture"]["status"], "REFERENCE_ONLY")
        self.assertEqual(sum(e["status"] == "SUPPORTED_E2E" for e in rows.values()), 0)

    def test_order_independent_and_input_not_mutated(self):
        self.snapshot["entries"].append({"id": "nuvio:repo:other", "snapshotId": "reference-other", "name": "Other",
                                          "ecosystem": "nuvio", "endpoint": None})
        self.snapshot["uniqueEntries"] = 2
        self.snapshot["counts"]["nuvio"] = 2
        before = copy.deepcopy((self.snapshot, self.catalog, self.films))
        one = self.generator.render_matrix(self.build())
        self.assertEqual(before, (self.snapshot, self.catalog, self.films))
        self.snapshot["entries"].reverse()
        self.assertEqual(one, self.generator.render_matrix(self.build()))

    def test_duplicate_ids_rejected_across_input_categories(self):
        self.catalog["sources"][0]["id"] = "reference-fixture"
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.build()

    def test_snapshot_identity_is_not_upstream_id(self):
        other = copy.deepcopy(self.snapshot["entries"][0])
        other["snapshotId"] = "reference-other"
        self.snapshot["entries"].append(other)
        self.snapshot["uniqueEntries"] = 2
        self.snapshot["counts"]["nuvio"] = 2
        rows = {e["id"] for e in self.build()["entries"]}
        self.assertTrue({"reference-fixture", "reference-other"}.issubset(rows))
        other["snapshotId"] = "reference-fixture"
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.build()
        del other["snapshotId"]
        with self.assertRaisesRegex(ValueError, "snapshotId"):
            self.build()

    def test_reported_snapshot_counts_are_checked(self):
        for field, value in (("uniqueEntries", 94), ("counts", {"nuvio": 99})):
            with self.subTest(field=field):
                original = self.snapshot[field]
                self.snapshot[field] = value
                with self.assertRaisesRegex(ValueError, "count"):
                    self.build()
                self.snapshot[field] = original

    def test_parser_strict_text_and_integer_requirements(self):
        for name in ("   ", "bad\x7fname", "bad\x85name"):
            self.catalog["sources"][0]["name"] = name
            with self.assertRaises(ValueError):
                self.build()
        self.catalog["sources"][0]["name"] = "Valid"
        for field in ("schemaVersion", "uniqueEntries"):
            self.snapshot[field] = True
            with self.assertRaises(ValueError):
                self.build()
            self.snapshot[field] = 1
        self.snapshot["subplugins"] = []
        self.snapshot["nuvioSubpluginDescriptors"] = 42
        with self.assertRaisesRegex(ValueError, "count"):
            self.generator.build_plugin_review_ledger(self.snapshot)

    def test_raw_asset_cli_matrix_and_separate_ledger(self):
        scratch = Path.home() / ".hermes/cache/scratch"
        scratch.mkdir(parents=True, exist_ok=True)
        before = {p: p.read_bytes() for p in ASSETS.glob("*.json")}
        with tempfile.TemporaryDirectory(prefix="compat-raw-test-", dir=scratch) as directory:
            matrix = Path(directory) / "matrix.json"
            ledger = Path(directory) / "ledger.json"
            command = [sys.executable, "-B", str(SCRIPT), "--output", str(matrix),
                       "--plugin-ledger-output", str(ledger)]
            first = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            expected = (matrix.read_bytes(), ledger.read_bytes())
            self.assertEqual(len(json.loads(expected[0])["entries"]), 92)
            self.assertEqual(len(json.loads(expected[1])["entries"]), 162)
            second = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(second.returncode, 0, second.stderr)
            self.assertEqual(expected, (matrix.read_bytes(), ledger.read_bytes()))
            check = subprocess.run(command + ["--check"], capture_output=True, text=True)
            self.assertEqual(check.returncode, 0, check.stderr)
            ledger.write_text("{}")
            stale = subprocess.run(command + ["--check"], capture_output=True, text=True)
            self.assertNotEqual(stale.returncode, 0)
            self.assertEqual(ledger.read_text(), "{}")
            collision = subprocess.run(command[:-1] + [str(matrix)], capture_output=True, text=True)
            self.assertNotEqual(collision.returncode, 0)
            self.assertEqual(matrix.read_bytes(), expected[0])
        self.assertEqual(before, {p: p.read_bytes() for p in ASSETS.glob("*.json")})

    def test_invalid_inputs_fail_closed(self):
        for field, value in (("name", "bad\nname"), ("id", ""),
                             ("engine", None), ("enabled", "true"),
                             ("endpoints", ["javascript:alert(1)"])):
            with self.subTest(field=field):
                original = self.catalog["sources"][0][field]
                self.catalog["sources"][0][field] = value
                with self.assertRaises(ValueError):
                    self.build()
                self.catalog["sources"][0][field] = original
        self.snapshot["schemaVersion"] = 2
        with self.assertRaisesRegex(ValueError, "schemaVersion"):
            self.build()

    def test_disabled_unknown_and_runtime_configurations_cannot_claim_playback(self):
        source = self.catalog["sources"][0]
        source.update(enabled=False, capability={"directPlayback": True})
        self.assertEqual(next(e for e in self.build()["entries"] if e["id"] == source["id"])
                         ["status"], "REFERENCE_ONLY")
        source["enabled"] = True
        for engine, expected in (("NEW_ENGINE", "REFERENCE_ONLY"),
                                 ("NUVIOJS", "BLOCKED_SECURITY"),
                                 ("CLOUDSTREAMDEX", "BLOCKED_SECURITY"),
                                 ("JELLYFIN", "REQUIRES_USER_SERVER"),
                                 ("NGINX", "REQUIRES_USER_SERVER")):
            source["engine"] = engine
            row = next(e for e in self.build()["entries"] if e["id"] == source["id"])
            self.assertEqual(row["status"], expected)
            self.assertFalse(any(row["capability"].values()))

    def test_real_matrix_exact_schema_and_status_counts(self):
        from collections import Counter
        matrix = self.generator.build_matrix(*[json.loads((ASSETS / name).read_text())
            for name in ("embedded_directory_snapshot.json", "embedded_catalog.json", "open_films.json")])
        self.assertEqual(Counter(e["status"] for e in matrix["entries"]), {
            "REFERENCE_ONLY": 79, "SUPPORTED_PROTOCOL": 6, "SUPPORTED_E2E": 1,
            "BLOCKED_SECURITY": 2, "BLOCKED_RIGHTS_OR_API": 2, "REQUIRES_USER_SERVER": 2})
        self.assertEqual(set(matrix), {"schemaVersion", "entries"})
        for row in matrix["entries"]:
            self.assertEqual(set(row), {"id", "ecosystem", "name", "status", "capability",
                                        "reason", "evidenceUrl", "version"})
            self.assertEqual(set(row["capability"]), {"catalog", "search", "details", "episodes",
                                                       "directPlayback", "download", "externalPlayback"})
            self.assertTrue(all(type(v) is bool for v in row["capability"].values()))
            self.assertTrue(row["reason"])
            if row["status"] not in ("SUPPORTED_E2E", "SUPPORTED_PROTOCOL"):
                self.assertIsNone(row["evidenceUrl"], "descriptor URLs must not become evidence")
        blender = next(e for e in matrix["entries"] if e["id"] == "blender-open-films")
        self.assertEqual(blender["evidenceUrl"],
                         "https://github.com/Xorashrin/Cinetor-Final/releases/download/"
                         "v0.4.0-preview/ANDROID-PROVIDER-EVIDENCE.json")
        self.assertEqual(blender["version"], "19683fb")
        self.assertIn("one-film", blender["reason"])
        self.assertIn("19683fb", blender["reason"])
        self.assertIn("not a current run", blender["reason"])
        for row in matrix["entries"]:
            if row["status"] == "SUPPORTED_PROTOCOL":
                self.assertIn(row["evidenceUrl"], {
                    "https://www.tvmaze.com/api",
                    "https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/README.md",
                    "https://github.com/iptv-org/iptv/blob/master/README.md",
                })
                self.assertEqual(row["capability"]["details"], row["id"] == "stremio-http")
                self.assertEqual(row["capability"]["episodes"], row["id"] == "stremio-http")
        self.assertFalse(any("france24" in e["id"].lower() or "archive" in e["id"].lower()
                             for e in matrix["entries"] if e["capability"]["directPlayback"]))
        self.assertIn("four-film", blender["reason"])
        self.assertFalse(blender["capability"]["episodes"])
        ledger = self.generator.build_plugin_review_ledger(
            json.loads((ASSETS / "embedded_directory_snapshot.json").read_text()))
        self.assertEqual(set(ledger), {"schemaVersion", "entries"})
        self.assertEqual(len(ledger["entries"]), 162)
        self.assertEqual(len({e["id"] for e in ledger["entries"]}), 162)
        for plugin in ledger["entries"]:
            self.assertEqual(set(plugin), {
                "id", "parentSnapshotId", "ecosystem", "name", "status", "license", "digest", "signer",
                "immutableVersion", "sourceCommit", "reproducibleBuildEvidence", "verifiedSignature",
                "releaseAuthorization", "revocationState", "licenseEvidence", "dependencyNotices",
                "distributionObligations", "securityReview", "approvedCapabilities", "mediaRightsEvidence",
                "territories", "playbackPermission", "downloadPermission", "attributionRequirements",
                "blockers", "reviewedAt", "recheckAt", "androidEvidence", "releaseCommit", "apkDigest"
            })
            self.assertFalse(plugin["approvedCapabilities"])
            self.assertIn("NO_RUNTIME_E2E", plugin["blockers"])
            self.assertIsNone(plugin["androidEvidence"])
            self.assertEqual(plugin["status"], "UNREVIEWED")
            self.assertIsNone(plugin["license"])
            self.assertIsNone(plugin["digest"])
            self.assertIsNone(plugin["signer"])

    def test_plugin_ledger_handles_duplicate_cross_language_names_and_parent_later(self):
        self.snapshot["entries"][0]["subplugins"] = [
            {"name": "映画"}, {"name": "映画"}, {"name": "Película"}]
        later = {"id": "upstream-same", "snapshotId": "parent-later", "ecosystem": "nuvio",
                 "name": "Later", "endpoint": None, "subplugins": [{"name": "映画"}]}
        self.snapshot["entries"].append(later)
        self.snapshot["uniqueEntries"] = 2
        self.snapshot["counts"]["nuvio"] = 2
        ledger = self.generator.build_plugin_review_ledger(self.snapshot)
        self.assertEqual(len(ledger["entries"]), 4)
        self.assertEqual(len({e["id"] for e in ledger["entries"]}), 4)
        self.assertEqual([e["name"] for e in ledger["entries"]].count("映画"), 3)
        self.snapshot["entries"].reverse()
        self.assertEqual(self.generator.render_plugin_review_ledger(ledger),
                         self.generator.render_plugin_review_ledger(
                             self.generator.build_plugin_review_ledger(self.snapshot)))

    def test_cli_output_and_check_are_deterministic_and_do_not_publish_asset(self):
        scratch = Path.home() / ".hermes/cache/scratch"
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="compat-test-", dir=scratch) as directory:
            base = Path(directory)
            files = []
            for name, data in (("snapshot", self.snapshot), ("catalog", self.catalog),
                               ("films", self.films)):
                path = base / (name + ".json")
                path.write_text(json.dumps(data))
                files.extend(["--" + name, str(path)])
            output = base / "provider_compatibility.json"
            command = [sys.executable, "-B", str(SCRIPT), *files, "--output", str(output)]
            first = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            self.assertTrue(output.is_file(), "CLI must actually produce JSON")
            expected = output.read_bytes()
            second = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(second.returncode, 0, second.stderr)
            self.assertEqual(expected, output.read_bytes())
            check = subprocess.run(command + ["--check"], capture_output=True, text=True)
            self.assertEqual(check.returncode, 0, check.stderr)
            output.write_text("{}")
            stale = subprocess.run(command + ["--check"], capture_output=True, text=True)
            self.assertNotEqual(stale.returncode, 0)
            self.assertEqual(output.read_text(), "{}")


if __name__ == "__main__":
    unittest.main()
