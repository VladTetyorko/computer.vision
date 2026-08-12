"""`cv_service.tracking.params` -- sentinel resolution and the config layering.

Pure stdlib: no cv2, no frames, no gRPC.
"""

from __future__ import annotations

import dataclasses

from cv_service.config import Settings
from cv_service.tracking import params as params_module
from cv_service.tracking.params import (
    MODE_ASSOCIATE,
    MODE_FOLLOW,
    MODE_OFF,
    MODE_UNSPECIFIED,
    TrackingRequest,
)

SETTINGS = Settings()


def test_absent_config_resolves_to_off():
    resolved = params_module.resolve(TrackingRequest(), SETTINGS)

    assert resolved.mode == MODE_OFF
    assert resolved.active is False


def test_unspecified_mode_is_off_not_an_error():
    resolved = params_module.resolve(TrackingRequest(mode=MODE_UNSPECIFIED), SETTINGS)

    assert resolved.mode == MODE_OFF


def test_a_mode_this_build_does_not_know_degrades_to_off():
    resolved = params_module.resolve(TrackingRequest(mode="TRACKING_MODE_TELEPATHY"), SETTINGS)

    assert resolved.mode == MODE_OFF


def test_non_positive_sentinels_take_the_deployment_defaults():
    resolved = params_module.resolve(
        TrackingRequest(
            mode=MODE_ASSOCIATE,
            verify_every_millis=0,
            redetect_iou_threshold=0.0,
            max_age_frames=-1,
            min_hits=0,
        ),
        SETTINGS,
    )

    assert resolved.verify_every_millis == SETTINGS.track_verify_millis
    assert resolved.redetect_iou_threshold == SETTINGS.track_iou
    assert resolved.max_age_frames == SETTINGS.track_max_age_frames
    assert resolved.min_hits == SETTINGS.track_min_hits


def test_the_request_wins_over_the_deployment_default():
    resolved = params_module.resolve(
        TrackingRequest(
            mode=MODE_FOLLOW,
            engine_id="ncc",
            verify_every_millis=500,
            redetect_iou_threshold=0.55,
            max_age_frames=9,
            min_hits=1,
        ),
        SETTINGS,
    )

    assert resolved.engine_id == "ncc"
    assert resolved.verify_every_millis == 500
    assert resolved.redetect_iou_threshold == 0.55
    assert resolved.max_age_frames == 9
    assert resolved.min_hits == 1


def test_the_default_engine_is_per_mode():
    settings = dataclasses.replace(
        SETTINGS, track_associate_engine="assoc-x", track_follow_engine="follow-y"
    )

    assert params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), settings).engine_id == "assoc-x"
    assert params_module.resolve(TrackingRequest(mode=MODE_FOLLOW), settings).engine_id == "follow-y"


def test_settings_read_the_track_env_vars(monkeypatch):
    monkeypatch.setenv("CV_TRACK_ASSOCIATE_ENGINE", "bytetrack2")
    monkeypatch.setenv("CV_TRACK_FOLLOW_ENGINE", "ncc")
    monkeypatch.setenv("CV_TRACK_VERIFY_MS", "750")
    monkeypatch.setenv("CV_TRACK_IOU", "0.45")
    monkeypatch.setenv("CV_TRACK_MAX_AGE", "12")
    monkeypatch.setenv("CV_TRACK_MIN_HITS", "5")

    settings = Settings.from_env()

    assert settings.track_associate_engine == "bytetrack2"
    assert settings.track_follow_engine == "ncc"
    assert settings.track_verify_millis == 750
    assert settings.track_iou == 0.45
    assert settings.track_max_age_frames == 12
    assert settings.track_min_hits == 5


def test_a_percent_in_the_fraction_knob_falls_back_rather_than_clamping(monkeypatch):
    # The likeliest mistake is writing the REST layer's percent (30) into the
    # wire layer's fraction; clamping it to 1.0 would silently produce a
    # tracker that never re-anchors.
    monkeypatch.setenv("CV_TRACK_IOU", "30")

    assert Settings.from_env().track_iou == Settings().track_iou


def test_garbage_track_env_vars_fall_back_and_never_raise(monkeypatch):
    monkeypatch.setenv("CV_TRACK_VERIFY_MS", "soon")
    monkeypatch.setenv("CV_TRACK_MIN_HITS", "-4")
    monkeypatch.setenv("CV_TRACK_IOU", "")
    monkeypatch.setenv("CV_TRACK_FOLLOW_ENGINE", "   ")

    settings = Settings.from_env()

    assert settings.track_verify_millis == Settings().track_verify_millis
    assert settings.track_min_hits == Settings().track_min_hits
    assert settings.track_iou == Settings().track_iou
    assert settings.track_follow_engine == Settings().track_follow_engine


# -- wave C1 additions: no wire sentinel, straight from Settings ------------


def test_resolve_takes_track_max_age_millis_and_min_tracker_confidence_from_settings():
    settings = dataclasses.replace(
        SETTINGS, track_max_age_millis=4242, track_min_tracker_confidence=0.42
    )

    resolved = params_module.resolve(TrackingRequest(mode=MODE_FOLLOW), settings)

    assert resolved.track_max_age_millis == 4242
    assert resolved.min_tracker_confidence == 0.42


def test_settings_read_the_wave_c1_env_vars(monkeypatch):
    monkeypatch.setenv("CV_TRACK_MAX_AGE_MILLIS", "9000")
    monkeypatch.setenv("CV_TRACK_MIN_TRACKER_CONFIDENCE", "0.65")

    settings = Settings.from_env()

    assert settings.track_max_age_millis == 9000
    assert settings.track_min_tracker_confidence == 0.65


def test_garbage_wave_c1_env_vars_fall_back_and_never_raise(monkeypatch):
    monkeypatch.setenv("CV_TRACK_MAX_AGE_MILLIS", "soon")
    monkeypatch.setenv("CV_TRACK_MIN_TRACKER_CONFIDENCE", "70")  # percent, not fraction

    settings = Settings.from_env()

    assert settings.track_max_age_millis == Settings().track_max_age_millis
    assert settings.track_min_tracker_confidence == Settings().track_min_tracker_confidence


# -- wave C2 addition: motion_engine_id (proto field 8) ----------------------


def test_a_blank_motion_engine_id_takes_the_deployment_default():
    settings = dataclasses.replace(SETTINGS, track_motion_engine="pose")

    resolved = params_module.resolve(TrackingRequest(mode=MODE_FOLLOW), settings)

    assert resolved.motion_engine_id == "pose"


def test_a_requested_motion_engine_id_wins_over_the_deployment_default():
    resolved = params_module.resolve(
        TrackingRequest(mode=MODE_FOLLOW, motion_engine_id="pose"), SETTINGS
    )

    assert resolved.motion_engine_id == "pose"


def test_an_explicit_off_passes_through_rather_than_falling_back():
    # "off" is a legitimate resolved value (TRACKING-V2-PLAN §2's wire
    # docstring), not a sentinel resolve() rewrites -- unlike a blank
    # engine_id, it must reach `session.py` as "off", not as whatever
    # CV_TRACK_MOTION_ENGINE happens to default to.
    settings = dataclasses.replace(SETTINGS, track_motion_engine="flow")

    resolved = params_module.resolve(
        TrackingRequest(mode=MODE_FOLLOW, motion_engine_id="off"), settings
    )

    assert resolved.motion_engine_id == "off"


def test_settings_read_the_motion_engine_env_var(monkeypatch):
    monkeypatch.setenv("CV_TRACK_MOTION_ENGINE", "pose")

    assert Settings.from_env().track_motion_engine == "pose"


def test_a_blank_motion_engine_env_var_falls_back_to_flow(monkeypatch):
    monkeypatch.setenv("CV_TRACK_MOTION_ENGINE", "   ")

    assert Settings.from_env().track_motion_engine == Settings().track_motion_engine == "flow"


# -- wave C3 addition: appearance_engine_id (proto field 9) ------------------


def test_a_blank_appearance_engine_id_takes_the_deployment_default():
    settings = dataclasses.replace(SETTINGS, track_appearance_engine="off")

    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), settings)

    assert resolved.appearance_engine_id == "off"


def test_a_requested_appearance_engine_id_wins_over_the_deployment_default():
    resolved = params_module.resolve(
        TrackingRequest(mode=MODE_ASSOCIATE, appearance_engine_id="histogram"),
        dataclasses.replace(SETTINGS, track_appearance_engine="off"),
    )

    assert resolved.appearance_engine_id == "histogram"


def test_an_explicit_appearance_off_passes_through_rather_than_falling_back():
    settings = dataclasses.replace(SETTINGS, track_appearance_engine="histogram")

    resolved = params_module.resolve(
        TrackingRequest(mode=MODE_ASSOCIATE, appearance_engine_id="off"), settings
    )

    assert resolved.appearance_engine_id == "off"


def test_settings_read_the_appearance_engine_env_var(monkeypatch):
    monkeypatch.setenv("CV_TRACK_APPEARANCE_ENGINE", "off")

    assert Settings.from_env().track_appearance_engine == "off"


def test_a_blank_appearance_engine_env_var_falls_back_to_the_default(monkeypatch):
    monkeypatch.setenv("CV_TRACK_APPEARANCE_ENGINE", "   ")

    assert Settings.from_env().track_appearance_engine == Settings().track_appearance_engine


# -- wave C3 addition: cost weights/gates (no wire field, Settings-only) -----


def test_resolve_builds_cost_weights_and_gates_from_settings():
    settings = dataclasses.replace(
        SETTINGS,
        track_cost_weight_iou=0.7,
        track_cost_weight_appearance=0.3,
        track_cost_weight_label=0.1,
        track_cost_gate_min_iou=0.05,
        track_cost_gate_max_appearance=0.55,
        track_cost_gate_max_cost=3.0,
        track_cost_gate_high_confidence=0.4,
    )

    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), settings)

    assert resolved.cost_weights.iou == 0.7
    assert resolved.cost_weights.appearance == 0.3
    assert resolved.cost_weights.label == 0.1
    assert resolved.cost_gates.min_iou == 0.05
    assert resolved.cost_gates.max_appearance == 0.55
    assert resolved.cost_gates.max_cost == 3.0
    assert resolved.cost_gates.high_confidence == 0.4


def test_settings_read_the_cost_weight_and_gate_env_vars(monkeypatch):
    monkeypatch.setenv("CV_TRACK_COST_WEIGHT_IOU", "0.6")
    monkeypatch.setenv("CV_TRACK_COST_WEIGHT_APPEARANCE", "0.9")
    monkeypatch.setenv("CV_TRACK_COST_WEIGHT_LABEL", "0.2")
    monkeypatch.setenv("CV_TRACK_COST_GATE_MIN_IOU", "0.1")
    monkeypatch.setenv("CV_TRACK_COST_GATE_MAX_APPEARANCE", "0.8")
    monkeypatch.setenv("CV_TRACK_COST_GATE_MAX_COST", "5")
    monkeypatch.setenv("CV_TRACK_COST_GATE_HIGH_CONFIDENCE", "0.5")

    settings = Settings.from_env()

    assert settings.track_cost_weight_iou == 0.6
    assert settings.track_cost_weight_appearance == 0.9
    assert settings.track_cost_weight_label == 0.2
    assert settings.track_cost_gate_min_iou == 0.1
    assert settings.track_cost_gate_max_appearance == 0.8
    assert settings.track_cost_gate_max_cost == 5.0
    assert settings.track_cost_gate_high_confidence == 0.5


def test_a_gate_of_exactly_zero_is_honoured_not_rejected(monkeypatch):
    # Unlike `CV_TRACK_IOU` (`_parse_unit_fraction`, which forbids 0 because
    # it would silently mean "match anything" for a FOLLOW re-anchor), a
    # cost gate's `0.0` is a normal "this gate is off" configuration.
    monkeypatch.setenv("CV_TRACK_COST_GATE_MIN_IOU", "0")

    assert Settings.from_env().track_cost_gate_min_iou == 0.0


def test_garbage_cost_weight_and_gate_env_vars_fall_back_and_never_raise(monkeypatch):
    monkeypatch.setenv("CV_TRACK_COST_WEIGHT_IOU", "not-a-number")
    monkeypatch.setenv("CV_TRACK_COST_WEIGHT_APPEARANCE", "-1")
    monkeypatch.setenv("CV_TRACK_COST_GATE_MIN_IOU", "1.5")
    monkeypatch.setenv("CV_TRACK_COST_GATE_MAX_COST", "-2")

    settings = Settings.from_env()

    assert settings.track_cost_weight_iou == Settings().track_cost_weight_iou
    assert settings.track_cost_weight_appearance == Settings().track_cost_weight_appearance
    assert settings.track_cost_gate_min_iou == Settings().track_cost_gate_min_iou
    assert settings.track_cost_gate_max_cost == Settings().track_cost_gate_max_cost


# -- wave C4 addition: memory_ttl_millis (proto field 10) + MemoryParams -----


def test_a_non_positive_memory_ttl_request_takes_the_deployment_default():
    settings = dataclasses.replace(SETTINGS, track_memory_ttl_millis=9_000)

    resolved = params_module.resolve(
        TrackingRequest(mode=MODE_ASSOCIATE, memory_ttl_millis=0), settings
    )

    assert resolved.memory_params.ttl_millis == 9_000


def test_a_requested_memory_ttl_wins_over_the_deployment_default():
    resolved = params_module.resolve(
        TrackingRequest(mode=MODE_ASSOCIATE, memory_ttl_millis=5_000), SETTINGS
    )

    assert resolved.memory_params.ttl_millis == 5_000


def test_a_non_positive_deployment_ttl_is_a_legitimate_disabled_value():
    # Unlike every other `<=0` sentinel in this module, a non-positive
    # DEPLOYMENT default is not replaced by anything -- it is the operator's
    # own choice to run with no gallery at all (`session.py`'s `_resolve_
    # memory` is what turns this into a genuine no-op).
    settings = dataclasses.replace(SETTINGS, track_memory_ttl_millis=0)

    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), settings)

    assert resolved.memory_params.ttl_millis == 0


def test_resolve_builds_memory_params_from_settings():
    settings = dataclasses.replace(
        SETTINGS,
        track_memory_capacity=8,
        track_memory_gallery_size=2,
        track_memory_max_appearance_distance=0.3,
        track_memory_max_speed=0.5,
        track_memory_blend_alpha=0.6,
        track_memory_min_confidence=0.4,
    )

    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), settings)

    assert resolved.memory_params.capacity == 8
    assert resolved.memory_params.gallery_size == 2
    assert resolved.memory_params.max_appearance_distance == 0.3
    assert resolved.memory_params.max_speed == 0.5
    assert resolved.memory_params.blend_alpha == 0.6
    assert resolved.memory_params.min_confidence == 0.4


def test_settings_read_the_memory_env_vars(monkeypatch):
    monkeypatch.setenv("CV_TRACK_MEMORY_TTL_MILLIS", "9000")
    monkeypatch.setenv("CV_TRACK_MEMORY_CAPACITY", "16")
    monkeypatch.setenv("CV_TRACK_MEMORY_GALLERY_SIZE", "6")
    monkeypatch.setenv("CV_TRACK_MEMORY_MAX_APPEARANCE_DISTANCE", "0.5")
    monkeypatch.setenv("CV_TRACK_MEMORY_MAX_SPEED", "2.0")
    monkeypatch.setenv("CV_TRACK_MEMORY_BLEND_ALPHA", "0.8")
    monkeypatch.setenv("CV_TRACK_MEMORY_MIN_CONFIDENCE", "0.5")

    settings = Settings.from_env()

    assert settings.track_memory_ttl_millis == 9000
    assert settings.track_memory_capacity == 16
    assert settings.track_memory_gallery_size == 6
    assert settings.track_memory_max_appearance_distance == 0.5
    assert settings.track_memory_max_speed == 2.0
    assert settings.track_memory_blend_alpha == 0.8
    assert settings.track_memory_min_confidence == 0.5


def test_a_negative_memory_ttl_env_var_disables_rather_than_falling_back(monkeypatch):
    # `CV_TRACK_MEMORY_TTL_MILLIS` is the ONE knob in this file where a
    # non-positive value is honoured, not replaced -- see `config.py`'s
    # `_parse_int_allow_nonpositive`.
    monkeypatch.setenv("CV_TRACK_MEMORY_TTL_MILLIS", "0")

    assert Settings.from_env().track_memory_ttl_millis == 0


def test_garbage_memory_env_vars_fall_back_and_never_raise(monkeypatch):
    monkeypatch.setenv("CV_TRACK_MEMORY_TTL_MILLIS", "soon")
    monkeypatch.setenv("CV_TRACK_MEMORY_CAPACITY", "-4")
    monkeypatch.setenv("CV_TRACK_MEMORY_MAX_APPEARANCE_DISTANCE", "1.5")
    monkeypatch.setenv("CV_TRACK_MEMORY_MIN_CONFIDENCE", "not-a-number")

    settings = Settings.from_env()

    assert settings.track_memory_ttl_millis == Settings().track_memory_ttl_millis
    assert settings.track_memory_capacity == Settings().track_memory_capacity
    assert (
        settings.track_memory_max_appearance_distance
        == Settings().track_memory_max_appearance_distance
    )
    assert settings.track_memory_min_confidence == Settings().track_memory_min_confidence


# -- wave C5b additions: session pool + multi-target FOLLOW ------------------
#
# All three are deployment-only, same "no wire field, straight from Settings"
# shape as `track_max_age_millis`/`min_tracker_confidence` (wave C1) above --
# `CV_TRACK_SESSION_GRACE_MILLIS`/`CV_TRACK_SESSION_CAPACITY` are read
# directly by `cv_service.tracking.sessions.SessionRegistry` (never by
# `resolve()` -- see that module's own tests), `CV_TRACK_FOLLOW_TOP_K` is
# the one of the three that DOES flow through `TrackingParams.follow_top_k`.


def test_resolve_takes_follow_top_k_from_settings():
    settings = dataclasses.replace(SETTINGS, track_follow_top_k=5)

    resolved = params_module.resolve(TrackingRequest(mode=MODE_FOLLOW), settings)

    assert resolved.follow_top_k == 5


def test_follow_top_k_defaults_to_one():
    # The wave's own safety net: shipping opt-in (see `config.py`'s
    # `DEFAULT_TRACK_FOLLOW_TOP_K` for why) means today's exact single-
    # target FOLLOW behaviour must be what a fresh deployment gets.
    assert Settings().track_follow_top_k == 1


def test_settings_read_the_wave_c5b_env_vars(monkeypatch):
    monkeypatch.setenv("CV_TRACK_SESSION_GRACE_MILLIS", "5000")
    monkeypatch.setenv("CV_TRACK_SESSION_CAPACITY", "128")
    monkeypatch.setenv("CV_TRACK_FOLLOW_TOP_K", "4")

    settings = Settings.from_env()

    assert settings.track_session_grace_millis == 5000
    assert settings.track_session_capacity == 128
    assert settings.track_follow_top_k == 4


def test_garbage_wave_c5b_env_vars_fall_back_and_never_raise(monkeypatch):
    monkeypatch.setenv("CV_TRACK_SESSION_GRACE_MILLIS", "soon")
    monkeypatch.setenv("CV_TRACK_SESSION_CAPACITY", "-4")
    monkeypatch.setenv("CV_TRACK_FOLLOW_TOP_K", "not-a-number")

    settings = Settings.from_env()

    assert settings.track_session_grace_millis == Settings().track_session_grace_millis
    assert settings.track_session_capacity == Settings().track_session_capacity
    assert settings.track_follow_top_k == Settings().track_follow_top_k
