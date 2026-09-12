"""`object_states` -- this frame's `ObjectState[]`, built from facts the rest
of the frame has already computed (CV-ORCHESTRATION wave W1, plan §4.5).

**Why the mirror is built here and not inside `Aggregator`.** The plan says
"the aggregator fills `objects[]`", and the aggregator is indeed the sole
writer of `Track`. But two of the mirror's fields are not track facts at
all: `provenance.contributors` comes from this frame's `FrameLedger`, which
the aggregator never receives (the orchestrator owns it and hands it to no
contributor), and `belief.confidence_raw` comes from the response boxes the
aggregator has only just produced. Closing that gap by handing the
aggregator a ledger reference would give the one class that MUTATES track
state a second responsibility -- describing a frame -- plus a reference to
the very artifact that records what it did. Instead this is a pure function
`session.process()` calls after the run. Recorded as a deviation from §4.5
in `cv/cv-service/MODULE.md`.

**This function is READ-ONLY, and that is load-bearing.** It never mutates a
`Track`, the book, the gallery, the `LockArbiter`, the `FrameContext` or the
`FrameLedger`. A describe-step that changed what it describes would make the
ledger a record of itself.

**Absent group vs. zero field** (`objectstate.py`'s docstring states the
rule; this module is where it is APPLIED):

| Group | `None` when | Because |
|---|---|---|
| `identity` | never | every object has a name, even `""` |
| `kinematics` | never | every object has a box |
| `belief` | never | |
| `provenance` | never | the evidence source is always knowable |
| `memory` | this stream has no gallery resolved at all | "nobody could be asked" is not "asked, not recovered" |
| `lock` | `LockArbiter` has never applied a lock here | "no lock has ever existed" is not "not the locked one" |
| `timing` | never | |

**Clock.** `Track.first_seen`/`.last_seen`/`.last_confirmed` are monotonic
SECONDS (`FrameContext.now` is `now_millis / 1000.0`), so each is converted
to millis here. They stay on the session's MONOTONIC timebase; rebasing them
onto the frame's epoch capture instant is `grpc/servicers.py`'s job, because
only the servicer holds both clocks for the same frame.
`DormantIdentity.lost_at_millis`/`.first_seen_millis` are already millis on
that same monotonic timebase (`TrackBook._retire` passes `now * 1000.0`) and
are therefore NOT re-scaled here -- but they are rebased by the servicer
exactly like the live ones, since they share the timebase.

**Staleness.** A live ASSOCIATE track the associator did not match this frame
never had `_observe` called on it, so its `reupdated`/`displacement_x`/`_y`
still describe whichever earlier frame last touched it. `Track.reupdated`'s
own docstring notes this is safe for the DETECTION path because an untouched
track emits no `Detection` -- but the mirror emits EVERY live track, so that
protection does not extend here. `_touched` is this module's answer:
`_observe`/`_born`/`_adopt` all set `last_seen = now`, so a track whose
`last_seen` is not this frame's `now` was not touched, and every
this-frame-only field reports its zero instead of an earlier frame's value.
"""

from __future__ import annotations

from typing import Any, Optional, Sequence

from cv_service.orchestration.keys import Key
from cv_service.tracking.engines.base import IDENTITY, SOURCE_DETECTOR, SOURCE_TRACKER, Box
from cv_service.tracking.objectstate import (
    EVIDENCE_SOURCE_DETECTOR,
    EVIDENCE_SOURCE_MEMORY,
    EVIDENCE_SOURCE_PREDICTED,
    EVIDENCE_SOURCE_REUPDATE,
    EVIDENCE_SOURCE_TRACKER,
    EVIDENCE_SOURCE_UNSPECIFIED,
    OBJECT_LIFECYCLE_DORMANT,
    Belief,
    Identity,
    Kinematics,
    LabelCandidate,
    Lock,
    Memory,
    ObjectState,
    Provenance,
    Timing,
)
from cv_service.tracking.track import STATE_COASTING, Track

#: `Track.state` and `ObjectState.lifecycle` share a suffix by construction
#: (`TRACK_STATE_COASTING` -> `OBJECT_LIFECYCLE_COASTING`), so the mapping is
#: a prefix swap rather than a table that could drift out of sync with either
#: enum. `DORMANT` has no `Track.state` counterpart at all -- it is precisely
#: the state the wire could not report before this wave.
_STATE_PREFIX = "TRACK_STATE_"
_LIFECYCLE_PREFIX = "OBJECT_LIFECYCLE_"

#: `DormantIdentity.best_distance`'s own "nothing to compare against" answer:
#: maximum distance on its `[0, 1]` scale. An object nobody matched this
#: frame reports this rather than `0.0`, which would read as a perfect match.
_NO_APPEARANCE_MATCH = 1.0

_MILLIS_PER_SECOND = 1000.0


def object_states(
    *,
    book: Any,
    memory: Any,
    lock: Any,
    ctx: Any,
    ledger: Any,
    boxes: "Sequence[Any]",
) -> "tuple[ObjectState, ...]":
    """One `ObjectState` per LIVE track, then one per DORMANT identity.

    Both source collections (`TrackBook.tracks`, `ObjectMemory.identities`)
    already order by ascending id, so the sequence is deterministic frame to
    frame without this function sorting anything: a consumer diffing two
    frames must never see a reordering it has to undo.
    """
    now = ctx.now
    now_millis = now * _MILLIS_PER_SECOND
    detector_boxes = _detector_boxes(ctx)
    predictions = _predictions_by_track(ctx)
    recoveries = _recoveries_by_track(ctx)
    costs = _costs_by_track(ctx)
    confidences = _confidences_by_track(boxes)
    motion_compensated = _motion_compensated(ctx)
    gallery_present = memory is not None
    lock_applied_seq = getattr(lock, "applied_seq", 0)
    bound_track_id = getattr(lock, "bound_track_id", 0)

    states: "list[ObjectState]" = [
        _live_state(
            track=track,
            stream_id=ctx.stream_id,
            now=now,
            now_millis=now_millis,
            detector_box=detector_boxes.get(track.track_id),
            prediction=predictions.get(track.track_id),
            recovery=recoveries.get(track.track_id),
            assoc_cost=costs.get(track.track_id, 0.0),
            confidence_raw=confidences.get(track.track_id, 0.0),
            contributors=_contributors(ledger, track.track_id),
            motion_compensated=motion_compensated,
            gallery_present=gallery_present,
            lock_applied_seq=lock_applied_seq,
            bound_track_id=bound_track_id,
        )
        for track in book.tracks
    ]

    if gallery_present:
        states.extend(
            _dormant_state(
                identity=identity,
                stream_id=ctx.stream_id,
                now_millis=now_millis,
                contributors=_contributors(ledger, identity.track_id),
                lock_applied_seq=lock_applied_seq,
                bound_track_id=bound_track_id,
            )
            for identity in memory.identities()
        )
    return tuple(states)


def _live_state(
    *,
    track: Track,
    stream_id: str,
    now: float,
    now_millis: float,
    detector_box: Optional[Box],
    prediction: Optional[Any],
    recovery: Optional[Any],
    assoc_cost: float,
    confidence_raw: float,
    contributors: "tuple[str, ...]",
    motion_compensated: bool,
    gallery_present: bool,
    lock_applied_seq: int,
    bound_track_id: int,
) -> ObjectState:
    touched = _touched(track, now)
    last_seen_millis = track.last_seen * _MILLIS_PER_SECOND
    return ObjectState(
        id=track.track_id,
        lifecycle=track.state.replace(_STATE_PREFIX, _LIFECYCLE_PREFIX, 1),
        stream_id=stream_id,
        identity=Identity(
            label=track.elected_label,
            label_raw=track.label,
            candidates=_candidates(track.label_tally),
            stability=track.label_stability,
        ),
        kinematics=Kinematics(
            box=track.box,
            detector_box=detector_box,
            # The SOT engine's box IS the settled box on a frame it produced
            # the observation for -- `_observe` assigns `track.box =
            # observation.box` and `track.source = observation.source` in the
            # same breath. Reading it back off the FOLLOW proposal instead
            # would mean re-deriving `TrackBook._namespaced` out here to match
            # observation keys to booked tracks, putting a private keying rule
            # in a second place.
            tracker_box=track.box if touched and track.source == SOURCE_TRACKER else None,
            predicted_box=prediction.box if prediction is not None else None,
            # How far the extrapolation actually reached, AFTER `predict()`'s
            # own clamp -- carried on the `Prediction` because this is not
            # recomputable here: by now every touched track's `last_seen` has
            # advanced to this frame and the interval no longer exists.
            horizon_ms=(
                int(prediction.horizon_seconds * _MILLIS_PER_SECOND)
                if prediction is not None
                else 0
            ),
            velocity_x=track.velocity_x,
            velocity_y=track.velocity_y,
            # This-frame-only facts: see the module docstring on `_touched`.
            displacement_x=track.displacement_x if touched else 0.0,
            displacement_y=track.displacement_y if touched else 0.0,
            motion_compensated=motion_compensated,
        ),
        belief=Belief(
            confidence_raw=confidence_raw,
            confidence_smoothed=track.confidence_smoothed,
            existence=_existence(track),
            since_confirmed_ms=int(
                max(0.0, now_millis - track.last_confirmed * _MILLIS_PER_SECOND)
            ),
        ),
        provenance=Provenance(
            source=_source(track, recovery, touched=touched),
            contributors=contributors,
            assoc_cost=assoc_cost,
            reupdated=track.reupdated if touched else False,
        ),
        # Present for every live object once a gallery exists at all, even
        # when this object was not recovered: "asked and not recovered" is a
        # different fact from "no gallery could be asked", and only an absent
        # GROUP can say the second one.
        memory=(
            Memory(
                recovered=recovery is not None,
                identity_confidence=recovery.confidence if recovery is not None else 0.0,
                dormant_ms=recovery.dormant_millis if recovery is not None else 0,
                gallery_matches=recovery.gallery_considered if recovery is not None else 0,
                match_distance=(
                    recovery.match_distance if recovery is not None else _NO_APPEARANCE_MATCH
                ),
            )
            if gallery_present
            else None
        ),
        lock=_lock(track.track_id, lock_applied_seq, bound_track_id),
        timing=Timing(
            first_seen_ms=int(track.first_seen * _MILLIS_PER_SECOND),
            last_seen_ms=int(last_seen_millis),
            last_confirmed_ms=int(track.last_confirmed * _MILLIS_PER_SECOND),
            age_frames=track.age_frames,
            hits=track.hits,
            misses=track.misses,
        ),
    )


def _dormant_state(
    *,
    identity: Any,
    stream_id: str,
    now_millis: float,
    contributors: "tuple[str, ...]",
    lock_applied_seq: int,
    bound_track_id: int,
) -> ObjectState:
    """A retired identity still in the gallery -- the state the wire could
    not report at all before this wave (plan §4.5, R4 Part B).

    Most groups are deliberately thin, and that is the honest shape: a
    dormant identity has no observation this frame, so there is no raw
    confidence, no per-frame displacement and no hit/miss counters to
    report. What it DOES have -- a name, its last known box and velocity,
    when it was lost, and how large an appearance gallery it left behind --
    is exactly what an operator needs to judge whether it can come back.
    """
    dormant_millis = int(max(0.0, now_millis - identity.lost_at_millis))
    return ObjectState(
        id=identity.track_id,
        lifecycle=OBJECT_LIFECYCLE_DORMANT,
        stream_id=stream_id,
        identity=Identity(
            label=identity.label,
            # No observation this frame, so there is no raw detector word to
            # report; the remembered elected label is the only name there is.
            label_raw=identity.label,
        ),
        kinematics=Kinematics(
            box=identity.box,
            velocity_x=identity.velocity[0],
            velocity_y=identity.velocity[1],
        ),
        belief=Belief(since_confirmed_ms=dormant_millis),
        provenance=Provenance(source=EVIDENCE_SOURCE_MEMORY, contributors=contributors),
        memory=Memory(
            dormant_ms=dormant_millis,
            gallery_matches=len(identity.gallery),
            match_distance=_NO_APPEARANCE_MATCH,
        ),
        lock=_lock(identity.track_id, lock_applied_seq, bound_track_id),
        timing=Timing(
            first_seen_ms=int(identity.first_seen_millis),
            # A dormant identity's last sighting IS the moment it was lost;
            # both fields say so rather than one of them reporting `0`, which
            # on this wire reads as "never seen".
            last_seen_ms=int(identity.lost_at_millis),
            last_confirmed_ms=int(identity.lost_at_millis),
        ),
    )


def _touched(track: Track, now: float) -> bool:
    """Whether this track was booked THIS frame.

    `_born`/`_adopt`/`_observe` are the only three paths that touch a track,
    and all three assign `last_seen = now` from the very float this function
    compares against -- so the equality is exact by construction, not a
    tolerance question.
    """
    return track.last_seen == now


def _lock(track_id: int, applied_seq: int, bound_track_id: int) -> Optional[Lock]:
    """Absent until a lock has ever been applied on this stream -- see the
    module docstring's absent-vs-zero table."""
    if applied_seq <= 0:
        return None
    return Lock(locked=bound_track_id == track_id, lock_seq_applied=applied_seq)


def _source(track: Track, recovery: Optional[Any], *, touched: bool) -> str:
    """Most specific evidence wins.

    A recovered track was also observed by the detector this frame, and a
    re-updated one also has a `source`; reporting the generic answer in
    either case throws away the only fact a reader could not derive
    themselves. Order: MEMORY (this identity came back) > REUPDATE (its gap
    was reconstructed) > PREDICTED (nothing observed it at all) > whatever
    did observe it.

    `touched` gates the REUPDATE answer for the same reason `provenance.
    reupdated` is gated on it (see the module docstring): `Track.reupdated`
    describes whichever frame last booked this track, and an untouched track
    reporting `REUPDATE` would name evidence that arrived on some earlier
    frame as if it had arrived on this one.
    """
    if recovery is not None:
        return EVIDENCE_SOURCE_MEMORY
    if touched and track.reupdated:
        return EVIDENCE_SOURCE_REUPDATE
    if track.state == STATE_COASTING:
        return EVIDENCE_SOURCE_PREDICTED
    if track.source == SOURCE_DETECTOR:
        return EVIDENCE_SOURCE_DETECTOR
    if track.source == SOURCE_TRACKER:
        return EVIDENCE_SOURCE_TRACKER
    return EVIDENCE_SOURCE_UNSPECIFIED


def _existence(track: Track) -> float:
    """Hit ratio on `[0, 1]` -- a pure derivation, no new state.

    Deliberately NOT a Bayesian existence probability: this platform has no
    calibrated miss model, and a number that merely LOOKED calibrated would
    be read as one. The honest fact available is "how often has the detector
    actually confirmed this object since it was born".
    """
    observed = track.hits + track.misses
    return track.hits / observed if observed > 0 else 0.0


def _candidates(tally: "dict[str, float]") -> "tuple[LabelCandidate, ...]":
    """The decayed vote distribution, best first, ties broken by label.

    Deterministic ordering matters more here than it looks: this crosses the
    wire every frame, and an unstable order would make two otherwise
    identical frames serialize differently.
    """
    return tuple(
        LabelCandidate(label=label, weight=weight)
        for label, weight in sorted(tally.items(), key=lambda item: (-item[1], item[0]))
    )


def _contributors(ledger: Any, track_id: int) -> "tuple[str, ...]":
    """Contributor ids that claimed something about this object this frame,
    in ledger (i.e. run) order, de-duplicated.

    A contributor that claimed twice about one object appears once: the
    mirror answers "who touched this", and the claims themselves stay in the
    ledger, where the algorithm's own detail belongs (plan E7).
    """
    seen: "dict[str, None]" = {}
    for evidence in ledger.objects.get(track_id, ()):
        seen.setdefault(evidence.contributor_id, None)
    return tuple(seen)


def _detector_boxes(ctx: Any) -> "dict[int, Box]":
    """What the DETECTOR claimed about each matched track this frame.

    Worth reporting separately from `kinematics.box` precisely because the
    two can differ: late/ORU correction rewrites the box that gets BOOKED,
    and this is the raw target it was corrected from.
    `CostAssignment.tracks[i]` and `.candidates[i]` are the same track by
    construction, so a match `(candidate_index, target_index)` names both the
    track and the detector target it was paired with. Empty for `bytetrack`
    (which exposes no readable assignment) and for FOLLOW.
    """
    found = ctx.get(Key.ASSIGNMENT)
    if found is None:
        return {}
    return {
        found.tracks[candidate_index].track_id: found.targets[target_index].box
        for candidate_index, target_index in found.assignment.matches
    }


def _predictions_by_track(ctx: Any) -> "dict[int, Any]":
    """Constant-velocity extrapolation per live track.

    `predict.cv` writes `TRACKS_PREV` and `PREDICTIONS` together and
    positionally (its own stated invariant), so zipping them is safe in a way
    that a fresh `book.tracks` read would not be. Empty in FOLLOW, which
    never whole-predicts the book -- and an empty map is what makes
    `kinematics.predicted_box` ABSENT there rather than a zero box.
    """
    tracks = ctx.get(Key.TRACKS_PREV)
    predictions = ctx.get(Key.PREDICTIONS)
    if not tracks or not predictions:
        return {}
    return {
        track.track_id: prediction for track, prediction in zip(tracks, predictions)
    }


def _recoveries_by_track(ctx: Any) -> "dict[int, Any]":
    """This frame's gallery recoveries, keyed by the id each restored.

    `Key.RECOVERIES` is keyed by detection/target index (what `propose.cost`
    needs); the mirror wants them by track id, which the `Recovery` itself
    carries.
    """
    found = ctx.get(Key.RECOVERIES) or {}
    return {recovery.track_id: recovery for _identity, recovery in found.values()}


def _costs_by_track(ctx: Any) -> "dict[int, float]":
    """The matched pair's total association cost, per track.

    Positional against `assignment.matches`, exactly as `CostAssignment.costs`
    documents. Empty when the resolved engine exposes no `.cost` (bytetrack),
    in which case `assoc_cost` stays at its honest `0.0`.
    """
    found = ctx.get(Key.ASSIGNMENT)
    if found is None or not found.costs:
        return {}
    return {
        found.tracks[candidate_index].track_id: cost
        for (candidate_index, _target_index), cost in zip(found.assignment.matches, found.costs)
    }


def _confidences_by_track(boxes: "Sequence[Any]") -> "dict[int, float]":
    """This frame's own observed confidence per track, read off the response
    boxes the aggregator just produced.

    Not `Track.confidence`, which survives from whichever frame last touched
    the track: a track with no box on this frame genuinely observed nothing,
    and `0.0` says that where a carried-over number would not.
    """
    found: "dict[int, float]" = {}
    for tracked in boxes:
        track = getattr(tracked, "track", None)
        if track is not None:
            found[track.track_id] = tracked.confidence
    return found


def _motion_compensated(ctx: Any) -> bool:
    """Whether a REAL ego-motion transform was applied this frame.

    `IDENTITY` is the compensator's own documented refusal value (too few
    points, failed fit, low inlier ratio), so "a transform exists" is not the
    question -- "is it one that moved anything" is.
    """
    transform = ctx.get(Key.TRANSFORM)
    return transform is not None and transform != IDENTITY
