#!/usr/bin/env python3
"""Run installed Android provider instrumentation; fail on tests or missing evidence."""
from pathlib import Path
import subprocess
import re
import sys
import json

out = Path(sys.argv[1])
out.mkdir(parents=True, exist_ok=True)
package = 'com.cinator.app'
try:
    run = subprocess.run(['adb', 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'com.cinator.app.ProviderPlaybackE2ETest',
        'com.cinator.app.test/androidx.test.runner.AndroidJUnitRunner'],
        capture_output=True, text=True, timeout=1250)
    text = run.stdout + run.stderr
    (out / 'instrumentation.txt').write_text(text)
    print(text)
finally:
    logs = subprocess.run(['adb', 'logcat', '-d', '-v', 'brief'], capture_output=True, text=True)
    (out / 'logcat.txt').write_text(logs.stdout)
    with (out / 'private-evidence.tar').open('wb') as sink:
        subprocess.run(['adb', 'exec-out', 'run-as', package, 'tar', '-cf', '-', 'files/provider-e2e'], stdout=sink, stderr=subprocess.PIPE)
if run.returncode != 0 or not re.search(r'OK \(1 test\)', text) or any(x in text for x in ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'Process crashed']):
    raise SystemExit('Provider instrumentation did not pass')
(out / 'result.json').write_text(json.dumps({'instrumentationPassed':True,
    'scope':'One audited film/provider; test-owned ExoPlayer; app UI fullscreen and all-provider playback not covered'}, indent=2)+'\n')
