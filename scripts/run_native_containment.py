#!/usr/bin/env python3
"""Run installed NativeContainmentTest and preserve original device evidence.

Usage: python3 scripts/run_native_containment.py OUTPUT_DIRECTORY
Uses adb device selection (including ANDROID_SERIAL). Does not build, install,
synthesize results, or establish guarantees beyond the Android test assertions.
"""
import argparse
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tarfile

if __package__:
    from .run_isolated_js_instrumentation import validate_instrumentation
    from .run_provider_instrumentation import integer, require, strict_json
else:
    from run_isolated_js_instrumentation import validate_instrumentation
    from run_provider_instrumentation import integer, require, strict_json

PACKAGE = "com.cinator.app"
TEST_CLASS = PACKAGE + ".NativeContainmentTest"
DEVICE_DIRECTORY = "files/native-containment"
RESULT_MEMBER = DEVICE_DIRECTORY + "/result.json"
MAX_RESULT_BYTES = 2 * 1024 * 1024
MAX_ARCHIVE_BYTES = 4 * 1024 * 1024
REQUIRED_STAGES = frozenset((
    "manifest", "probe_admission", "native_promise", "native_timeout", "native_oom", "native_stack", "native_output_limit",
    "private_file_inaccessible", "socket_denied", "watchdog_death", "crash_death",
    "healthy_production_rebind",
))


def read_device_result(archive):
    """Read exactly one bounded regular JSON file, never extract archive paths."""
    require(0 < Path(archive).stat().st_size <= MAX_ARCHIVE_BYTES,
            "Empty or oversized evidence archive")
    raw = None
    with tarfile.open(archive, "r:") as bundle:
        for index, member in enumerate(bundle):
            require(index < 128, "Too many archive members")
            name = member.name.removeprefix("./")
            path = PurePosixPath(name)
            require(not path.is_absolute() and ".." not in path.parts, "Unsafe archive path")
            if member.isdir() and name.rstrip("/") in ("files", DEVICE_DIRECTORY):
                continue
            require(name == RESULT_MEMBER and member.type in (tarfile.REGTYPE, tarfile.AREGTYPE)
                    and not member.sparse and raw is None,
                    "Missing, unexpected, linked or ambiguous device result")
            require(0 < member.size <= MAX_RESULT_BYTES, "Empty or oversized device result")
            source = bundle.extractfile(member)
            if source is None:
                raise ValueError("Unreadable device result")
            with source:
                raw = source.read(MAX_RESULT_BYTES + 1)
            require(len(raw) == member.size, "Truncated device result")
    require(raw is not None, "Missing device result")
    return raw


def validate_evidence(raw):
    require(isinstance(raw, bytes) and 0 < len(raw) <= MAX_RESULT_BYTES,
            "Empty or oversized JSON evidence")
    result = strict_json(raw)
    require(isinstance(result, dict), "Evidence must be an object")
    require(result.get("test") == "NativeContainmentTest", "Wrong evidence test")
    require(result.get("status") == "PASSED" and not result.get("failure"),
            "Native containment device test did not pass")
    for key in ("appUid", "appPid"):
        require(integer(result.get(key), 1) and result[key] <= 2147483647,
                "Invalid app process identity: " + key)
    stages = result.get("stages")
    require(isinstance(stages, list) and len(stages) == len(REQUIRED_STAGES),
            "Exact containment stage set is required")
    names = []
    for stage in stages:
        require(isinstance(stage, dict) and isinstance(stage.get("name"), str) and
                stage.get("status") == "PASSED" and not stage.get("exception") and
                not stage.get("failure"), "Malformed or non-passing containment stage")
        names.append(stage["name"])
    require(len(set(names)) == len(names) and set(names) == REQUIRED_STAGES,
            "Missing, unexpected or duplicate containment stages")
    require(isinstance(result.get("observations"), dict) and result["observations"],
            "Missing nonempty observations")
    not_verified = result.get("notVerified")
    require(isinstance(not_verified, list) and
            all(isinstance(item, str) and item.strip() for item in not_verified),
            "Missing or malformed notVerified limitations")
    # Mirror the private-file oracle; preserve device bytes and errno distinctions.
    observations = result["observations"]
    private = observations.get("private_file_inaccessible")
    require(isinstance(private, dict) and private.get("case") == "PRIVATE_FILE" and
            private.get("event") == "RESULT", "Missing private-file observation")
    path = private.get("markerPath")
    require(isinstance(path, str) and 0 < len(path) <= 1024 and path.startswith("/") and
            path.endswith("/files/native-containment-private.txt"), "Invalid fixed marker path")
    for phase in ("before", "after"):
        control = observations.get("private_file_parent_" + phase)
        require(isinstance(control, dict) and control.get("markerPath") == path and
                integer(control.get("uid"), 1) and control["uid"] == result["appUid"] and
                all(control.get(key) is True for key in ("exists", "readable", "contentMatches")),
                "Missing exact-path app-UID positive control: " + phase)
    observed = (private.get("outcome"), private.get("detail"), private.get("isolationMode"))
    require(observed in (
        ("DENIED", "EACCES", "permission_denied"),
        ("DENIED", "EPERM", "permission_denied"),
        ("DENIED", "SECURITY_EXCEPTION", "permission_denied"),
        ("NOT_FOUND", "ENOENT", "isolated_namespace_not_visible")),
        "Invalid private-file isolation outcome/detail/mode")
    # Android assertions own the remaining native outcomes and isolated identities.


def collect(out):
    """Attempt both diagnostic collections even after a device command fails."""
    errors = []
    for name, args in [
        ("logcat", ["adb", "logcat", "-d", "-v", "threadtime", "-t", "10000"]),
        ("private-evidence", ["adb", "exec-out", "run-as", PACKAGE,
                              "tar", "-cf", "-", DEVICE_DIRECTORY]),
    ]:
        destination = out / ("logcat.txt" if name == "logcat" else "private-evidence.tar")
        try:
            with destination.open("wb") as sink:
                run = subprocess.run(args, stdout=sink, stderr=subprocess.PIPE, timeout=30)
            (out / (name + ".stderr.txt")).write_bytes(run.stderr)
            if run.returncode:
                errors.append(f"{name} collection exited {run.returncode}")
        except (OSError, subprocess.TimeoutExpired) as failure:
            errors.append(f"{name} collection failed: {failure}")
            try:
                (out / (name + ".stderr.txt")).write_text(str(failure) + "\n")
            except OSError as diagnostic_failure:
                errors.append(f"Cannot save {name} diagnostic: {diagnostic_failure}")
    return errors


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output_directory", type=Path)
    out = parser.parse_args(argv).output_directory.resolve()
    text, returncode, errors = "", None, []
    try:
        out.mkdir(parents=True, exist_ok=True)
        # A failed rerun must not retain an earlier passing JSON or archive.
        for name in ("result.json", "private-evidence.tar", "instrumentation.txt", "cleanup.txt"):
            (out / name).unlink(missing_ok=True)
        (out / "instrumentation.txt").write_bytes(b"")
        clear = subprocess.run(["adb", "shell", "run-as", PACKAGE, "rm", "-rf", DEVICE_DIRECTORY],
                               capture_output=True, timeout=30)
        (out / "cleanup.txt").write_bytes(clear.stdout + clear.stderr)
        require(clear.returncode == 0, "Cannot clear stale device evidence")
        run = subprocess.run([
            "adb", "shell", "am", "instrument", "-w", "-r", "-e", "class", TEST_CLASS,
            PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner",
        ], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
        (out / "instrumentation.txt").write_bytes(run.stdout)
        text, returncode = run.stdout.decode("utf-8", errors="replace"), run.returncode
    except subprocess.TimeoutExpired as failure:
        raw = failure.stdout or b""
        text = raw.decode("utf-8", errors="replace")
        try:
            (out / "instrumentation.txt").write_bytes(raw)
        except OSError as diagnostic_failure:
            errors.append(str(diagnostic_failure))
        errors.append(f"Device command timed out after {failure.timeout}s")
    except (OSError, ValueError) as failure:
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
        # Preserve original bytes even for FAILED/RUNNING/malformed JSON.
        (out / "result.json").write_bytes(raw)
        validate_evidence(raw)
    except (OSError, ValueError, tarfile.TarError) as failure:
        errors.append(f"Invalid native containment evidence: {failure}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Validated native containment instrumentation and device evidence: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
