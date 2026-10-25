#!/usr/bin/env python3
"""Validate provider E2E evidence; never perform or invent playback.

Usage: python3 scripts/provider_e2e.py --report path/to/report.json
Exit: 0 = all supplied cases meet E2E evidence contract; 1 = valid but
incomplete/failed/unsupported/test-only; 2 = malformed or unbacked claims.

Schema v1 (unknown fields are rejected except optional string `notes`):
  root: schemaVersion=1, evidenceKind="real"|"test_only", providers=[case,...]
  case: providerId, version, mediaId, contentId (nonempty exact strings),
        stages={every name in STAGES: {status, evidence: [artifact,...]}}
  status: passed|failed|not_tested|unsupported
  artifact: path, kind, providerId, version, mediaId, contentId

Required stages and artifact kinds:
  catalog, search, details, resolve: provider_probe
  stream_transport: transport_probe
  android_play, android_pause, android_seek: android_instrumentation|android_manual
  download: android_download
  offline_play: android_offline_playback

Every passed stage needs at least one readable nonempty regular file under the
report directory, with matching identity and the stage-specific evidence kind
in KINDS. All supplied artifact references are checked, even for failed stages.
Paths are relative; escaping the directory through '..' or symlinks is rejected.

Acceptance is strict: all ten stages must pass for every supplied case. Unsupported
is not a pass. Playback needs the first eight stages, not just stream transport.
Counts of verified providers require all that provider's supplied cases to qualify;
these are not counts of plugins installed or of unlisted providers. Stage counts
are declared case statuses, never proof of actual execution. Test-only manifests
can exercise validation but cannot produce accepted or verified results.

TRUST BOUNDARY: this is an artifact/declared-provenance contract validator, not a
media player or forensic authenticator. It cannot establish that a log/screenshot
is genuine, or that a provider list is exhaustive. `*_verified` means evidence
contract satisfied for reports explicitly declared real, subject to human review
of their contents. Collect actual device playback/control/offline evidence first.
"""
import argparse
import json
from pathlib import Path
import stat

STAGES = ("catalog", "search", "details", "resolve", "stream_transport",
          "android_play", "android_pause", "android_seek", "download", "offline_play")
STATUSES = ("passed", "failed", "not_tested", "unsupported")
IDENTITY = ("providerId", "version", "mediaId", "contentId")
KINDS: dict[str, tuple[str, ...]] = {stage: ("provider_probe",) for stage in STAGES[:4]}
KINDS.update(stream_transport=("transport_probe",),
             android_play=("android_instrumentation", "android_manual"),
             android_pause=("android_instrumentation", "android_manual"),
             android_seek=("android_instrumentation", "android_manual"),
             download=("android_download",),
             offline_play=("android_offline_playback",))


def exact_string(value):
    return (isinstance(value, str) and bool(value) and value == value.strip()
            and not any(ord(c) < 32 or ord(c) == 127 for c in value))


def check_fields(value, required, where, errors):
    if not isinstance(value, dict):
        errors.append(where + ": expected an object")
        return False
    missing = set(required) - set(value)
    unknown = set(value) - set(required) - {"notes"}
    if missing or unknown:
        errors.append(f"{where}: missing fields {sorted(missing)}; unknown fields {sorted(unknown)}")
    if "notes" in value and not isinstance(value["notes"], str):
        errors.append(where + ": notes must be a string")
    return not missing and not unknown


def check_artifact(artifact, case, stage, base, where, errors):
    if not check_fields(artifact, (*IDENTITY, "path", "kind"), where, errors):
        return
    for key in IDENTITY:
        if not exact_string(artifact[key]) or artifact[key] != case.get(key):
            errors.append(f"{where}: {key} does not match case identity")
    if artifact["kind"] not in KINDS[stage]:
        errors.append(f"{where}: {stage} requires evidence kind {KINDS[stage]}")
    if not exact_string(artifact["path"]):
        errors.append(where + ": path must be a nonempty relative string")
        return
    try:
        relative = Path(artifact["path"])
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("path must stay under report directory")
        target = (base / relative).resolve(strict=True)
        target.relative_to(base)
        if not stat.S_ISREG(target.stat().st_mode):
            raise ValueError("evidence must be a regular file")
        with target.open("rb") as source:
            if not source.read(1):
                raise ValueError("evidence file is empty")
    except (OSError, ValueError, RuntimeError) as exc:
        errors.append(f"{where}: unusable evidence path: {exc}")


def empty_result():
    return {"valid": False, "accepted": False, "errors": [], "providers": [],
            "acceptance_reason": "invalid report or missing/unusable evidence",
            "verification_scope": "artifact existence and declared provenance only; not independent playback authentication",
            "summary": {"case_count": 0, "provider_count": 0,
                        "transport_verified": 0, "playback_verified": 0,
                        "offline_verified": 0, "e2e_verified": 0,
                        "stage_counts_basis": "declared statuses per supplied case",
                        "stage_counts": {s: {v: 0 for v in STATUSES} for s in STAGES}}}


def validate_report(report, base):
    """Return JSON-ready validation; caller remains responsible for artifact truth."""
    result = empty_result()
    errors = result["errors"]
    if not check_fields(report, ("schemaVersion", "evidenceKind", "providers"), "report", errors):
        return result
    if type(report["schemaVersion"]) is not int or report["schemaVersion"] != 1:
        errors.append("report: schemaVersion must be integer 1")
    if report["evidenceKind"] not in ("real", "test_only"):
        errors.append("report: evidenceKind must be real or test_only")
    cases = report["providers"]
    if not isinstance(cases, list) or not cases:
        errors.append("report: providers must be a nonempty array of cases")
        return result
    try:
        base = Path(base).resolve(strict=True)
        if not base.is_dir():
            raise ValueError("report base must be a directory")
    except (OSError, ValueError, RuntimeError) as exc:
        errors.append(f"report: invalid base directory: {exc}")
        return result
    result["summary"]["case_count"] = len(cases)
    seen = set()
    for index, case in enumerate(cases):
        where = f"providers[{index}]"
        if not check_fields(case, (*IDENTITY, "stages"), where, errors):
            continue
        if not all(exact_string(case[k]) for k in IDENTITY):
            errors.append(where + ": identity fields must be nonempty exact strings without control characters")
        else:
            identity = tuple(case[k] for k in IDENTITY)
            if identity in seen:
                errors.append(where + ": duplicate provider/version/media/content case")
            seen.add(identity)
        stages = case["stages"]
        if not isinstance(stages, dict) or set(stages) != set(STAGES):
            errors.append(where + ": stages must contain exactly all ten required stages")
            continue
        for stage in STAGES:
            record = stages[stage]
            label = f"{where}.stages.{stage}"
            if not check_fields(record, ("status", "evidence"), label, errors):
                continue
            status = record["status"]
            if status not in STATUSES:
                errors.append(label + ": invalid status")
            else:
                result["summary"]["stage_counts"][stage][status] += 1
            if not isinstance(record["evidence"], list):
                errors.append(label + ": evidence must be an array")
                continue
            if status == "passed" and not record["evidence"]:
                errors.append(label + ": passed requires evidence")
            for number, artifact in enumerate(record["evidence"]):
                check_artifact(artifact, case, stage, base, f"{label}.evidence[{number}]", errors)
    result["summary"]["provider_count"] = len({identity[0] for identity in seen})
    result["valid"] = not errors
    # Fail closed: invalid reports never expose any verified/accepted counts.
    if errors:
        return result
    real = report["evidenceKind"] == "real"
    for case in cases:
        passed = {s for s in STAGES if case["stages"][s]["status"] == "passed"}
        result["providers"].append({**{k: case[k] for k in IDENTITY},
            "transport_verified": real and set(STAGES[:5]) <= passed,
            "playback_verified": real and set(STAGES[:8]) <= passed,
            "offline_verified": real and set(STAGES) <= passed,
            "e2e_verified": real and set(STAGES) <= passed,
            "not_passed": [s for s in STAGES if s not in passed]})
    for metric in ("transport_verified", "playback_verified", "offline_verified", "e2e_verified"):
        groups = {}
        for case in result["providers"]:
            groups.setdefault(case["providerId"], []).append(case[metric])
        result["summary"][metric] = sum(all(values) for values in groups.values())
    result["accepted"] = real and all(case["e2e_verified"] for case in result["providers"])
    result["acceptance_reason"] = ("all supplied cases meet the artifact contract" if result["accepted"]
        else "test-only evidence cannot verify real providers" if not real
        else "required stages failed, unsupported, or not tested")
    return result


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def reject_constant(value):
    raise ValueError(f"invalid JSON constant: {value}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()
    try:
        report = json.loads(args.report.read_text(encoding="utf-8"),
                            object_pairs_hook=unique_object, parse_constant=reject_constant)
        result = validate_report(report, args.report.parent)
    except (OSError, ValueError, RuntimeError) as exc:
        result = empty_result()
        result["errors"].append(f"cannot read report: {exc}")
    print(json.dumps(result, sort_keys=True))
    return 2 if not result["valid"] else 0 if result["accepted"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
