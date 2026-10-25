#!/usr/bin/env python3
"""Unit tests only: synthetic ZIPs are NOT built, installable, or signed APKs.

Fixtures live in temporary scratch directories and are deleted after each test.
Verifier JSON is captured, never published as evidence of a real APK build.
Run: python3 -B -m unittest discover -s scripts -p 'test_verify_apk_bundle.py' -v
"""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SCRIPT = Path(__file__).resolve().with_name('verify_apk_bundle.py')
SPEC = importlib.util.spec_from_file_location('apk_bundle_verifier_under_test', SCRIPT)
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)
ASSET_NAMES = ('embedded_catalog.json', 'embedded_directory_snapshot.json', 'open_films.json', 'provider_compatibility.json', 'plugin_review_ledger.json')


class VerifyApkBundleTests(unittest.TestCase):
    def setUp(self):
        # Never write fixtures into the checkout or an APK output directory.
        scratch = Path(os.environ.get(
            'TMPDIR', str(Path.home() / '.cache' / 'cinetor-tests')))
        scratch.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(
            prefix='test-only-apk-verifier-', dir=scratch)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.assets = self.root / 'app/src/main/assets'
        self.assets.mkdir(parents=True)
        original_assets = SCRIPT.parent.parent / 'app/src/main/assets'
        for name in ASSET_NAMES:
            (self.assets / name).write_bytes((original_assets / name).read_bytes())
        self.snapshot = json.loads(
            (self.assets / 'embedded_directory_snapshot.json').read_bytes())
        # Relocate only source discovery; all ZIP reads and validation are real.
        # This permits malformed source/snapshot cases without editing the repo.
        (self.root / 'scripts').mkdir()
        relocation = patch.object(
            verifier, '__file__', str(self.root / 'scripts/verify_apk_bundle.py'))
        relocation.start()
        self.addCleanup(relocation.stop)

    def make_zip(self, omit=(), overrides=None):
        members = {
            'AndroidManifest.xml': b'TEST ONLY: not a compiled Android manifest',
            'classes.dex': b'TEST ONLY: not executable Dalvik bytecode',
            'TEST-ONLY.txt': b'Synthetic unit-test ZIP. NOT AN APK DELIVERABLE.',
        }
        members.update({
            'assets/' + name: (self.assets / name).read_bytes()
            for name in ASSET_NAMES
        })
        members.update(overrides or {})
        path = self.root / 'synthetic-test-only-not-an-apk.zip'
        with zipfile.ZipFile(path, 'w', compression=zipfile.ZIP_STORED) as archive:
            archive.comment = b'TEST ONLY - NOT A REAL APK'
            for name, data in members.items():
                if name not in omit:
                    archive.writestr(name, data)
        return path

    def replace_snapshot(self, snapshot):
        (self.assets / 'embedded_directory_snapshot.json').write_text(
            json.dumps(snapshot), encoding='utf-8')

    def assert_rejected(self, path, error, message=None):
        output = io.StringIO()
        expectation = (self.assertRaisesRegex(error, message) if message
                       else self.assertRaises(error))
        with contextlib.redirect_stdout(output), expectation:
            verifier.verify(path)
        self.assertEqual(output.getvalue(), '', 'Rejected ZIP must not emit success JSON')

    def test_matching_source_assets_report_counts_without_runtime_claim(self):
        path = self.make_zip()
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = verifier.verify(path)
        self.assertEqual(json.loads(output.getvalue()), result)
        self.assertEqual(result['embeddedRepositoryReferences'], len(self.snapshot['entries']))
        self.assertEqual(result['embeddedRepositoryReferences'], self.snapshot['uniqueEntries'])
        self.assertTrue(all(entry['runtimeEnabled'] is False
                            for entry in self.snapshot['entries']))
        self.assertIs(result['assetBytesMatchSource'], True)
        self.assertIs(result['runtimePlaybackVerified'], False)
        self.assertEqual(result['apk'], path.name)
        self.assertEqual(result['bytes'], path.stat().st_size)
        self.assertEqual(result['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())

    def test_each_missing_asset_is_rejected(self):
        for name in ASSET_NAMES:
            with self.subTest(asset=name):
                self.assert_rejected(self.make_zip(omit={'assets/' + name}), KeyError)

    def test_each_byte_mismatched_asset_is_rejected(self):
        for name in ASSET_NAMES:
            with self.subTest(asset=name):
                # Whitespace changes preserve JSON semantics but not byte identity.
                path = self.make_zip(overrides={
                    'assets/' + name: (self.assets / name).read_bytes() + b'\n'})
                self.assert_rejected(path, AssertionError, 'Asset mismatch: ' + name)

    def test_missing_dex_is_rejected(self):
        self.assert_rejected(self.make_zip(omit={'classes.dex'}), AssertionError)

    def test_missing_manifest_is_rejected(self):
        self.assert_rejected(self.make_zip(omit={'AndroidManifest.xml'}), AssertionError)

    def test_corrupt_zip_entry_is_rejected(self):
        path = self.make_zip()
        with zipfile.ZipFile(path) as archive:
            info = archive.getinfo('TEST-ONLY.txt')
            self.assertIsNone(archive.testzip())
        payload = bytearray(path.read_bytes())
        # Flip a stored data byte without updating its CRC; keep ZIP headers valid.
        name_length, extra_length = struct.unpack_from('<HH', payload, info.header_offset + 26)
        data_offset = info.header_offset + 30 + name_length + extra_length
        payload[data_offset] ^= 1
        path.write_bytes(payload)
        with zipfile.ZipFile(path) as archive:
            self.assertEqual(archive.testzip(), 'TEST-ONLY.txt')
        self.assert_rejected(path, AssertionError, 'Corrupt ZIP entry')

    def test_non_zip_input_is_rejected(self):
        path = self.root / 'test-only-invalid.zip'
        path.write_bytes(b'TEST ONLY: not a ZIP archive')
        self.assert_rejected(path, zipfile.BadZipFile)

    def test_incorrect_declared_entry_count_is_rejected(self):
        self.snapshot['uniqueEntries'] = len(self.snapshot['entries']) + 1
        self.replace_snapshot(self.snapshot)
        self.assert_rejected(self.make_zip(), AssertionError)

    def test_duplicate_snapshot_ids_are_rejected(self):
        self.assertGreaterEqual(len(self.snapshot['entries']), 2)
        self.snapshot['entries'][1]['snapshotId'] = self.snapshot['entries'][0]['snapshotId']
        self.replace_snapshot(self.snapshot)
        self.assert_rejected(self.make_zip(), AssertionError)

    def test_runtime_enabled_must_be_literal_false(self):
        self.assertTrue(self.snapshot['entries'])
        for value in (True, 0, None, 'false'):
            with self.subTest(runtimeEnabled=value):
                self.snapshot['entries'][0]['runtimeEnabled'] = value
                self.replace_snapshot(self.snapshot)
                self.assert_rejected(self.make_zip(), AssertionError)


if __name__ == '__main__':
    unittest.main()
