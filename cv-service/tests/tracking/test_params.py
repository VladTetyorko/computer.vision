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
