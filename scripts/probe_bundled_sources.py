#!/usr/bin/env python3
"""Read-only bundled source probe; availability is not a playback test."""
import argparse
import concurrent.futures
import datetime
import hashlib
import json
from pathlib import Path
import urllib.request


def probe(url):
    result = {'url': url}
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Cinator-SourceAudit/0.3'})
        with urllib.request.urlopen(req, timeout=18) as r:
            data = r.read(4 * 1024 * 1024 + 1)
            if len(data) > 4 * 1024 * 1024:
                raise ValueError('response size limit')
            result.update(httpStatus=r.status, bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
            if url.endswith('.mp4'):
                if not r.headers.get('Content-Type', '').lower().startswith('video/mp4') or data[4:8] != b'ftyp':
                    raise ValueError('not an MP4 payload')
                result['mp4SignatureObserved'] = True
                result['status'] = 'sample_transport_reachable'
                return result
            if url.endswith('.m3u'):
                text = data.decode('utf-8-sig')
                if not text.startswith('#EXTM3U'):
                    raise ValueError('not M3U')
                result['declaredChannels'] = sum(line.startswith('#EXTINF:') for line in text.splitlines())
            else:
                body = json.loads(data)
                if url.endswith('/manifest.json'):
                    result['manifestId'] = body.get('id')
                    result['resources'] = body.get('resources', [])
                else:
                    result['records'] = len(body) if isinstance(body, list) else len(body.get('metas', []))
            result['status'] = 'metadata_reachable'
    except Exception as exc:
        result.update(status='unreachable_or_invalid', error=str(exc))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    bundle = json.loads((root / 'app/src/main/assets/embedded_catalog.json').read_text())
    urls = sorted({u for s in bundle['sources'] if s['enabled'] for u in s['endpoints']})
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(probe, urls))
    report = {'capturedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'scope': 'bounded metadata/playlist requests; no stream playback tested',
              'expectedEndpoints': len(urls), 'checkedEndpoints': len(results),
              'reachable': sum(r['status'] in ('metadata_reachable', 'sample_transport_reachable') for r in results),
              'results': results}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
