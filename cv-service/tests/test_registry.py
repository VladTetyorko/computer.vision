"""Unit tests for cv_service.registry: discovery, routing, composite merge.

Needs the `cv` extra (imports `cv_service.inference`, which needs
`cv2`/`numpy` at module scope -- same requirement as `test_inference.py`) but
NOT `ultralytics`/`torch` -- every `YoloDetector` here is swapped out for a
fake `detector_factory`, so no real weights are ever loaded.
"""

from __future__ import annotations

from pathlib import Path

import pytest

from cv_service.concurrency import InferenceGate
from cv_service.inference import Detection, ModelUnavailableError
from cv_service.registry import ModelRegistry, detect_composite, discover_roster, short_name


class FakeDetector:
    """Stand-in for `YoloDetector`: records calls, returns a fixed result."""

    def __init__(self, model_name, *, detections=None, inference_millis=10):
        self.model_name = model_name
        self._detections = detections if detections is not None else []
        self._inference_millis = inference_millis
        self.calls: list[dict] = []

    def detect(self, **kwargs):
        self.calls.append(kwargs)
        return self._detections, self._inference_millis


def _factory_returning(detectors_by_name: dict[str, "FakeDetector"]):
    def factory(*, model_name):
        return detectors_by_name[model_name]

    return factory


def _booming_factory(*, model_name):
    raise ModelUnavailableError(f"cannot load {model_name!r}")


# --- discover_roster -------------------------------------------------------


def test_discover_roster_finds_pt_files_and_openvino_dirs(tmp_path: Path):
    (tmp_path / "yolo11n.pt").write_bytes(b"fake-weights")
    (tmp_path / "orion12l.pt").write_bytes(b"fake-weights")
    (tmp_path / "yolo11n_openvino_model").mkdir()
    (tmp_path / "not_a_model.txt").write_text("ignore me")

    roster = discover_roster(tmp_path, default_model="yolo11n.pt")

    assert roster["yolo11n.pt"] == str(tmp_path / "yolo11n.pt")
    assert roster["orion12l.pt"] == str(tmp_path / "orion12l.pt")
    assert roster["yolo11n_openvino_model"] == str(tmp_path / "yolo11n_openvino_model")
    assert "not_a_model.txt" not in roster
    assert len(roster) == 3


def test_discover_roster_picks_up_yoloe_seg_pf_checkpoint(tmp_path: Path):
    """A prompt-free open-vocabulary YOLOE checkpoint (`yoloe-*-seg-pf.pt`)
    is just another `*.pt` file, so it is auto-routable through the existing
    registry with ZERO code change -- `discover_roster` globs it exactly like
    `yolo26n.pt`/`orion12l.pt`. This test uses a tiny empty placeholder file
    (NOT real 31MB weights -- unit tests never download) to prove the glob
    includes it as a routable `model_id`."""
    (tmp_path / "yolo26n.pt").write_bytes(b"fake-weights")
    (tmp_path / "yoloe-26s-seg-pf.pt").write_bytes(b"fake-seg-pf-weights")

    roster = discover_roster(tmp_path, default_model="yolo26n.pt")

    # the YOLOE seg-pf model is discovered under its exact wire model_id, so a
    # client sending model_id="yoloe-26s-seg-pf.pt" routes straight to it.
    assert roster["yoloe-26s-seg-pf.pt"] == str(tmp_path / "yoloe-26s-seg-pf.pt")
    assert "yolo26n.pt" in roster  # default stays the fast general model


def test_discover_roster_always_includes_default_even_if_not_found(tmp_path: Path):
    roster = discover_roster(tmp_path, default_model="yolo11n.pt")

    assert roster == {"yolo11n.pt": "yolo11n.pt"}


def test_discover_roster_missing_base_dir_still_includes_default(tmp_path: Path):
    roster = discover_roster(tmp_path / "does-not-exist", default_model="yolo11n.pt")

    assert roster == {"yolo11n.pt": "yolo11n.pt"}


def test_discover_roster_ignores_openvino_dir_that_is_actually_a_file(tmp_path: Path):
    (tmp_path / "weird_openvino_model").write_text("not a directory")

    roster = discover_roster(tmp_path, default_model="yolo11n.pt")

    assert "weird_openvino_model" not in roster


# --- short_name --------------------------------------------------------


@pytest.mark.parametrize(
    "model_id,expected",
    [
        ("yolo11n.pt", "yolo11n"),
        ("orion12l.pt", "orion12l"),
        ("yolo11n_openvino_model", "yolo11n"),
        # YOLOE prompt-free seg checkpoint: only the trailing `.pt` is stripped
        # (the `-seg-pf` is part of the model's own name, not a known export
        # suffix), so it yields a sensible, distinct composite-label prefix.
        # This is correct, not a bug: the prefix only ever labels detections in
        # composite mode (e.g. "yoloe-26s-seg-pf:building"), where keeping the
        # full name disambiguates it from any other yoloe variant.
        ("yoloe-26s-seg-pf.pt", "yoloe-26s-seg-pf"),
        ("yolo", "yolo"),  # bare alias, no known suffix -- passes through
    ],
)
def test_short_name(model_id, expected):
    assert short_name(model_id) == expected


# --- ModelRegistry: construction / lazy loading -----------------------------


def test_registry_requires_default_id_in_roster():
    with pytest.raises(ValueError, match="default_id"):
        ModelRegistry(roster={"a.pt": "a.pt"}, default_id="missing.pt")


def test_registry_lazily_constructs_and_caches_detectors():
    fake_a = FakeDetector("a.pt")
    calls: list[str] = []

    def factory(*, model_name):
        calls.append(model_name)
        return fake_a

    registry = ModelRegistry(roster={"a.pt": "a.pt"}, default_id="a.pt", detector_factory=factory)
    assert registry.loaded_ids() == []  # nothing loaded yet

    first = registry.default_detector()
    second = registry.default_detector()

    assert first is fake_a
    assert second is fake_a
    assert calls == ["a.pt"]  # constructed exactly once, cached after
    assert registry.loaded_ids() == ["a.pt"]


def test_registry_roster_property_is_a_copy():
    registry = ModelRegistry(roster={"a.pt": "a.pt"}, default_id="a.pt", detector_factory=_booming_factory)

    roster = registry.roster
    roster["b.pt"] = "b.pt"

    assert "b.pt" not in registry.roster


# --- ModelRegistry.resolve: routing / unknown-id fallback -------------------


def test_resolve_known_single_id_returns_that_detector():
    fake_a = FakeDetector("a.pt")
    fake_b = FakeDetector("b.pt")
    registry = ModelRegistry(
        roster={"a.pt": "a.pt", "b.pt": "b.pt"},
        default_id="a.pt",
        detector_factory=_factory_returning({"a.pt": fake_a, "b.pt": fake_b}),
    )

    resolved = registry.resolve("b.pt")

    assert resolved == [("b.pt", fake_b)]


def test_resolve_empty_model_id_falls_back_to_default():
    fake_default = FakeDetector("a.pt")
    registry = ModelRegistry(
        roster={"a.pt": "a.pt"}, default_id="a.pt", detector_factory=_factory_returning({"a.pt": fake_default})
    )

    assert registry.resolve("") == [("a.pt", fake_default)]


def test_resolve_unknown_id_falls_back_to_default_and_warns_once(caplog):
    fake_default = FakeDetector("a.pt")
    registry = ModelRegistry(
        roster={"a.pt": "a.pt"}, default_id="a.pt", detector_factory=_factory_returning({"a.pt": fake_default})
    )

    with caplog.at_level("INFO", logger="cv_service.registry"):
        first = registry.resolve("some-other-model")
        second = registry.resolve("some-other-model")
        third = registry.resolve("yet-another-unknown")

    assert first == [("a.pt", fake_default)]
    assert second == [("a.pt", fake_default)]
    assert third == [("a.pt", fake_default)]
    unknown_warnings = [r for r in caplog.records if "not in the local registry roster" in r.message]
    # one distinct warning per distinct unknown id, not per call.
    assert len(unknown_warnings) == 2


def test_resolve_mixed_known_and_unknown_composite_keeps_known_member(caplog):
    fake_a = FakeDetector("a.pt")
    fake_default = FakeDetector("default.pt")
    registry = ModelRegistry(
        roster={"a.pt": "a.pt", "default.pt": "default.pt"},
        default_id="default.pt",
        detector_factory=_factory_returning({"a.pt": fake_a, "default.pt": fake_default}),
    )

    with caplog.at_level("INFO", logger="cv_service.registry"):
        resolved = registry.resolve("a.pt,unknown.pt")

    # the known member survives; the whole request does NOT fall back to
    # the default just because one comma-separated member was unrecognized.
    assert resolved == [("a.pt", fake_a)]


def test_resolve_model_that_fails_to_load_falls_back_to_default(caplog):
    fake_default = FakeDetector("default.pt")

    def factory(*, model_name):
        if model_name == "broken.pt":
            raise ModelUnavailableError("corrupt weights")
        return fake_default

    registry = ModelRegistry(
        roster={"broken.pt": "broken.pt", "default.pt": "default.pt"},
        default_id="default.pt",
        detector_factory=factory,
    )

    with caplog.at_level("WARNING", logger="cv_service.registry"):
        resolved = registry.resolve("broken.pt")

    assert resolved == [("default.pt", fake_default)]
    assert any("could not be loaded" in r.message for r in caplog.records)


def test_resolve_returns_empty_when_default_itself_unavailable():
    registry = ModelRegistry(roster={"a.pt": "a.pt"}, default_id="a.pt", detector_factory=_booming_factory)

    assert registry.resolve("unknown") == []
    assert registry.resolve("") == []


def test_resolve_composite_two_known_ids_preserves_request_order():
    fake_a = FakeDetector("a.pt")
    fake_b = FakeDetector("b.pt")
    registry = ModelRegistry(
        roster={"a.pt": "a.pt", "b.pt": "b.pt"},
        default_id="a.pt",
        detector_factory=_factory_returning({"a.pt": fake_a, "b.pt": fake_b}),
    )

    assert registry.resolve("b.pt,a.pt") == [("b.pt", fake_b), ("a.pt", fake_a)]


def test_resolve_strips_whitespace_around_comma_separated_ids():
    fake_a = FakeDetector("a.pt")
    fake_b = FakeDetector("b.pt")
    registry = ModelRegistry(
        roster={"a.pt": "a.pt", "b.pt": "b.pt"},
        default_id="a.pt",
        detector_factory=_factory_returning({"a.pt": fake_a, "b.pt": fake_b}),
    )

    assert registry.resolve(" a.pt , b.pt ") == [("a.pt", fake_a), ("b.pt", fake_b)]


# --- detect_composite --------------------------------------------------


def _gate() -> InferenceGate:
    return InferenceGate(max_concurrent=2)


def test_detect_composite_single_model_leaves_labels_unprefixed():
    detector = FakeDetector(
        "yolo11n.pt",
        detections=[Detection(label="person", confidence=0.9, x=0.1, y=0.1, width=0.2, height=0.2)],
        inference_millis=15,
    )

    detections, total_millis = detect_composite(
        [("yolo11n.pt", detector)],
        gate=_gate(),
        width=4,
        height=3,
        encoding="IMAGE_ENCODING_BGR24",
        data=b"\x00" * 36,
    )

    assert total_millis == 15
    assert [d.label for d in detections] == ["person"]


def test_detect_composite_multi_model_prefixes_labels_and_sums_millis():
    detector_a = FakeDetector(
        "yolo11n.pt",
        detections=[Detection(label="person", confidence=0.9, x=0.0, y=0.0, width=0.1, height=0.1)],
        inference_millis=15,
    )
    detector_b = FakeDetector(
        "orion12l.pt",
        detections=[Detection(label="tank", confidence=0.8, x=0.2, y=0.2, width=0.1, height=0.1)],
        inference_millis=45,
    )

    detections, total_millis = detect_composite(
        [("yolo11n.pt", detector_a), ("orion12l.pt", detector_b)],
        gate=_gate(),
        width=4,
        height=3,
        encoding="IMAGE_ENCODING_BGR24",
        data=b"\x00" * 36,
    )

    assert total_millis == 60  # sum of members, not overlapped
    labels = [d.label for d in detections]
    assert labels == ["yolo11n:person", "orion12l:tank"]
    # confidence/box untouched by tagging.
    tank = next(d for d in detections if d.label == "orion12l:tank")
    assert tank.confidence == pytest.approx(0.8)
    assert tank.x == pytest.approx(0.2)


def test_detect_composite_passes_confidence_threshold_through_to_each_member():
    detector_a = FakeDetector("a.pt")
    detector_b = FakeDetector("b.pt")

    detect_composite(
        [("a.pt", detector_a), ("b.pt", detector_b)],
        gate=_gate(),
        width=4,
        height=3,
        encoding="IMAGE_ENCODING_BGR24",
        data=b"\x00" * 36,
        confidence_threshold=0.6,
    )

    assert detector_a.calls[0]["confidence_threshold"] == pytest.approx(0.6)
    assert detector_b.calls[0]["confidence_threshold"] == pytest.approx(0.6)


def test_detect_composite_empty_resolved_list_returns_empty():
    detections, total_millis = detect_composite(
        [],
        gate=_gate(),
        width=4,
        height=3,
        encoding="IMAGE_ENCODING_BGR24",
        data=b"\x00" * 36,
    )

    assert detections == []
    assert total_millis == 0
