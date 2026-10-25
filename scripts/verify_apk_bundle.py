#!/usr/bin/env python3
"""Verify source bundle identities inside a built APK, not media playback."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile


def verify(apk_path):
    root = Path(__file__).resolve().parents[1]
    with zipfile.ZipFile(apk_path) as archive:
        assert archive.testzip() is None, 'Corrupt ZIP entry'
        assert 'AndroidManifest.xml' in archive.namelist()
        assert 'classes.dex' in archive.namelist()
        for name in ('embedded_catalog.json', 'embedded_directory_snapshot.json', 'open_films.json'):
            expected = (root / 'app/src/main/assets' / name).read_bytes()
            actual = archive.read('assets/' + name)
            assert actual == expected, f'Asset mismatch: {name}'
        snapshot = json.loads(archive.read('assets/embedded_directory_snapshot.json'))
        entries = snapshot['entries']
        assert len(entries) == snapshot['uniqueEntries']
        assert len({e['snapshotId'] for e in entries}) == len(entries)
        assert all(e['runtimeEnabled'] is False for e in entries)
    apk = Path(apk_path)
    result = {'apk': apk.name, 'bytes': apk.stat().st_size,
              'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
              'embeddedRepositoryReferences': len(entries),
              'assetBytesMatchSource': True, 'runtimePlaybackVerified': False}
    print(json.dumps(result, indent=2))
    return result


if __name__ == '__main__':
    verify(sys.argv[1])
