#!/usr/bin/env python3
"""Run installed isolated-JS Android test; retain only real device evidence.

Usage: python3 scripts/run_isolated_js_instrumentation.py OUTPUT_DIRECTORY
Uses adb's current device selection (including ANDROID_SERIAL). Does not build,
install, synthesize results, or claim sandbox guarantees beyond the device test.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import tarfile

PACKAGE = "com.cinator.app"
TEST_CLASS = "com.cinator.app.IsolatedQuickJsTest"
DEVICE_DIRECTORY = "files/isolated-js-e2e"
RESULT_MEMBER = DEVICE_DIRECTORY + "/result.json"


def validate_instrumentation(text, returncode):
    """adb often exits zero for failed JUnit tests; inspect the raw protocol."""
    success = re.findall(r"^\s*(OK \(\d+ tests?\))\s*$", text, re.MULTILINE)
    failure = re.search(
        r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|"
        r"Process crashed|INSTRUMENTATION_STATUS_CODE:\s*-[1-9]\d*|"
        r"INSTRUMENTATION_RESULT:\s*(?:shortMsg|longMsg)=|"
        r"INSTRUMENTATION_CODE:\s*(?!-1\b)-?\d+", text, re.IGNORECASE)
    if returncode != 0 or success != ["OK (1 test)"] or failure:
        raise ValueError("Isolated-JS instrumentation did not pass exactly one test")


def read_device_result(archive):
    """Read the one expected regular file without extracting untrusted tar paths."""
    with tarfile.open(archive, "r:") as bundle:
        members = [member for member in bundle.getmembers()
                   if member.name.removeprefix("./") == RESULT_MEMBER]
        if len(members) != 1 or not members[0].isfile():
            raise ValueError("Missing or ambiguous device result.json")
        if not 0 < members[0].size <= 2 * 1024 * 1024:
            raise ValueError("Device result.json is empty or oversized")
        source = bundle.extractfile(members[0])
        if source is None:
            raise ValueError("Cannot read device result.json")
        with source:
            raw = source.read()
    return raw


def validate_evidence(raw):
    result = json.loads(raw)
    if not isinstance(result, dict) or result.get("status") != "PASSED":
        raise ValueError("Device evidence status is not PASSED")
    if result.get("test") != "IsolatedQuickJsTest":
        raise ValueError("Evidence belongs to a different test")
    # UID/PID and Binder death assertions are performed by the Android test,
    # not inferred from host logs or replaced with host-generated evidence.


def collect(out):
    errors = []
    for name, args, timeout in [
        ("logcat", ["adb", "logcat", "-d", "-v", "threadtime"], 30),
        ("private-evidence", ["adb", "exec-out", "run-as", PACKAGE,
                              "tar", "-cf", "-", DEVICE_DIRECTORY], 30),
    ]:
        destination = out / ("logcat.txt" if name == "logcat" else "private-evidence.tar")
        with destination.open("wb") as sink:
            try:
                run = subprocess.run(args, stdout=sink, stderr=subprocess.PIPE, timeout=timeout)
                (out / (name + ".stderr.txt")).write_bytes(run.stderr)
                if run.returncode:
                    errors.append(f"{name} collection exited {run.returncode}")
            except (OSError, subprocess.TimeoutExpired) as failure:
                (out / (name + ".stderr.txt")).write_text(str(failure) + "\n")
                errors.append(f"{name} collection failed: {failure}")
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output_directory", type=Path)
    out = parser.parse_args(argv).output_directory.resolve()
    out.mkdir(parents=True, exist_ok=True)
    # A failed rerun must never leave an earlier passing JSON in this output.
    (out / "result.json").unlink(missing_ok=True)
    text = ""
    returncode = None
    errors = []
    try:
        # Remove prior device evidence before running, so stale PASSED cannot
        # make a skipped/crashed run look successful. Only this test's files.
        clear = subprocess.run(["adb", "shell", "run-as", PACKAGE, "rm", "-rf",
                                DEVICE_DIRECTORY], capture_output=True, timeout=30)
        (out / "cleanup.txt").write_bytes(clear.stdout + clear.stderr)
        if clear.returncode:
            raise ValueError(f"Cannot clear stale device evidence (exit {clear.returncode})")
        run = subprocess.run([
            "adb", "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
            PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner",
        ], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=300)
        text = run.stdout.decode("utf-8", errors="replace")
        returncode = run.returncode
        (out / "instrumentation.txt").write_bytes(run.stdout)
    except subprocess.TimeoutExpired as failure:
        raw = failure.stdout or b""
        text = raw.decode("utf-8", errors="replace")
        (out / "instrumentation.txt").write_bytes(raw)
        errors.append(f"Device command timed out after {failure.timeout}s")
    except (OSError, ValueError) as failure:
        (out / "instrumentation.txt").write_bytes(b"")
        errors.append(str(failure))
    finally:
        print(text, end="", flush=True)
        errors.extend(collect(out))
    try:
        validate_instrumentation(text, returncode)
    except ValueError as failure:
        errors.append(str(failure))
    try:
        raw = read_device_result(out / "private-evidence.tar")
        # Preserve original test-owned bytes, including FAILED/RUNNING results.
        (out / "result.json").write_bytes(raw)
        validate_evidence(raw)
    except (OSError, ValueError, tarfile.TarError) as failure:
        errors.append(f"Invalid device evidence: {failure}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Validated device instrumentation and evidence: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
