#!/usr/bin/env python3
"""Model registry lifecycle tool - remaining-gaps items 11 (pipeline) and 13 (rollback).

The registry (models/registry.json) already recorded every model version and
which one is "active"; switching or rolling back meant hand-editing env vars
with no check and no record. This tool makes those steps controlled:

  verify    [--schema-only]           schema + sha256 of every artifact on disk
  register  --version V --file F      add a CANDIDATE (never becomes active)
  approve   --version V --approved-by NAME --gate-report R
                                      human approval; refused unless the
                                      evaluation gate report for V passed
  promote   --version V --actor NAME  make an APPROVED/KNOWN_GOOD version active
  rollback  --actor NAME [--to V]     return to a known-good version (default:
                                      the version active before the current one)

Safety rules: nothing is ever deleted or overwritten on disk; an artifact
whose sha256 does not match its manifest can never be promoted or rolled back
to; every promote/rollback/approve is appended to "history" (who, when, why).
There is deliberately NO automatic, threshold-triggered rollback - a
rollback is a human decision (see docs/AI_MODEL_EVALUATION.md). After
promote/rollback, restart ai-service with USE_MODEL_REGISTRY=true (or apply
the printed YOLO_MODEL_PATH/YOLO_MODEL_VERSION values).
"""
from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import sys
from pathlib import Path

DEFAULT_REGISTRY = Path(__file__).resolve().parent.parent / "models" / "registry.json"
STATUSES = ("candidate", "approved", "known_good", "rejected")


class RegistryError(Exception):
    pass


def load(path: Path) -> dict:
    return json.loads(Path(path).read_text())


def save(path: Path, registry: dict) -> None:
    tmp = Path(path).with_suffix(".json.tmp")
    tmp.write_text(json.dumps(registry, indent=2) + "\n")
    tmp.replace(path)  # atomic replace, never a partial file


def sha256_of(file_path: Path) -> str:
    digest = hashlib.sha256()
    with open(file_path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def entry(registry: dict, version: str) -> dict:
    for v in registry.get("versions", []):
        if v.get("model_version") == version:
            return v
    raise RegistryError(f"unknown model version: {version}")


def status_of(registry: dict, v: dict) -> str:
    # Entries created before this tool carry no status; the active one is by
    # definition the model in service, so it counts as known-good.
    return v.get("status") or ("known_good" if v.get("model_version") == registry.get("active") else "approved")


def file_of(v: dict) -> str:
    return (v.get("manifest") or {}).get("file_name") or v.get("file_name") or ""


def validate_schema(registry: dict) -> list[str]:
    problems = []
    versions = registry.get("versions")
    if not isinstance(versions, list) or not versions:
        return ["'versions' must be a non-empty list"]
    names = [v.get("model_version") for v in versions]
    if len(names) != len(set(names)):
        problems.append("duplicate model_version entries")
    for v in versions:
        if not v.get("model_version"):
            problems.append("entry without model_version")
        if not file_of(v):
            problems.append(f"{v.get('model_version')}: no file_name")
        if not (v.get("manifest") or {}).get("sha256"):
            problems.append(f"{v.get('model_version')}: no manifest.sha256")
        if v.get("status") and v["status"] not in STATUSES:
            problems.append(f"{v.get('model_version')}: invalid status {v['status']}")
    if registry.get("active") not in names:
        problems.append(f"active version {registry.get('active')!r} is not in versions")
    return problems


def verify_artifact(registry_path: Path, v: dict) -> None:
    artifact = Path(registry_path).parent / file_of(v)
    if not artifact.is_file():
        raise RegistryError(f"{v['model_version']}: artifact {artifact.name} not found")
    actual = sha256_of(artifact)
    if actual != v["manifest"]["sha256"]:
        raise RegistryError(f"{v['model_version']}: sha256 mismatch (artifact changed or corrupted)")


def _record(registry: dict, action: str, actor: str, **fields) -> None:
    registry.setdefault("history", []).append({
        "timestamp": _dt.datetime.now(_dt.timezone.utc).isoformat(timespec="seconds"),
        "action": action, "actor": actor, **fields})


def register(registry_path: Path, version: str, file_name: str, metrics_file: Path | None = None) -> dict:
    registry = load(registry_path)
    if any(v.get("model_version") == version for v in registry["versions"]):
        raise RegistryError(f"{version} already registered - versions are immutable, use a new version name")
    artifact = Path(registry_path).parent / file_name
    if not artifact.is_file():
        raise RegistryError(f"artifact {file_name} not found next to the registry")
    manifest = {"file_name": file_name, "sha256": sha256_of(artifact), "file_size_bytes": artifact.stat().st_size}
    if metrics_file:
        manifest["test_set_evaluation"] = json.loads(Path(metrics_file).read_text())
    registry["versions"].append({
        "model_version": version, "status": "candidate",
        "added_date": _dt.date.today().isoformat(), "manifest": manifest})
    _record(registry, "register", "pipeline", version=version)
    save(registry_path, registry)
    return registry


def approve(registry_path: Path, version: str, approved_by: str, gate_report: Path) -> dict:
    registry = load(registry_path)
    v = entry(registry, version)
    report = json.loads(Path(gate_report).read_text())
    if report.get("candidate_version") != version:
        raise RegistryError("gate report is for a different version")
    if report.get("passed") is not True:
        raise RegistryError(f"evaluation gate did not pass for {version}: {report.get('reasons')}")
    if not approved_by.strip():
        raise RegistryError("approved_by is required (a named human reviewer)")
    verify_artifact(registry_path, v)
    v["status"] = "approved"
    v["approved_by"] = approved_by
    v["gate_report"] = report
    _record(registry, "approve", approved_by, version=version)
    save(registry_path, registry)
    return registry


def promote(registry_path: Path, version: str, actor: str, reason: str = "") -> dict:
    registry = load(registry_path)
    v = entry(registry, version)
    if status_of(registry, v) not in ("approved", "known_good"):
        raise RegistryError(f"{version} is {status_of(registry, v)}; only approved or known-good versions can be promoted")
    verify_artifact(registry_path, v)
    previous = registry["active"]
    if previous == version:
        raise RegistryError(f"{version} is already active")
    entry(registry, previous)["status"] = "known_good"
    registry["active"] = version
    _record(registry, "promote", actor, from_version=previous, to_version=version, reason=reason)
    save(registry_path, registry)
    return registry


def rollback(registry_path: Path, actor: str, to: str | None = None, reason: str = "") -> dict:
    registry = load(registry_path)
    current = registry["active"]
    if to is None:
        targets = [h["from_version"] for h in reversed(registry.get("history", []))
                   if h.get("action") in ("promote", "rollback") and h.get("to_version") == current]
        if not targets:
            raise RegistryError("no previous active version recorded; pass --to explicitly")
        to = targets[0]
    target = entry(registry, to)
    if status_of(registry, target) != "known_good":
        raise RegistryError(f"{to} is {status_of(registry, target)}; rollback only targets known-good versions")
    if to == current:
        raise RegistryError(f"{to} is already active")
    verify_artifact(registry_path, target)
    registry["active"] = to
    _record(registry, "rollback", actor, from_version=current, to_version=to, reason=reason)
    save(registry_path, registry)
    return registry


def env_lines(registry_path: Path) -> str:
    registry = load(registry_path)
    v = entry(registry, registry["active"])
    return f"YOLO_MODEL_PATH=models/{file_of(v)}\nYOLO_MODEL_VERSION={registry['active']}"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--registry", type=Path, default=DEFAULT_REGISTRY)
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("verify"); p.add_argument("--schema-only", action="store_true")
    p = sub.add_parser("register"); p.add_argument("--version", required=True); p.add_argument("--file", required=True)
    p.add_argument("--metrics", type=Path)
    p = sub.add_parser("approve"); p.add_argument("--version", required=True); p.add_argument("--approved-by", required=True)
    p.add_argument("--gate-report", type=Path, required=True)
    p = sub.add_parser("promote"); p.add_argument("--version", required=True); p.add_argument("--actor", required=True)
    p.add_argument("--reason", default="")
    p = sub.add_parser("rollback"); p.add_argument("--actor", required=True); p.add_argument("--to")
    p.add_argument("--reason", default="")
    a = ap.parse_args(argv)
    try:
        if a.cmd == "verify":
            registry = load(a.registry)
            problems = validate_schema(registry)
            if not a.schema_only and not problems:
                for v in registry["versions"]:
                    try:
                        verify_artifact(a.registry, v)
                        print(f"OK   {v['model_version']} ({status_of(registry, v)})")
                    except RegistryError as e:
                        (problems if v["model_version"] == registry["active"] else [None]).append(str(e))
                        print(f"WARN {e}")
            for msg in filter(None, problems):
                print(f"FAIL {msg}")
            print("registry OK" if not any(problems) else "registry INVALID")
            return 1 if any(problems) else 0
        if a.cmd == "register":
            register(a.registry, a.version, a.file, a.metrics)
        elif a.cmd == "approve":
            approve(a.registry, a.version, a.approved_by, a.gate_report)
        elif a.cmd == "promote":
            promote(a.registry, a.version, a.actor, a.reason)
        elif a.cmd == "rollback":
            rollback(a.registry, a.actor, a.to, a.reason)
        print(f"{a.cmd}: done")
        if a.cmd in ("promote", "rollback"):
            print("Restart ai-service with USE_MODEL_REGISTRY=true, or set:\n" + env_lines(a.registry))
        return 0
    except RegistryError as e:
        print(f"REFUSED: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
