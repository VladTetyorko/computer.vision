"""What one frame of tracking produced, as the servicer reads it.

Split out of `session.py` (`docs/plans/active/CV-ORCHESTRATION-PLAN.md` wave
W0) so the response shape has an owner of its own: `orchestration/`'s
contributors all build boxes, and every one of them would otherwise import
the session module that imports them back.

Nothing here changed in the split -- same fields, same defaults, same
`box_for`/`from_track` behaviour. `session.py` re-exports both dataclasses,
so `from cv_service.tracking.session import FrameOutcome, TrackedBox` (the
servicer, the trackeval harness, the benchmarks) keeps working unchanged.

Pure stdlib, like the rest of `cv_service/tracking/`.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Optional

from cv_service.tracking.engines.base import Box
from cv_service.tracking.scheduler import REASON_UNSPECIFIED
from cv_service.tracking.track import Track


@dataclass(frozen=True)
class TrackedBox:
    """One box on the response, with its track facts or `None` for untracked.

    `track is None` is the ONE spelling of untracked on this side of the
    wire; the servicer turns it into `track_id == 0` and leaves every other
    track field at its proto3 zero value (TRACKING-ORCHESTRATION §6 rule 2).

    `identity_confidence`/`dormant_millis` (TRACKING-V2-PLAN wave C4,
    `Detection` wire fields 10/11) live HERE, not on `Track`: a recovery is
    an EVENT that happened on this one frame, not a property of the
    identity that persists across many frames the way `track_id`/`state` do.
    Both are `0.0`/`0` on every frame that is not the exact frame a track
    was recovered on -- `_run_cost_associate` is the only place either is
    ever set to something else.
    """

    label: str
    confidence: float
    box: Box
    track: Optional[Track] = None
    identity_confidence: float = 0.0
    dormant_millis: int = 0


@dataclass(frozen=True)
class FrameOutcome:
    """Everything the servicer needs to build one `DetectionResponse`.

    `boxes is None` means "echo this frame" -- the same degradation the
    servicer already applies when no model resolves.
    """

    boxes: Optional[list[TrackedBox]]
    inference_millis: int = 0
    detector_ran: bool = False
    detector_reason: str = REASON_UNSPECIFIED
    tracker_millis: int = 0
    engine_id: str = ""
    locked_track_id: int = 0
    # TRACKING-V2-PLAN wave C2/C3 -- 0 / "" on every frame this platform does
    # not compensate: OFF always; ASSOCIATE unless `cost` is the resolved
    # engine (see the module docstring); and any FOLLOW/`cost`-ASSOCIATE
    # frame where nothing was constructible.
    motion_millis: int = 0
    motion_engine_id: str = ""
    # TRACKING-V2-PLAN wave C5c -- `DetectionResponse.detector_roi` (wire
    # field 13). `True` exactly when `_roi_rescue` actually invoked `detect`
    # with a region THIS frame -- always in ADDITION to, never instead of,
    # the frame's own full-frame/duty-cycle detector pass (`detector_ran`
    # above): ASSOCIATE always runs full-frame every received frame, so a
    # `True` here means TWO detector passes served this one response, not
    # one. `False` on every frame the rescue never fired, including every
    # frame `roi_enabled` is off on (the deployment default) and every
    # frame it fired on but found nothing worth merging.
    detector_roi: bool = False
    # TRACKING-V3-PLAN wave V1 -- the capability ladder (§5). `served` is
    # always <= the resolved `TrackingParams.capability_level` (decision
    # E12's ceiling arithmetic, `cv_service.tracking.levels.resolve`);
    # `reason` is `""` exactly when nothing was actually capped, the same
    # "empty string = no degradation" convention `motion_engine_id`'s own
    # docstring establishes for that field.
    capability_level_served: int = 0
    capability_level_reason: str = ""
    # TRACKING-V3-PLAN wave V3 -- ORU (§4.2), `DetectionResponse.reupdate_
    # millis`/`.reupdated_tracks` (wire fields 22/23). Both `0` on every
    # frame that never calls `TrackBook.apply()` at all (OFF, or no engine
    # constructible) -- see `TrackBook.reset_reupdate_stats`'s own docstring
    # for why that is guaranteed rather than merely usual.
    reupdate_millis: int = 0
    reupdated_tracks: int = 0
    # TRACKING-V3-PLAN wave V6 -- `DetectionResponse.detection_lag_millis`
    # (wire field 24), §4.5. Echoed straight from the `detection_lag_millis`
    # `process()` was called with (clamped, never negative) -- independent
    # of whether back-correction actually ran this frame: this is what
    # makes the bias VISIBLE, never merely assumed corrected. `0` on a
    # stream whose caller measures no lag (every push-mode frame today).
    detection_lag_millis: int = 0


def box_for(
    detection: Any,
    track: Optional[Track] = None,
    *,
    identity_confidence: float = 0.0,
    dormant_millis: int = 0,
    box: Optional[Box] = None,
) -> TrackedBox:
    """One box on the response -- `box`, if given, else `Box(detection.x, ...)`.

    `box` (TRACKING-V3-PLAN wave V6) lets a caller that may have applied
    late-detection back-correction (`_late_corrected_box`) show what was
    actually BOOKED into the track rather than the raw, possibly-stale
    detection -- `_run_cost_associate` and `_follow_verify` are the only
    two callers that ever pass it (both build it from `track.box` AFTER
    `TrackBook.apply()`, which is exactly `observation.box`, corrected or
    not, by `_observe`'s own contract). Every other caller passes `None`
    and reproduces this function's pre-wave-V6 behavior exactly, which
    matters most for `_run_associate` (`bytetrack`): that engine's own
    `Observation.box` is ITS post-Kalman estimate, deliberately never shown
    here even absent this wave -- passing `track.box` there would be a
    genuine, unrelated behaviour change this wave does not intend to make.

    TRACK-IDENTITY-PLAN wave L1 (plan item 3): `label` is `track.elected_
    label` whenever a `track` is given (a TRACKED detection -- this frame's
    box has an identity behind it whose stable, hysteresis-gated name is
    what the operator should read), and the raw `detection.label` only for
    an UNTRACKED one (`track=None` -- ASSOCIATE below `min_hits`, or any
    detection this frame never bound to a track at all): there is no
    identity yet to elect a label over, so the newest roll is the only
    opinion that exists.
    """
    return TrackedBox(
        label=track.elected_label if track is not None else detection.label,
        confidence=detection.confidence,
        box=box if box is not None else Box(detection.x, detection.y, detection.width, detection.height),
        track=track,
        identity_confidence=identity_confidence,
        dormant_millis=dormant_millis,
    )



def from_track(track: Track) -> TrackedBox:
    # TRACK-IDENTITY-PLAN wave L1 (plan item 3): a coast frame already has
    # nothing BUT the track to read from, so the elected label was always
    # the more honest choice available here -- this call site simply picks
    # it explicitly now instead of echoing `track.label`'s raw newest roll.
    return TrackedBox(label=track.elected_label, confidence=track.confidence, box=track.box, track=track)

