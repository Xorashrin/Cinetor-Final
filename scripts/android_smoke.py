#!/usr/bin/env python3
"""Android emulator smoke test using UIAutomator; saves real XML/log evidence."""
import argparse
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = 'com.cinator.app'


def adb(*args, timeout=25):
    return subprocess.run(['adb', *args], check=True, capture_output=True, text=True, timeout=timeout).stdout


def bounds_center(value):
    values = [int(v) for v in re.findall(r'\d+', value)]
    if len(values) != 4:
        raise ValueError('Invalid UI bounds')
    x1, y1, x2, y2 = values
    if x2 <= x1 or y2 <= y1:
        raise ValueError('Empty UI bounds')
    return (x1 + x2) // 2, (y1 + y2) // 2


def smoke(output):
    output.mkdir(parents=True, exist_ok=True)
    adb('logcat', '-c')
    adb('shell', 'am', 'force-stop', PACKAGE)
    adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
    evidence = []
    def tree(label):
        for attempt in range(3):
            try:
                adb('shell', 'uiautomator', 'dump', '/sdcard/cinator-smoke.xml')
                path = output / (label + '.xml')
                adb('pull', '/sdcard/cinator-smoke.xml', str(path))
                return ET.parse(path).getroot()
            except (subprocess.SubprocessError, ET.ParseError):
                if attempt == 2:
                    raise
                time.sleep(2)
    def has_label(root, label):
        return any(label in n.get('text', '') or label in n.get('content-desc', '') for n in root.iter('node'))
    def require(label, evidence_name):
        for _ in range(12):
            root = tree(evidence_name)
            if has_label(root, label):
                evidence.append(evidence_name)
                return root
            time.sleep(1)
        raise AssertionError('UI label not found: ' + label)
    def tap(label, evidence_name):
        root = tree(evidence_name)
        matches = [n for n in root.iter('node') if n.get('text') == label or n.get('content-desc') == label]
        assert matches, 'Missing navigation target: ' + label
        points = [bounds_center(n.get('bounds', '')) for n in matches]
        x, y = max(points, key=lambda p: p[1])
        adb('shell', 'input', 'tap', str(x), str(y))
        time.sleep(1)
    try:
        require('Cinator • Home', '01-home')
        tap('Anime', '02-before-anime')
        require('Cinator • Anime', '03-anime')
        tap('K-Drama', '04-before-kdrama')
        require('Cinator • K-Drama', '05-kdrama')
        tap('More', '06-before-more')
        require('Live TV Channels', '07-more')
        tap('Home', '08-before-home')
        require('Cinator • Home', '09-home-return')
        assert adb('shell', 'pidof', PACKAGE).strip(), 'App process is not running'
    finally:
        log = adb('logcat', '-d', '-v', 'brief')
        (output / 'logcat.txt').write_text(log)
    assert not re.search(r'FATAL EXCEPTION[\s\S]{0,600}Process: com\.cinator\.app', log), 'App crash in logcat'
    (output / 'result.txt').write_text('PASS: launch, Home, Anime, K-Drama, More and return navigation\n'
        'Not a provider playback, performance or offline-download verification.\n')
    print('PASS:', ', '.join(evidence))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    smoke(Path(parser.parse_args().output))
