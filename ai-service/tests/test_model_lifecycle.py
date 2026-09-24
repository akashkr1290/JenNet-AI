"""Remaining-gaps items 1, 11, 12, 13: evaluation tooling, training-pipeline
stages, persisted timing, and controlled registry promotion/rollback."""
from __future__ import annotations

import csv
import json
import sys
from pathlib import Path

import numpy as np
import pytest

SCRIPTS = Path(__file__).resolve().parent.parent / "scripts"
sys.path.insert(0, str(SCRIPTS))
import evaluation_gate  # noqa: E402
import model_registry as mr  # noqa: E402
import prepare_training_dataset as prep  # noqa: E402

REAL_REGISTRY = Path(__file__).resolve().parent.parent / "models" / "registry.json"


# ---------------------------------------------------------------- registry (item 13)

@pytest.fixture()
def registry(tmp_path):
    (tmp_path / "v1.pt").write_bytes(b"model-one")
    reg = {"active": "v1", "versions": [{"model_version": "v1", "manifest": {
        "file_name": "v1.pt", "sha256": mr.sha256_of(tmp_path / "v1.pt")}}]}
    path = tmp_path / "registry.json"
    path.write_text(json.dumps(reg))
    (tmp_path / "v2.pt").write_bytes(b"model-two")
    return path


def _gate_report(tmp_path, version, passed=True):
    p = tmp_path / f"gate-{version}.json"
    p.write_text(json.dumps({"candidate_version": version, "passed": passed, "reasons": [] if passed else ["worse"]}))
    return p


def test_real_repository_registry_is_valid_and_its_artifact_verifies():
    registry = mr.load(REAL_REGISTRY)
    assert mr.validate_schema(registry) == []
    active = mr.entry(registry, registry["active"])
    if (REAL_REGISTRY.parent / mr.file_of(active)).is_file():
        mr.verify_artifact(REAL_REGISTRY, active)


def test_candidate_cannot_be_promoted_without_gate_and_human_approval(registry, tmp_path):
    mr.register(registry, "v2", "v2.pt")
    assert mr.status_of(mr.load(registry), mr.entry(mr.load(registry), "v2")) == "candidate"
    with pytest.raises(mr.RegistryError, match="only approved"):
        mr.promote(registry, "v2", "ops")
    with pytest.raises(mr.RegistryError, match="gate did not pass"):
        mr.approve(registry, "v2", "reviewer", _gate_report(tmp_path, "v2", passed=False))
    with pytest.raises(mr.RegistryError, match="different version"):
        mr.approve(registry, "v2", "reviewer", _gate_report(tmp_path, "v1"))
    assert mr.load(registry)["active"] == "v1"


def test_promote_then_rollback_is_audited_and_non_destructive(registry, tmp_path):
    mr.register(registry, "v2", "v2.pt")
    mr.approve(registry, "v2", "reviewer", _gate_report(tmp_path, "v2"))
    mr.promote(registry, "v2", "ops", reason="better recall")
    reg = mr.load(registry)
    assert reg["active"] == "v2" and mr.entry(reg, "v1")["status"] == "known_good"
    mr.rollback(registry, "ops", reason="field complaints")
    reg = mr.load(registry)
    assert reg["active"] == "v1"
    assert [h["action"] for h in reg["history"]] == ["register", "approve", "promote", "rollback"]
    assert reg["history"][-1]["from_version"] == "v2" and reg["history"][-1]["to_version"] == "v1"
    assert (tmp_path / "v1.pt").read_bytes() == b"model-one" and (tmp_path / "v2.pt").exists()
    assert mr.env_lines(registry) == "YOLO_MODEL_PATH=models/v1.pt\nYOLO_MODEL_VERSION=v1"


def test_rollback_refuses_tampered_artifact_and_non_known_good_target(registry, tmp_path):
    mr.register(registry, "v2", "v2.pt")
    mr.approve(registry, "v2", "reviewer", _gate_report(tmp_path, "v2"))
    mr.promote(registry, "v2", "ops")
    (tmp_path / "v1.pt").write_bytes(b"tampered")
    with pytest.raises(mr.RegistryError, match="sha256 mismatch"):
        mr.rollback(registry, "ops")
    assert mr.load(registry)["active"] == "v2"


def test_versions_are_immutable_and_cli_refuses_with_exit_code(registry):
    mr.register(registry, "v2", "v2.pt")
    with pytest.raises(mr.RegistryError, match="immutable"):
        mr.register(registry, "v2", "v2.pt")
    assert mr.main(["--registry", str(registry), "promote", "--version", "v2", "--actor", "x"]) == 2
    assert mr.main(["--registry", str(registry), "verify"]) == 0


# ---------------------------------------------------------------- gate (item 11)

def _eval(version, map50, recalls, dataset="abc"):
    return {"model_version": version, "dataset_sha256": dataset, "overall": {"map50": map50},
            "per_class": {c: {"recall": r} for c, r in recalls.items()}}


def test_gate_passes_only_when_candidate_is_not_worse_on_the_same_dataset():
    active = _eval("v1", 0.68, {"pothole": 0.70, "garbage_overflow": 0.60})
    assert evaluation_gate.gate(_eval("v2", 0.70, {"pothole": 0.71, "garbage_overflow": 0.60}), active)["passed"]
    worse_map = evaluation_gate.gate(_eval("v2", 0.60, {"pothole": 0.71, "garbage_overflow": 0.60}), active)
    assert not worse_map["passed"] and "mAP50" in worse_map["reasons"][0]
    class_drop = evaluation_gate.gate(_eval("v2", 0.70, {"pothole": 0.50, "garbage_overflow": 0.60}), active)
    assert not class_drop["passed"] and "pothole" in class_drop["reasons"][0]
    other_data = evaluation_gate.gate(_eval("v2", 0.90, {"pothole": 0.9, "garbage_overflow": 0.9}, "zzz"), active)
    assert not other_data["passed"]


# ---------------------------------------------------------------- dataset prep (item 11)

def test_prepare_uses_only_human_confirmed_annotated_rows(tmp_path):
    images, labels, out = tmp_path / "img", tmp_path / "lbl", tmp_path / "out"
    images.mkdir(); labels.mkdir()
    rows = [("1", "POTHOLE", "VERIFIED"), ("2", "GARBAGE_OVERFLOW", "CLOSED"), ("3", "POTHOLE", "REJECTED"),
            ("4", "GENERAL", "VERIFIED"), ("5", "WATER_LEAKAGE", "RESOLVED"), ("6", "POTHOLE", "VERIFIED")]
    with open(tmp_path / "fb.csv", "w", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(["complaint_id", "ai_predicted_class", "ai_confidence", "model_version",
                    "human_final_category", "decision_status", "duplicate_flagged"])
        for cid, cat, status in rows:
            w.writerow([cid, "pothole", "80", "v1", cat, status, "false"])
            (images / f"{cid}.jpg").write_bytes(b"jpg")
    names = ["broken_street_light", "garbage_overflow", "illegal_construction", "open_manhole", "pothole", "water_leakage"]
    (labels / "1.txt").write_text("4 0.5 0.5 0.2 0.2\n")
    (labels / "2.txt").write_text("1 0.4 0.4 0.3 0.3\n")
    (labels / "6.txt").write_text("1 0.4 0.4 0.3 0.3\n")  # box is not the confirmed category -> re-annotate
    summary = prep.prepare(tmp_path / "fb.csv", images, labels, names, out, min_images=1)
    assert summary["usable"] == 2 and summary["needs_annotation"] == 2 and summary["excluded"] == 2
    assert summary["written"] and "4: pothole" in (out / "dataset.yaml").read_text()
    pending = list(csv.DictReader(open(out / "needs_annotation.csv")))
    assert sorted(r["complaint_id"] for r in pending) == ["5", "6"]
    refused = prep.prepare(tmp_path / "fb.csv", images, labels, names, tmp_path / "out2", min_images=50)
    assert refused["written"] is False and not (tmp_path / "out2" / "dataset.yaml").exists()


def test_split_assignment_is_deterministic():
    assert [prep.split_for(str(i), 42, 0.1, 0.1) for i in range(50)] == \
           [prep.split_for(str(i), 42, 0.1, 0.1) for i in range(50)]


# ---------------------------------------------------------------- config resolver (item 13)

def test_active_model_defaults_to_env_settings_and_can_follow_registry(monkeypatch, tmp_path):
    from app.config import active_model, get_settings
    s = get_settings()
    assert active_model(s) == (s.yolo_model_path, s.yolo_model_version)
    reg = tmp_path / "registry.json"
    reg.write_text(json.dumps({"active": "v9", "versions": [
        {"model_version": "v9", "manifest": {"file_name": "v9.pt", "sha256": "x"}}]}))
    monkeypatch.setattr(s, "use_model_registry", True)
    monkeypatch.setattr(s, "model_registry_path", str(reg))
    assert active_model(s) == (str(tmp_path / "v9.pt"), "v9")
    monkeypatch.setattr(s, "model_registry_path", str(tmp_path / "missing.json"))
    assert active_model(s) == (s.yolo_model_path, s.yolo_model_version)  # never blocks startup


# ---------------------------------------------------------------- evaluation (item 1) - REAL model runs

MODEL = REAL_REGISTRY.parent / "yolov11-civic-v1.0.pt"
needs_model = pytest.mark.skipif(not MODEL.is_file(), reason="model weights not present in this checkout")


def _synthetic_image(path: Path, seed: int):
    import cv2
    rng = np.random.default_rng(seed)
    img = rng.integers(60, 200, (320, 320, 3)).astype(np.uint8)
    cv2.ellipse(img, (160, 170), (80, 40), 0, 0, 360, (25, 25, 25), -1)
    cv2.imwrite(str(path), img)


@needs_model
def test_latency_measurement_runs_the_real_model(tmp_path):
    import evaluate_model
    for i in range(2):
        _synthetic_image(tmp_path / f"{i}.jpg", i)
    lat = evaluate_model.measure_latency(MODEL, tmp_path, runs=1)
    assert lat["images"] == 2 and lat["samples"] == 2 and lat["p95_ms"] >= lat["p50_ms"] > 0


@needs_model
def test_dataset_evaluation_pipeline_produces_per_class_report(tmp_path):
    """Exercises the val code path on a 2-image synthetic set. The numbers are
    meaningless and are NOT a model-quality result - this checks the tooling."""
    import evaluate_model
    for split in ("test",):
        (tmp_path / "images" / split).mkdir(parents=True)
        (tmp_path / "labels" / split).mkdir(parents=True)
        for i in range(2):
            _synthetic_image(tmp_path / "images" / split / f"{i}.jpg", i)
            (tmp_path / "labels" / split / f"{i}.txt").write_text("4 0.5 0.53 0.5 0.25\n")
    names = "\n".join(f"  {i}: {n}" for i, n in enumerate(
        ["broken_street_light", "garbage_overflow", "illegal_construction", "open_manhole", "pothole", "water_leakage"]))
    data = tmp_path / "data.yaml"
    data.write_text(f"path: {tmp_path}\ntrain: images/test\nval: images/test\ntest: images/test\nnames:\n{names}\n")
    report = evaluate_model.evaluate_dataset(MODEL, data, split="test")
    assert set(report["overall"]) == {"precision", "recall", "f1", "map50", "map50_95"}
    assert report["dataset_sha256"] == mr.sha256_of(data) and report["split"] == "test"
    for stats in report["per_class"].values():
        assert set(stats) == {"precision", "recall", "f1", "map50", "map50_95"}


def test_evaluate_cli_refuses_to_run_without_data_or_images():
    import evaluate_model
    with pytest.raises(SystemExit):
        evaluate_model.main([])


# ---------------------------------------------------------------- persisted timing (item 12)

def test_classify_raw_model_output_carries_timing_for_persistence():
    import asyncio

    import cv2
    from app.services import pipeline
    rng = np.random.default_rng(7)
    img = rng.integers(60, 200, (480, 640, 3)).astype(np.uint8)
    for x in range(0, 640, 40):
        cv2.line(img, (x, 0), (x, 480), (230, 230, 230), 2)
    result = asyncio.run(pipeline.classify(cv2.imencode(".jpg", img)[1].tobytes(), None, None, None))
    timing = result.raw_model_output["timing_ms"]
    assert timing == result.timing_ms and timing["total"] > 0
