"""Two corrections every path shares: late detections, and recovered ids.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` wave W0. Both were private
methods on `StreamTrackingSession` with two callers each -- one on the
ASSOCIATE side, one on the FOLLOW side -- which is exactly the shape that
made the session the only place either could live. As free functions they
belong to neither mode.

Nothing here changed in the move: same gates, same three-case return, same
reshaping of a dormant record.
"""

from __future__ import annotations

from cv_service.tracking import reupdate as reupdate_module
from cv_service.tracking.engines.base import Box
from cv_service.tracking.memory import DormantIdentity, Recovery
from cv_service.tracking.params import TrackingParams
from cv_service.tracking.track import RecoveredIdentity, Track


def late_corrected_box(
    track: Track,
    box: Box,
    now: float,
    *,
    params: TrackingParams,
    lag_seconds: float,
    live_track_count: int,
) -> "tuple[Box, float]":
    """`(box, now)`, or `reupdate.late_correction`'s re-propagated answer
    paired with `now` too, when this frame carries a measured capture
    lag worth correcting for -- `(box, now - lag_seconds)`
    for the one case in between: a lag WAS measured but there was
    nothing honest to correct `box` with.

    Mechanically ORU (`reupdate.py`) applied to EVERY confirmed
    detection, not only after a miss: an offboard detector (§5.2, "L1
    RELAY") describes the frame it ran on, not the frame that is current
    by the time its answer lands, so booking `box` against `now` as-is
    is the same systematic lag bias `reupdate()` already deletes after
    an occlusion -- just arriving every frame instead of only some.

    **The second return value, added in the 2026-08-14 repair.** Every
    caller of this method immediately hands `box` to `TrackBook.apply()`
    for booking into `track.history` (`ObservationRing`, `history.py`)
    -- and that ring's own contract (`ObservationRing.record`'s own
    docstring) is that its timestamp must be the instant `box`'s content
    was actually true, never merely `now`. This method is the ONE place
    in the session that knows the answer for each of the three cases a
    caller cannot tell apart just by looking at `box`:

      * Correction disabled, or nothing measured this frame -- `box` is
        whatever the caller passed in, already true at `now` as far as
        this stream can tell (P5: no lag known, no correction to make).
      * `late_correction` SUCCEEDED -- `corrected` is deliberately
        re-propagated to represent position AT `now` (`late_correction`'s
        own docstring: "projects `box` forward by the SAME `lag_
        seconds`"), so `now` is correct for it too.
      * `late_correction` returned `None` -- `box` is still the RAW,
        never-corrected detection, and that content was true at its own
        capture instant, `now - lag_seconds`, not `now`.
        Booking it under `now` anyway is the exact defect `history.py`'s
        `TimedObservation` docstring documents: this is precisely the
        case that used to poison the ring during the dead zone before
        any bracket existed to correct against.

    A genuine no-op (returns `(box, now)`, P5) whenever there is nothing
    to correct with: the deployment knob is off, this frame measured no
    lag (`_frame_lag_seconds <= 0.0` -- every push-mode frame today, and
    pull mode's own first frame before a skew estimate exists) -- so a
    stream that never measures a lag, or has this disabled, is untouched
    by construction on BOTH return values, which is P7's reversibility
    proof for this mechanism without a second code path.
    """
    if not params.detection_lag_correction_enabled:
        return box, now
    if lag_seconds <= 0.0:
        return box, now
    corrected = reupdate_module.late_correction(
        track,
        track.history,
        box,
        now,
        lag_seconds,
        max_gap_millis=params.reupdate_max_gap_millis,
        max_velocity_per_second=params.reupdate_max_velocity_per_second,
        # 2026-08-15 density gate -- `the live track count` is reachable
        # here with no threading at all (unlike detections/frame, which
        # this method has no way to reach -- see `reupdate.py`'s own
        # module docstring for why that signal is a proxy, not the
        # measured quantity).
        max_track_count=params.reupdate_max_track_count,
        live_track_count=live_track_count,
        # 2026-08-15 bracket-identity check
        # (`docs/conclusions/TRACKING-RECOVERY-RESEARCH.md` §2.1) --
        # same reused-not-duplicated reasoning as the density gate
        # directly above: a bracket too implausible to trust for
        # post-occlusion ORU is too implausible to trust here either,
        # so this call site threads the SAME resolved deployment
        # values `track.py`'s own `_observe` does.
        max_shape_log_ratio=params.reupdate_max_shape_log_ratio,
        max_motion_center_distance=params.reupdate_max_motion_center_distance,
    )
    if corrected is None:
        return box, now - lag_seconds
    return corrected, now


def recovered_identity(identity: DormantIdentity, recovery: Recovery) -> RecoveredIdentity:
    """`ObjectMemory.claim()`'s own dormant record, reshaped into what
    `TrackBook._adopt` reads -- shared by `_attempt_recovery` (ASSOCIATE)
    and `_attempt_follow_recovery` (TRACK-IDENTITY-PLAN wave L4, FOLLOW).
    """
    return RecoveredIdentity(
        track_id=recovery.track_id,
        first_seen=identity.first_seen_millis / 1000.0,
        descriptor=identity.descriptor,
        velocity=identity.velocity,
        # TRACK-IDENTITY-PLAN wave L1: `identity.label` is the ELECTED
        # label `_retire`/`_settle_followed` remembered -- `_adopt`
        # re-seeds the recovered track's election around it, so the
        # operator sees the same stable name they lost, not a fresh guess
        # from this one recovering detection.
        elected_label=identity.label,
    )
