"""ASSOCIATE -- the two associators, and the cost path's assembly node.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.1/§4.3.

    assoc.bytetrack   detections -> a proposal, in one step
    assoc.cost        detections -> a ranked ASSIGNMENT (this file)
      detect.roi        one bounded second look at what it left unmatched
      memory.gallery    the dormant gallery's answer for what it never claimed
    propose.cost      all three -> the ONE proposal the aggregator folds

**Why `cost` is two nodes and `bytetrack` is one.** `bytetrack` holds its
own Kalman state and hands the book finished observations to rename -- there
is no seam inside it to hang a rescue or a recovery off. `cost` has no state
of its own at all: the candidates it ranks ARE `TrackBook`'s own tracks, so
"what did the match leave unexplained" is a readable fact BEFORE anything is
booked. That is the seam, and splitting the assembly out of the match is what
turns the rescue and the gallery into contributors instead of two more
branches inside one 210-line method.

**The order the split has to preserve**, because `ObjectMemory.claim` is
first-come-first-served and the ROI pass spends a real detector call: match,
then rescue, then recovery, then exactly one `TrackBook.apply`. The
declarations give the first three; the aggregator gives the fourth.
"""

from __future__ import annotations

import dataclasses
from dataclasses import dataclass, field
from typing import Any, Optional, Sequence

from cv_service.orchestration.aggregator import Proposal
from cv_service.orchestration.budget import ASSOC
from cv_service.orchestration.contract import PHASE_TRACK, Contribution, FrameContext
from cv_service.orchestration.corrections import late_corrected_box
from cv_service.orchestration.engines import EngineSet
from cv_service.orchestration.keys import Key
from cv_service.orchestration.state import StreamState
from cv_service.tracking.assign import Assignment, Candidate, CostAssociator, Target
from cv_service.tracking.engines.base import Box, Descriptor, Observation
from cv_service.tracking.levels import LEVEL_L1, LEVEL_L3
from cv_service.tracking.memory import Recovery
from cv_service.tracking.outcome import box_for
from cv_service.tracking.track import STATE_TENTATIVE, RecoveredIdentity, Track, TrackBook

#: The assembly node's own id. Its own responsibility, not the associator's:
#: it turns three independent answers into the one proposal the aggregator
#: folds, and nothing it does can change a match.
PROPOSE_COST = "propose.cost"


@dataclass(frozen=True)
class CostAssignment:
    """Everything `cost`'s match produced, for the three readers that follow.

    `tracks[i]` and `candidates[i]` are the SAME track by construction (see
    `predict.cv`, which snapshots both) -- the rescue needs the raw `Track`
    for its `.misses`/`.track_id` priority rule alongside the `Candidate` the
    engine speaks.
    """

    assignment: Assignment
    candidates: "tuple[Candidate, ...]"
    targets: "tuple[Target, ...]"
    tracks: "tuple[Track, ...]"
    #: One observation per MATCHED candidate, already late-corrected, plus the
    #: capture instant each was actually true at.
    matched: "tuple[Observation, ...]"
    matched_descriptors: "tuple[Optional[Descriptor], ...]"
    captured_at: "dict[object, float]"


class AssociateByteTrack:
    """`bytetrack` -- the engine owns the identities, the book renames them."""

    id = "assoc.bytetrack"
    family = ASSOC
    reads = frozenset({Key.DETECTIONS})
    writes = frozenset({Key.OBSERVATIONS})
    min_level = LEVEL_L3
    phase = PHASE_TRACK

    def __init__(self, engines: EngineSet) -> None:
        self._engines = engines

    def contribute(self, ctx: FrameContext, budget: Any) -> Contribution:
        detections = ctx.get(Key.DETECTIONS) or []
        engine = self._engines.engine
        # TRACKING-V2-PLAN wave C5c: ROI re-detection is NOT wired into this
        # branch, and neither is ego-motion. `bytetrack`'s association state
        # lives inside a third-party engine (its own Kalman filters) with no
        # seam to warp, predict against or rescue from -- the same reasoning
        # for both, stated once in `cv/cv-service/MODULE.md`.
        try:
            observations = engine.associate(detections, ctx.now)
        except Exception as exc:  # noqa: BLE001 - one bad frame, not a dead stream
            self._engines.reset_engine(exc)
            return Contribution(
                outputs={
                    Key.OBSERVATIONS: Proposal(
                        fold=False,
                        boxes=tuple(box_for(detection) for detection in detections),
                        summary={"branch": "engine raised"},
                    )
                },
                reason=f"{type(exc).__name__}: {exc}",
                summary={"observations": "0"},
            )

        return Contribution(
            outputs={
                Key.OBSERVATIONS: Proposal(
                    observations=tuple(observations),
                    detector_ran=True,
                    settle=lambda tracks: _boxes_by_index(detections, observations, tracks),
                    summary={"branch": "associate"},
                )
            },
            summary={"observations": str(len(observations))},
        )


class AssociateCost:
    """`cost` -- the platform owns the match, so the match is readable.

    Unlike `bytetrack`, the CANDIDATES this ranks are `TrackBook`'s own live
    tracks, predicted to `now` and already ego-motion-warped this frame. That
    is what makes `TrackBook.warp` change the association DECISION here rather
    than only what a coasting box displays, and it is what gives the ROI
    rescue and the dormant gallery a seam to hang off.
    """

    id = "assoc.cost"
    family = ASSOC
    reads = frozenset({Key.DETECTIONS, Key.DESCRIPTORS, Key.TRACKS_PREV, Key.PREDICTIONS})
    writes = frozenset({Key.ASSIGNMENT})
    #: L2 is where `TrackerRegistry` first offers `cost`.
    min_level = LEVEL_L1
    phase = PHASE_TRACK

    def __init__(self, engines: EngineSet, book: TrackBook, state: StreamState) -> None:
        self._engines = engines
        self._book = book
        self._state = state

    def contribute(self, ctx: FrameContext, budget: Any) -> Contribution:
        params = self._engines.params
        engine: CostAssociator = self._engines.engine
        detections = ctx.get(Key.DETECTIONS) or []
        boxes = [Box(d.x, d.y, d.width, d.height) for d in detections]
        descriptors = ctx.get(Key.DESCRIPTORS) or [None] * len(boxes)

        targets = [
            Target(
                box=boxes[index],
                label=detection.label,
                confidence=detection.confidence,
                descriptor=descriptors[index],
                det_index=index,
            )
            for index, detection in enumerate(detections)
        ]
        tracks_list = ctx.get(Key.TRACKS_PREV) or []
        predicted = ctx.get(Key.PREDICTIONS) or []
        candidates = [
            Candidate(
                key=track.key,
                box=predicted[index],
                # TRACK-IDENTITY-PLAN wave L1: the ELECTED label, not the raw
                # one, so `assign.py`'s label compatibility test and its L2
                # penalty both get a stable operand instead of re-rolling
                # every pass. `track.label` keeps its own unconditional-
                # overwrite semantics for every OTHER reader.
                label=track.elected_label,
                descriptor=track.descriptor,
                confirmed=track.state != STATE_TENTATIVE,
            )
            for index, track in enumerate(tracks_list)
        ]

        # No appearance extractor active on this stream -> pure geometry,
        # never geometry plus a constant: the configured weight is a
        # deployment default for "when `cost` AND an appearance engine are
        # both active", not a promise appearance always counts. Checked on the
        # EXTRACTOR, not on whether this particular frame produced a
        # descriptor -- a frame with no describable boxes must not be treated
        # as "appearance is off for this stream", since `assign.py`'s own
        # missing-descriptor handling (neutral 0.5, never a rejection) already
        # covers that case correctly.
        weights = params.cost_weights
        extractor = self._engines.appearance_extractor
        if extractor is None and weights.appearance > 0.0:
            weights = dataclasses.replace(weights, appearance=0.0)
        engine.retune(weights=weights, gates=params.cost_gates)

        assignment = engine.assign(candidates, targets)

        matched: "list[Observation]" = []
        matched_descriptors: "list[Optional[Descriptor]]" = []
        # 2026-08-14 repair -- `TrackBook.apply()`'s own `captured_at` map,
        # keyed the SAME way `recoveries` is (`observation.key`). Populated for
        # every matched candidate, never for a ROI rescue or a brand-new birth
        # (neither is a late-correction candidate -- see `track.py`'s
        # `_born`/`_adopt` for why that is a stated scope boundary).
        captured_at: "dict[object, float]" = {}
        evidence: "dict[int, dict[str, str]]" = {}
        for candidate_index, target_index in assignment.matches:
            target = targets[target_index]
            # `tracks_list[candidate_index]` is the SAME `Track`
            # `candidates[candidate_index]` was built from, so it is the one
            # with the real `.history`/`.history_transform` a late correction
            # needs to bracket against. A genuine no-op whenever this frame
            # measured no lag or the knob is off.
            candidate_key = candidates[candidate_index].key
            track = tracks_list[candidate_index]
            corrected_box, box_captured_at = late_corrected_box(
                track,
                target.box,
                ctx.now,
                params=params,
                lag_seconds=self._state.lag_seconds,
                live_track_count=len(self._book.tracks),
            )
            matched.append(
                Observation(
                    key=candidate_key,
                    box=corrected_box,
                    label=target.label,
                    confidence=target.confidence,
                    det_index=target.det_index,
                )
            )
            matched_descriptors.append(target.descriptor)
            captured_at[candidate_key] = box_captured_at
            evidence[track.track_id] = {
                "matched_det": str(target.det_index),
                "label": target.label,
                "late_corrected": str(int(corrected_box is not target.box)),
            }

        return Contribution(
            outputs={
                Key.ASSIGNMENT: CostAssignment(
                    assignment=assignment,
                    candidates=tuple(candidates),
                    targets=tuple(targets),
                    tracks=tuple(tracks_list),
                    matched=tuple(matched),
                    matched_descriptors=tuple(matched_descriptors),
                    captured_at=captured_at,
                )
            },
            summary={
                "candidates": str(len(candidates)),
                "targets": str(len(targets)),
                "matches": str(len(assignment.matches)),
                "unmatched_candidates": str(len(assignment.unmatched_candidates)),
                "unmatched_targets": str(len(assignment.unmatched_targets)),
                "appearance": "off" if extractor is None else "on",
            },
            evidence=evidence,
        )


class ProposeCost:
    """Turn the match, the rescue and the gallery into ONE proposal.

    Nothing here decides anything: it concatenates in the order
    `_run_cost_associate` always did -- matched, rescued, then unmatched (each
    with the recovery the gallery already found for it) -- and supplies the
    closure that puts track facts back onto the response.
    """

    id = PROPOSE_COST
    family = ASSOC
    reads = frozenset({Key.DETECTIONS, Key.ASSIGNMENT, Key.DETECTIONS_ROI, Key.RECOVERIES})
    writes = frozenset({Key.OBSERVATIONS})
    min_level = LEVEL_L1
    phase = PHASE_TRACK

    def contribute(self, ctx: FrameContext, budget: Any) -> Contribution:
        found: Optional[CostAssignment] = ctx.get(Key.ASSIGNMENT)
        detections = ctx.get(Key.DETECTIONS) or []
        if found is None:
            # The associator failed or was refused: echo, book nothing. Same
            # response `_run_associate`'s own exception path produced.
            return Contribution(
                outputs={
                    Key.OBSERVATIONS: Proposal(
                        fold=False,
                        boxes=tuple(box_for(detection) for detection in detections),
                        summary={"branch": "no assignment"},
                    )
                },
                reason="no assignment",
            )

        observations: "list[Observation]" = list(found.matched)
        descriptors: "list[Optional[Descriptor]]" = list(found.matched_descriptors)

        rescue = ctx.get(Key.DETECTIONS_ROI)
        rescue_index: Optional[int] = None
        if rescue is not None:
            rescue_index = len(observations)
            observations.append(rescue.observation)
            descriptors.append(rescue.descriptor)

        # TRACKING-V2-PLAN wave C4: an unmatched target has no live candidate
        # claiming it, but that is not the same question as "is this a
        # BRAND-NEW object" -- it may be one this book itself retired earlier.
        # The gallery has already been asked (`memory.gallery`); this only
        # files its answer against the key the observation is booked under, so
        # a match lands under the remembered id in the SAME `apply()` call
        # that books everything else.
        found_recoveries = ctx.get(Key.RECOVERIES) or {}
        recoveries: "dict[object, RecoveredIdentity]" = {}
        recovery_by_index: "dict[int, Recovery]" = {}
        for target_index in found.assignment.unmatched_targets:
            target = found.targets[target_index]
            # A fresh, permanently-unique token: `cost` allocates no identity
            # of its own (unlike `bytetrack`'s own key counter), so a
            # brand-new candidate needs a key nothing else could ever collide
            # with. `TrackBook._namespaced` only requires it be hashable and
            # stable across frames -- an `object()` sentinel satisfies both
            # with no counter to manage, and it doubles as `recoveries`' key.
            key = object()
            observations.append(
                Observation(
                    key=key,
                    box=target.box,
                    label=target.label,
                    confidence=target.confidence,
                    det_index=target.det_index,
                )
            )
            descriptors.append(target.descriptor)
            recovered = found_recoveries.get(target_index)
            if recovered is not None:
                identity, recovery = recovered
                recoveries[key] = identity
                recovery_by_index[target.det_index] = recovery

        def settle(tracks: "list[Track]") -> "list[Any]":
            by_index = {
                observation.det_index: track
                for observation, track in zip(observations, tracks)
                if observation.det_index >= 0
            }
            boxes_out = [
                box_for(
                    detection,
                    by_index.get(index),
                    identity_confidence=(
                        recovery_by_index[index].confidence if index in recovery_by_index else 0.0
                    ),
                    dormant_millis=(
                        recovery_by_index[index].dormant_millis if index in recovery_by_index else 0
                    ),
                    # `track.box` is exactly `observation.box` post-`_observe`
                    # (late-corrected, or the raw detection unchanged when
                    # nothing was), so passing it here is what makes a
                    # correction reach the WIRE and not just this track's own
                    # internal state.
                    box=(by_index[index].box if index in by_index else None),
                )
                for index, detection in enumerate(detections)
            ]
            # The rescued detection has no FULL-FRAME `det_index` (it is
            # `-1`), so it never entered `by_index` and is appended directly.
            # `rescue_index` is exactly where it landed in `observations`,
            # so `tracks[rescue_index]` is unambiguously ITS `Track`.
            if rescue_index is not None:
                boxes_out.append(
                    box_for(rescue.detection, tracks[rescue_index], box=tracks[rescue_index].box)
                )
            return boxes_out

        return Contribution(
            outputs={
                Key.OBSERVATIONS: Proposal(
                    observations=tuple(observations),
                    descriptors=tuple(descriptors),
                    recoveries=recoveries or None,
                    captured_at=found.captured_at,
                    detector_ran=True,
                    settle=settle,
                    summary={"branch": "cost"},
                )
            },
            summary={
                "observations": str(len(observations)),
                "rescued": str(int(rescue_index is not None)),
                "recovered": str(len(recoveries)),
            },
        )


def _boxes_by_index(
    detections: "Sequence[Any]",
    observations: "Sequence[Observation]",
    tracks: "Sequence[Track]",
) -> "list[Any]":
    by_index = {
        observation.det_index: track
        for observation, track in zip(observations, tracks)
        if observation.det_index >= 0
    }
    return [box_for(detection, by_index.get(index)) for index, detection in enumerate(detections)]
