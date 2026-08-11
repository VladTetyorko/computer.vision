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
