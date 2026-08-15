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


# -- wave C5c additions: ROI re-detection --------------------------------------
#
# Both deployment-only, same "no wire field, straight from Settings" shape as
# `follow_top_k` directly above -- `resolve()` copies them onto
# `TrackingParams` unchanged, and `session.py`'s `_roi_rescue`/`_roi_box` are
# what actually interpret them.


def test_resolve_takes_roi_settings_straight_through():
    settings = dataclasses.replace(SETTINGS, track_roi_enabled=True, track_roi_crop_factor=6.0)

    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), settings)

    assert resolved.roi_enabled is True
    assert resolved.roi_crop_factor == 6.0


def test_roi_is_enabled_by_default():
    # Shipped OFF first, deliberately, because a gated extra detector pass is
    # a real cost -- and because it regressed `clutter` by six id switches
    # until the rescue got its own IoU gate. With that gate it is better or
    # equal on every scenario and worse on none, which is the same evidence
    # bar `cost` had to clear, so it is on.
    assert Settings().track_roi_enabled is True
    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), Settings())
    assert resolved.roi_enabled is True


def test_settings_read_the_wave_c5c_env_vars(monkeypatch):
    monkeypatch.setenv("CV_TRACK_ROI_ENABLED", "1")
    monkeypatch.setenv("CV_TRACK_ROI_CROP_FACTOR", "5.5")

    settings = Settings.from_env()

    assert settings.track_roi_enabled is True
    assert settings.track_roi_crop_factor == 5.5


def test_roi_enabled_env_var_accepts_the_usual_boolean_spellings(monkeypatch):
    for spelling, expected in (
        ("true", True),
        ("YES", True),
        ("on", True),
        ("false", False),
        ("NO", False),
        ("off", False),
    ):
        monkeypatch.setenv("CV_TRACK_ROI_ENABLED", spelling)
        assert Settings.from_env().track_roi_enabled is expected


def test_garbage_wave_c5c_env_vars_fall_back_and_never_raise(monkeypatch):
    monkeypatch.setenv("CV_TRACK_ROI_ENABLED", "sort-of")
    monkeypatch.setenv("CV_TRACK_ROI_CROP_FACTOR", "not-a-number")

    settings = Settings.from_env()

    assert settings.track_roi_enabled == Settings().track_roi_enabled
    assert settings.track_roi_crop_factor == Settings().track_roi_crop_factor


def test_a_non_positive_roi_crop_factor_env_var_falls_back_to_default(monkeypatch):
    # Unlike `CV_TRACK_MEMORY_TTL_MILLIS`, `<=0` here is NOT a legitimate
    # "disable" sentinel -- that is `CV_TRACK_ROI_ENABLED`'s job -- so a
    # non-positive crop factor is treated as a misconfiguration, same as
    # every other `_parse_positive_*` knob in `config.py`.
    monkeypatch.setenv("CV_TRACK_ROI_CROP_FACTOR", "0")

    settings = Settings.from_env()

    assert settings.track_roi_crop_factor == Settings().track_roi_crop_factor


# -- wave V1 addition: the capability ladder's wire sentinel -----------------
# (TRACKING-V3-PLAN §5). Same `<=0 = server default` shape `verify_every_
# millis` uses above, with one deliberate difference exercised below: the
# RESOLVED value may itself legitimately be `0` (auto-probe), which
# `resolve()` never turns into a concrete level -- that is `session.py`'s job
# (`cv_service.tracking.levels.resolve`, not this module).


def test_capability_level_defaults_to_the_deployment_setting():
    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), SETTINGS)

    assert resolved.capability_level == SETTINGS.track_capability_level == 0


def test_a_non_positive_capability_level_request_takes_the_deployment_default():
    settings = dataclasses.replace(SETTINGS, track_capability_level=3)

    for sentinel in (0, -1, -99):
        resolved = params_module.resolve(
            TrackingRequest(mode=MODE_ASSOCIATE, capability_level=sentinel), settings
        )
        assert resolved.capability_level == 3


def test_a_positive_capability_level_request_wins_over_the_deployment_default():
    settings = dataclasses.replace(SETTINGS, track_capability_level=4)

    resolved = params_module.resolve(
        TrackingRequest(mode=MODE_ASSOCIATE, capability_level=1), settings
    )

    assert resolved.capability_level == 1


def test_the_deployment_default_may_itself_be_auto_probe():
    # Unlike every other `<=0` sentinel in this module, `0` surviving all
    # the way into `TrackingParams` is not a bug -- it is `levels.py`'s own
    # "auto-probe" value, and `resolve()` deliberately never resolves it
    # further (see this module's own docstring on `TrackingParams.
    # capability_level`).
    resolved = params_module.resolve(
        TrackingRequest(mode=MODE_ASSOCIATE, capability_level=0),
        dataclasses.replace(SETTINGS, track_capability_level=0),
    )

    assert resolved.capability_level == 0


def test_settings_read_the_wave_v1_env_var(monkeypatch):
    monkeypatch.setenv("CV_TRACK_CAPABILITY_LEVEL", "2")

    settings = Settings.from_env()

    assert settings.track_capability_level == 2


def test_garbage_wave_v1_env_var_falls_back_and_never_raises(monkeypatch):
    monkeypatch.setenv("CV_TRACK_CAPABILITY_LEVEL", "not-a-level")

    settings = Settings.from_env()

    assert settings.track_capability_level == Settings().track_capability_level


# -- wave V3 addition: reupdate_max_gap_millis (proto field 14) --------------
# (TRACKING-V3-PLAN §4.2). Same `<=0 = server default` request-level shape as
# `memory_ttl_millis` above, and the SAME "the deployment default itself may
# legitimately be non-positive" exception -- disabling ORU fleet-wide is a
# deployment choice, not something a per-request sentinel can express.


def test_a_non_positive_reupdate_gap_request_takes_the_deployment_default():
    settings = dataclasses.replace(SETTINGS, track_reupdate_max_gap_millis=9_000)

    for sentinel in (0, -1, -99):
        resolved = params_module.resolve(
            TrackingRequest(mode=MODE_FOLLOW, reupdate_max_gap_millis=sentinel), settings
        )
        assert resolved.reupdate_max_gap_millis == 9_000


def test_a_positive_reupdate_gap_request_wins_over_the_deployment_default():
    resolved = params_module.resolve(
        TrackingRequest(mode=MODE_FOLLOW, reupdate_max_gap_millis=5_000), SETTINGS
    )

    assert resolved.reupdate_max_gap_millis == 5_000


def test_a_non_positive_deployment_reupdate_gap_is_a_legitimate_disabled_value():
    # Unlike every other `<=0` sentinel in this module (except `memory_ttl_
    # millis`), a non-positive DEPLOYMENT default is not replaced by
    # anything -- it is the operator's own choice to run with ORU off
    # fleet-wide (`reupdate.py`'s own `gap_millis > max_gap_millis` check
    # rejects every gap once the ceiling is non-positive, since a gap is by
    # definition positive).
    settings = dataclasses.replace(SETTINGS, track_reupdate_max_gap_millis=0)

    resolved = params_module.resolve(TrackingRequest(mode=MODE_FOLLOW), settings)

    assert resolved.reupdate_max_gap_millis == 0


def test_settings_read_the_wave_v3_reupdate_gap_env_var(monkeypatch):
    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_GAP_MILLIS", "20000")

    settings = Settings.from_env()

    assert settings.track_reupdate_max_gap_millis == 20_000


def test_a_negative_reupdate_gap_env_var_disables_rather_than_falling_back(monkeypatch):
    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_GAP_MILLIS", "0")

    assert Settings.from_env().track_reupdate_max_gap_millis == 0


def test_garbage_reupdate_gap_env_var_falls_back_and_never_raises(monkeypatch):
    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_GAP_MILLIS", "soon")

    settings = Settings.from_env()

    assert settings.track_reupdate_max_gap_millis == Settings().track_reupdate_max_gap_millis


# -- 2026-08-14 repair, part 2: reupdate_max_velocity_per_second -------------
# (`docs/conclusions/TRACKING-BENCHMARK-RESULTS.md` §4/§8). Deployment-only,
# no wire field -- same "no per-request override to fall back FROM" shape as
# `follow_top_k`/`roi_enabled` above; `<=0` is a legitimate DISABLE value,
# same shape as `reupdate_max_gap_millis` directly above, not
# `roi_crop_factor`'s "non-positive is a misconfiguration" shape.


def test_resolve_takes_reupdate_max_velocity_per_second_straight_from_settings():
    settings = dataclasses.replace(SETTINGS, track_reupdate_max_velocity_per_second=3.5)

    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), settings)

    assert resolved.reupdate_max_velocity_per_second == 3.5


def test_reupdate_max_velocity_per_second_defaults_to_five():
    # `cv_service.config.DEFAULT_TRACK_REUPDATE_MAX_VELOCITY_PER_SECOND`'s
    # own derivation: >3x headroom over both the harness's own fastest
    # synthetic pan (0.6/sec) and `CV-RATE-BUDGET.md` §2's most aggressive
    # documented search yaw (1.5/sec).
    assert Settings().track_reupdate_max_velocity_per_second == 5.0
    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), Settings())
    assert resolved.reupdate_max_velocity_per_second == 5.0


def test_a_non_positive_deployment_velocity_bound_is_a_legitimate_disabled_value():
    # Same shape as `reupdate_max_gap_millis`'s own non-positive test above:
    # a non-positive DEPLOYMENT default is not replaced by anything -- it is
    # the operator's own choice to run the plausibility guard off fleet-wide
    # (`reupdate.py`'s own guard never fires once the bound is non-positive).
    settings = dataclasses.replace(SETTINGS, track_reupdate_max_velocity_per_second=0.0)

    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), settings)

    assert resolved.reupdate_max_velocity_per_second == 0.0


def test_settings_read_the_repair_part_2_env_var(monkeypatch):
    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_VELOCITY_PER_SECOND", "3.5")

    settings = Settings.from_env()

    assert settings.track_reupdate_max_velocity_per_second == 3.5


def test_a_non_positive_velocity_bound_env_var_disables_rather_than_falling_back(monkeypatch):
    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_VELOCITY_PER_SECOND", "0")

    assert Settings.from_env().track_reupdate_max_velocity_per_second == 0.0


def test_garbage_velocity_bound_env_var_falls_back_and_never_raises(monkeypatch):
    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_VELOCITY_PER_SECOND", "not-a-number")

    settings = Settings.from_env()

    assert (
        settings.track_reupdate_max_velocity_per_second
        == Settings().track_reupdate_max_velocity_per_second
    )


# -- 2026-08-15 density gate: reupdate_max_track_count -----------------------
# (`docs/conclusions/TRACKING-BENCHMARK-RESULTS.md` §4b/§8). Deployment-only,
# no wire field -- same "no per-request override to fall back FROM" shape as
# `reupdate_max_velocity_per_second` above; `<=0` is a legitimate DISABLE
# value, same shape as `reupdate_max_gap_millis`/`reupdate_max_velocity_per_
# second`. Unlike the velocity bound, this ships DISABLED by default (`0`) --
# no sweep against real footage has picked a threshold yet.


def test_resolve_takes_reupdate_max_track_count_straight_from_settings():
    settings = dataclasses.replace(SETTINGS, track_reupdate_max_track_count=5)

    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), settings)

    assert resolved.reupdate_max_track_count == 5


def test_reupdate_max_track_count_defaults_to_disabled():
    # `cv_service.config.DEFAULT_TRACK_REUPDATE_MAX_TRACK_COUNT` -- `0`,
    # provisional, pending the sweep against `benchmarks/` (see that
    # constant's own comment); unlike `reupdate_max_velocity_per_second`,
    # deliberately NOT derived from a documented platform bound.
    assert Settings().track_reupdate_max_track_count == 0
    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), Settings())
    assert resolved.reupdate_max_track_count == 0


def test_a_negative_deployment_track_count_ceiling_is_a_legitimate_disabled_value():
    # Same shape as `reupdate_max_velocity_per_second`'s own non-positive
    # test above: a non-positive DEPLOYMENT default is not replaced by
    # anything -- it is the operator's own choice to run the density gate
    # off fleet-wide (`reupdate.py`'s own gate never fires once the ceiling
    # is non-positive).
    settings = dataclasses.replace(SETTINGS, track_reupdate_max_track_count=-1)

    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), settings)

    assert resolved.reupdate_max_track_count == -1


def test_settings_read_the_density_gate_env_var(monkeypatch):
    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_TRACK_COUNT", "8")

    settings = Settings.from_env()

    assert settings.track_reupdate_max_track_count == 8


def test_a_non_positive_track_count_env_var_disables_rather_than_falling_back(monkeypatch):
    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_TRACK_COUNT", "0")

    assert Settings.from_env().track_reupdate_max_track_count == 0

    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_TRACK_COUNT", "-1")

    assert Settings.from_env().track_reupdate_max_track_count == -1


def test_garbage_track_count_env_var_falls_back_and_never_raises(monkeypatch):
    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_TRACK_COUNT", "not-a-number")

    settings = Settings.from_env()

    assert settings.track_reupdate_max_track_count == Settings().track_reupdate_max_track_count


# -- 2026-08-15 bracket-identity check: reupdate_max_shape_log_ratio ---------
# (`docs/conclusions/TRACKING-RECOVERY-RESEARCH.md` §2.1). Deployment-only,
# no wire field -- same "no per-request override to fall back FROM" shape as
# `reupdate_max_velocity_per_second`/`reupdate_max_track_count` above; `<=0`
# is a legitimate DISABLE value, same shape as both. Ships DISABLED by
# default (`0.0`) -- same reason `reupdate_max_track_count` does: no sweep
# against real footage has picked a value yet.


def test_resolve_takes_reupdate_max_shape_log_ratio_straight_from_settings():
    settings = dataclasses.replace(SETTINGS, track_reupdate_max_shape_log_ratio=0.7)

    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), settings)

    assert resolved.reupdate_max_shape_log_ratio == 0.7


def test_reupdate_max_shape_log_ratio_defaults_to_the_swept_value():
    """`0.40` is SWEPT, not chosen (`TRACKING-BENCHMARK-RESULTS.md` §4d) -- the
    first ORU configuration that beats not running ORU at all, at -97 IDSW and
    +0.7pp recovery across 21 real MOT17 pairs.

    Pinned here because the value is a MEASUREMENT: changing it silently would
    discard a sweep, and the accepted cost on the other side (`pan`/FOLLOW's
    `implaus_n` 0 -> 8, recorded in `tools/trackeval/BASELINE.md`) means the
    trade was made deliberately and should not drift by accident.
    """
    assert Settings().track_reupdate_max_shape_log_ratio == 0.40
    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), Settings())
    assert resolved.reupdate_max_shape_log_ratio == 0.40


def test_a_negative_deployment_shape_bound_is_a_legitimate_disabled_value():
    settings = dataclasses.replace(SETTINGS, track_reupdate_max_shape_log_ratio=-1.0)

    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), settings)

    assert resolved.reupdate_max_shape_log_ratio == -1.0


def test_settings_read_the_shape_bound_env_var(monkeypatch):
    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_SHAPE_LOG_RATIO", "0.7")

    settings = Settings.from_env()

    assert settings.track_reupdate_max_shape_log_ratio == 0.7


def test_a_non_positive_shape_bound_env_var_disables_rather_than_falling_back(monkeypatch):
    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_SHAPE_LOG_RATIO", "0")

    assert Settings.from_env().track_reupdate_max_shape_log_ratio == 0.0

    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_SHAPE_LOG_RATIO", "-1")

    assert Settings.from_env().track_reupdate_max_shape_log_ratio == -1.0


def test_garbage_shape_bound_env_var_falls_back_and_never_raises(monkeypatch):
    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_SHAPE_LOG_RATIO", "not-a-number")

    settings = Settings.from_env()

    assert (
        settings.track_reupdate_max_shape_log_ratio
        == Settings().track_reupdate_max_shape_log_ratio
    )


# -- 2026-08-15 bracket-identity check: reupdate_max_motion_center_distance --
# Same shape as `reupdate_max_shape_log_ratio` directly above -- deployment-
# only, no wire field, `<=0` disables, ships disabled by default.


def test_resolve_takes_reupdate_max_motion_center_distance_straight_from_settings():
    settings = dataclasses.replace(SETTINGS, track_reupdate_max_motion_center_distance=2.0)

    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), settings)

    assert resolved.reupdate_max_motion_center_distance == 2.0


def test_reupdate_max_motion_center_distance_defaults_to_disabled():
    assert Settings().track_reupdate_max_motion_center_distance == 0.0
    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), Settings())
    assert resolved.reupdate_max_motion_center_distance == 0.0


def test_a_negative_deployment_motion_bound_is_a_legitimate_disabled_value():
    settings = dataclasses.replace(SETTINGS, track_reupdate_max_motion_center_distance=-1.0)

    resolved = params_module.resolve(TrackingRequest(mode=MODE_ASSOCIATE), settings)

    assert resolved.reupdate_max_motion_center_distance == -1.0


def test_settings_read_the_motion_bound_env_var(monkeypatch):
    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_MOTION_CENTER_DISTANCE", "2.0")

    settings = Settings.from_env()

    assert settings.track_reupdate_max_motion_center_distance == 2.0


def test_a_non_positive_motion_bound_env_var_disables_rather_than_falling_back(monkeypatch):
    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_MOTION_CENTER_DISTANCE", "0")

    assert Settings.from_env().track_reupdate_max_motion_center_distance == 0.0

    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_MOTION_CENTER_DISTANCE", "-1")

    assert Settings.from_env().track_reupdate_max_motion_center_distance == -1.0


def test_garbage_motion_bound_env_var_falls_back_and_never_raises(monkeypatch):
    monkeypatch.setenv("CV_TRACK_REUPDATE_MAX_MOTION_CENTER_DISTANCE", "not-a-number")

    settings = Settings.from_env()

    assert (
        settings.track_reupdate_max_motion_center_distance
        == Settings().track_reupdate_max_motion_center_distance
    )
