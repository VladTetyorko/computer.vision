"""Unit tests for `cv_service.config`: `Settings.from_env()` and the
individual env-var parsers it's built from.

Pure stdlib -- no `cv`/gRPC extras needed to run these. Moved here from
`tests/test_concurrency.py` (the `CV_MAX_CONCURRENT_INFERENCES` parsing) and
`tests/test_inference.py` (the `CV_DEVICE`/`_parse_device` parsing) now that
both live in `cv_service.config` instead of being duplicated across
`cv_service.concurrency`/`cv_service.inference`.
"""

from __future__ import annotations

import pytest

from cv_service.config import (
    DEFAULT_GRPC_WORKERS,
    DEFAULT_IMGSZ,
    DEFAULT_MAX_UPLOAD_BYTES,
    DEFAULT_MODEL,
    DEFAULT_PORT,
    DEFAULT_ROLE,
    DEFAULT_SHUTDOWN_GRACE_SECONDS,
    DEFAULT_TRACK_CAPABILITY_LEVEL,
    ROLE_INFERENCE,
    ROLE_TRAINING,
    Settings,
    _default_max_concurrent_inferences,
    _parse_bool,
    _parse_capability_level,
    _parse_choice,
    _parse_device,
    _parse_imgsz,
    _parse_positive_float,
    _resolve_max_concurrent_inferences,
)

# --- _parse_bool (TRACKING-V2-PLAN wave C5c) ----------------------------------


def test_parse_bool_defaults_when_unset():
    assert _parse_bool(None, True, "X") is True
    assert _parse_bool("", False, "X") is False


@pytest.mark.parametrize("raw", ["1", "true", "TRUE", "True", "yes", "on", "  on  "])
def test_parse_bool_recognizes_true_spellings(raw):
    assert _parse_bool(raw, False, "X") is True


@pytest.mark.parametrize("raw", ["0", "false", "FALSE", "no", "off"])
def test_parse_bool_recognizes_false_spellings(raw):
    assert _parse_bool(raw, True, "X") is False


@pytest.mark.parametrize("raw", ["maybe", "2", "yesplease", " "])
def test_parse_bool_garbage_falls_back_to_default(raw):
    assert _parse_bool(raw, True, "X") is True
    assert _parse_bool(raw, False, "X") is False


# --- _parse_positive_float (TRACKING-V2-PLAN wave C5c) ------------------------


def test_parse_positive_float_defaults_when_unset():
    assert _parse_positive_float(None, 4.0, "X") == 4.0
    assert _parse_positive_float("", 4.0, "X") == 4.0


def test_parse_positive_float_honors_override():
    assert _parse_positive_float("6.5", 4.0, "X") == 6.5


@pytest.mark.parametrize("garbage", ["not-a-number", "0", "-3.0", "  "])
def test_parse_positive_float_garbage_falls_back_to_default(garbage):
    assert _parse_positive_float(garbage, 4.0, "X") == 4.0


# --- _parse_imgsz ------------------------------------------------------------


def test_parse_imgsz_defaults_when_unset():
    assert _parse_imgsz(None) == DEFAULT_IMGSZ
    assert _parse_imgsz("") == DEFAULT_IMGSZ


def test_parse_imgsz_honors_override():
    assert _parse_imgsz("320") == 320


@pytest.mark.parametrize("garbage", ["not-a-number", "0", "-32", "  "])
def test_parse_imgsz_garbage_falls_back_to_default(garbage):
    assert _parse_imgsz(garbage) == DEFAULT_IMGSZ


# --- _parse_device -----------------------------------------------------------


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (None, None),
        ("", None),
        ("   ", None),
        ("cpu", "cpu"),
        ("cuda", "cuda"),
        ("cuda:0", "cuda:0"),
        ("0", "0"),
        ("  cuda:0  ", "cuda:0"),
    ],
)
def test_parse_device(raw, expected):
    assert _parse_device(raw) == expected


# --- _resolve_max_concurrent_inferences / _default_max_concurrent_inferences -


def test_resolve_max_concurrent_defaults_when_unset():
    default = _default_max_concurrent_inferences()
    assert _resolve_max_concurrent_inferences(None) == default
    assert _resolve_max_concurrent_inferences("") == default


def test_resolve_max_concurrent_honors_override():
    assert _resolve_max_concurrent_inferences("5") == 5


@pytest.mark.parametrize("garbage", ["not-a-number", "0", "-3", "  "])
def test_resolve_max_concurrent_garbage_falls_back_to_default(garbage):
    assert _resolve_max_concurrent_inferences(garbage) == _default_max_concurrent_inferences()


def test_default_max_concurrent_is_at_least_one_and_at_most_two():
    # min(2, cpu_count // 2), floored at 1 -- see the function's own
    # docstring for the full reasoning (verified torch intra-op thread
    # count on the dev box this task ran in).
    value = _default_max_concurrent_inferences()
    assert 1 <= value <= 2


# --- _parse_capability_level (TRACKING-V3-PLAN wave V1) -----------------------


def test_parse_capability_level_defaults_when_unset():
    assert _parse_capability_level(None, DEFAULT_TRACK_CAPABILITY_LEVEL, "X") == 0
    assert _parse_capability_level("", 2, "X") == 2


@pytest.mark.parametrize("raw", ["0", "1", "2", "3", "4", "5"])
def test_parse_capability_level_accepts_the_whole_ladder(raw):
    assert _parse_capability_level(raw, 0, "X") == int(raw)


@pytest.mark.parametrize("raw", ["-1", "6", "99", "not-a-number", " "])
def test_parse_capability_level_out_of_range_or_garbage_falls_back_to_default(raw):
    assert _parse_capability_level(raw, 3, "X") == 3


def test_parse_capability_level_zero_is_auto_probe_not_a_typo_to_reject():
    # Unlike `_parse_positive_int`, `0` is a legitimate value here -- it is
    # `levels.py`'s own auto-probe sentinel, not "unset".
    assert _parse_capability_level("0", 4, "X") == 0


def test_the_valid_range_stays_in_sync_with_levels_max_level():
    # `_parse_capability_level` duplicates the `1`-`5` bound rather than
    # importing `cv_service.tracking.levels` (config.py's own "zero internal
    # imports" precedent) -- this guards the duplication against silently
    # drifting if `levels.py` ever grows a sixth tier.
    from cv_service.tracking.levels import MAX_LEVEL, MIN_LEVEL

    assert MIN_LEVEL == 1
    assert MAX_LEVEL == 5
    assert _parse_capability_level(str(MAX_LEVEL), 0, "X") == MAX_LEVEL
    assert _parse_capability_level(str(MAX_LEVEL + 1), 0, "X") == 0


# --- Settings.from_env() ------------------------------------------------------


def _clear_cv_env(monkeypatch):
    for var in (
        "CV_PORT",
        "CV_MODEL",
        "CV_IMGSZ",
        "CV_DEVICE",
        "CV_MAX_CONCURRENT_INFERENCES",
        "CV_DATASET_DIR",
        "CV_MODEL_DIR",
        "CV_MAX_UPLOAD_BYTES",
        "CV_GRPC_WORKERS",
        "CV_SHUTDOWN_GRACE",
        "CV_SERVICE_ROLE",
    ):
        monkeypatch.delenv(var, raising=False)


def test_settings_from_env_defaults_match_historical_literals(monkeypatch):
    """Every default must equal the literal it replaces (the layering-refactor
    behavior guardrail) -- this pins each one explicitly rather than just
    trusting the dataclass field defaults."""
    _clear_cv_env(monkeypatch)

    settings = Settings.from_env()

    assert settings.port == DEFAULT_PORT == 50051
    assert settings.model == DEFAULT_MODEL == "yolo26n.pt"
    assert settings.imgsz == DEFAULT_IMGSZ == 416
    assert settings.device is None
    assert 1 <= settings.max_concurrent_inferences <= 2
    assert settings.dataset_dir.name == "datasets"
    assert settings.max_upload_bytes == DEFAULT_MAX_UPLOAD_BYTES == 2 * 1024 * 1024 * 1024
    assert settings.grpc_workers == DEFAULT_GRPC_WORKERS == 10
    assert settings.shutdown_grace_seconds == DEFAULT_SHUTDOWN_GRACE_SECONDS == 5
    assert settings.role == DEFAULT_ROLE == "all"


def test_settings_from_env_honors_every_override(monkeypatch, tmp_path):
    _clear_cv_env(monkeypatch)
    monkeypatch.setenv("CV_PORT", "60000")
    monkeypatch.setenv("CV_MODEL", "custom.pt")
    monkeypatch.setenv("CV_IMGSZ", "320")
    monkeypatch.setenv("CV_DEVICE", "cuda:0")
    monkeypatch.setenv("CV_MAX_CONCURRENT_INFERENCES", "4")
    monkeypatch.setenv("CV_DATASET_DIR", str(tmp_path / "datasets"))
    monkeypatch.setenv("CV_MODEL_DIR", str(tmp_path / "models"))
    monkeypatch.setenv("CV_MAX_UPLOAD_BYTES", "1024")
    monkeypatch.setenv("CV_GRPC_WORKERS", "3")
    monkeypatch.setenv("CV_SHUTDOWN_GRACE", "1")
    monkeypatch.setenv("CV_SERVICE_ROLE", "inference")

    settings = Settings.from_env()

    assert settings.port == 60000
    assert settings.model == "custom.pt"
    assert settings.imgsz == 320
    assert settings.device == "cuda:0"
    assert settings.max_concurrent_inferences == 4
    assert settings.dataset_dir == tmp_path / "datasets"
    assert settings.model_dir == tmp_path / "models"
    assert settings.max_upload_bytes == 1024
    assert settings.grpc_workers == 3
    assert settings.shutdown_grace_seconds == 1
    assert settings.role == "inference"


@pytest.mark.parametrize(
    "var", ["CV_PORT", "CV_MAX_UPLOAD_BYTES", "CV_GRPC_WORKERS", "CV_SHUTDOWN_GRACE"]
)
@pytest.mark.parametrize("garbage", ["not-a-number", "0", "-1", "  "])
def test_settings_from_env_new_int_knobs_fall_back_to_default_on_garbage(monkeypatch, var, garbage):
    _clear_cv_env(monkeypatch)
    monkeypatch.setenv(var, garbage)

    settings = Settings.from_env()

    defaults = {
        "CV_PORT": DEFAULT_PORT,
        "CV_MAX_UPLOAD_BYTES": DEFAULT_MAX_UPLOAD_BYTES,
        "CV_GRPC_WORKERS": DEFAULT_GRPC_WORKERS,
        "CV_SHUTDOWN_GRACE": DEFAULT_SHUTDOWN_GRACE_SECONDS,
    }
    fields = {
        "CV_PORT": "port",
        "CV_MAX_UPLOAD_BYTES": "max_upload_bytes",
        "CV_GRPC_WORKERS": "grpc_workers",
        "CV_SHUTDOWN_GRACE": "shutdown_grace_seconds",
    }
    assert getattr(settings, fields[var]) == defaults[var]


def test_settings_from_env_dataset_dir_and_model_dir_default_under_checkout(monkeypatch):
    _clear_cv_env(monkeypatch)

    settings = Settings.from_env()

    # cv-service/ checkout dir -- both default under the same directory
    # `Path(__file__).resolve().parent.parent` in config.py computes.
    assert settings.dataset_dir.parent == settings.model_dir
    assert settings.model_dir.name == "cv-service"


def test_settings_is_frozen():
    settings = Settings()
    with pytest.raises(Exception):
        settings.port = 1234  # type: ignore[misc]


# --- role / CV_SERVICE_ROLE (docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md R6) -----


def test_parse_choice_accepts_a_known_choice():
    assert _parse_choice("inference", DEFAULT_ROLE, ("all", "inference", "training"), "X") == "inference"


def test_parse_choice_is_case_insensitive():
    assert _parse_choice("INFERENCE", DEFAULT_ROLE, ("all", "inference", "training"), "X") == "inference"


def test_parse_choice_defaults_when_unset():
    assert _parse_choice(None, DEFAULT_ROLE, ("all", "inference", "training"), "X") == DEFAULT_ROLE
    assert _parse_choice("", DEFAULT_ROLE, ("all", "inference", "training"), "X") == DEFAULT_ROLE


def test_parse_choice_falls_back_to_default_on_an_unknown_value():
    assert _parse_choice("bogus", DEFAULT_ROLE, ("all", "inference", "training"), "X") == DEFAULT_ROLE


def test_settings_from_env_role_defaults_to_all(monkeypatch):
    _clear_cv_env(monkeypatch)

    settings = Settings.from_env()

    assert settings.role == "all"


@pytest.mark.parametrize("raw,expected", [("inference", ROLE_INFERENCE), ("TRAINING", ROLE_TRAINING), ("all", "all")])
def test_settings_from_env_honors_cv_service_role(monkeypatch, raw, expected):
    _clear_cv_env(monkeypatch)
    monkeypatch.setenv("CV_SERVICE_ROLE", raw)

    settings = Settings.from_env()

    assert settings.role == expected


def test_settings_from_env_role_falls_back_to_default_on_garbage(monkeypatch):
    _clear_cv_env(monkeypatch)
    monkeypatch.setenv("CV_SERVICE_ROLE", "not-a-real-role")

    settings = Settings.from_env()

    assert settings.role == DEFAULT_ROLE
