"""FOLLOW -- one locked target, its extras, and the coast in between.

`docs/plans/active/CV-ORCHESTRATION-PLAN.md` §4.2/§4.3. The plan's mapping
table splits this into `follow.verify`, `follow.predict` and `follow.coast`;
it ships as ONE contributor, and that is a deliberate, recorded deviation.

**Why one node.** Whether this frame verifies, coasts or predicts is not a
per-node budget question -- it is the SAME scheduler decision (`run_detector`)
already reflected in `detect.full`'s own ledger row, and the three branches
share a single piece of per-stream state (the held `Track`, its extras, the
stall latch) that only one owner may mutate. Three nodes reading and writing
one latch would be three owners of one rule, which is exactly what §4.1 rule
2 exists to forbid. Which branch ran is reported as a ledger `summary` fact
instead, where it is just as greppable and cannot drift from the truth.

**What DID move.** Every `TrackBook.apply()` call site in this slice is gone:
the branches now return a `Proposal`, and the aggregator folds it. The
post-fold bookkeeping that genuinely belongs to FOLLOW -- binding the lock to
the booked id, re-wrapping extra slots around the tracks the book handed
back, remembering a target that went LOST -- rides on `Proposal.settle`, so
the book still has exactly one writer while the mode keeps its own state.
That preserves O1 contradiction 7's invariant literally: at most one
`apply()` per frame, and ZERO on the two paths that never had one (a
re-anchor failure with nothing held, and an engine that raised).
"""

from __future__ import annotations

import dataclasses
from typing import Any, Callable, Optional, Sequence

from cv_service.orchestration.aggregator import Proposal
from cv_service.orchestration.budget import FOLLOW
from cv_service.orchestration.contract import PHASE_TRACK, Contribution, FrameContext, skipped
from cv_service.orchestration.corrections import late_corrected_box, recovered_identity
from cv_service.orchestration.engines import EngineSet
from cv_service.orchestration.keys import Key
from cv_service.orchestration.state import ExtraCandidate, ExtraFollow, StreamState
from cv_service.tracking import lock as lock_module
from cv_service.tracking.engines.base import Box, Observation
from cv_service.tracking.levels import LEVEL_L2
from cv_service.tracking.lock import LockArbiter
from cv_service.tracking.memory import Recovery
from cv_service.tracking.outcome import TrackedBox, box_for, from_track
from cv_service.tracking.predict import predict
from cv_service.tracking.track import (
    SOURCE_TRACKER,
    STATE_LOST,
    Track,
    TrackBook,
    observation_for,
)

#: `TrackerRegistry` provider, threaded in rather than imported, so this
#: module stays free of the engine package's optional-dependency gates.
RegistryProvider = Callable[[], Any]


class Follow:
    """The FOLLOW mode's whole per-frame slice, as one proposal.

    `id` is `follow.<engine id>` so the ledger names the SOT engine that
    actually served (`follow.lk`, `follow.ncc`); `family` is the budget's
    single `follow` row, shared by every engine variant.
    """

    family = FOLLOW
    reads = frozenset({Key.FRAME, Key.DETECTIONS, Key.LOCK})
    writes = frozenset({Key.FOLLOW_OBS})
    min_level = LEVEL_L2
    phase = PHASE_TRACK

    def __init__(
        self,
        engines: EngineSet,
        book: TrackBook,
        lock: LockArbiter,
        state: StreamState,
        registry_provider: RegistryProvider,
    ) -> None:
        self._engines = engines
        self._book = book
        self._lock = lock
        self._state = state
        self._registry_provider = registry_provider
        self.id = f"{FOLLOW}.{engines.params.engine_id}"

    # -- the node ----------------------------------------------------------

    def contribute(self, ctx: FrameContext, budget: Any) -> Contribution:
        engine = self._engines.engine
        if engine is None:  # pragma: no cover - the budget refuses first
            return skipped("no engine resolved")
        frame = ctx.get(Key.FRAME)
        now = ctx.now
        # A verify pass is exactly "the detector ran this frame". Read off
        # the blackboard rather than off `budget.run_detector` so a detector
        # that was refused and one that FAILED are treated the same way --
        # key absent means no detections exist, whatever the reason.
        if ctx.has(Key.DETECTIONS):
            proposal, summary = self._verify(engine, ctx.get(Key.DETECTIONS) or [], frame, now)
        else:
            proposal, summary = self._predict(engine, frame, now)
        return Contribution(
            outputs={Key.FOLLOW_OBS: proposal},
            summary=summary,
            evidence=self._evidence(),
        )

    def _evidence(self) -> "dict[int, dict[str, str]]":
        """What FOLLOW itself claims about the objects it is holding -- the
        lock/extra distinction, which no other contributor can see."""
        claims: "dict[int, dict[str, str]]" = {}
        held = self._state.followed
        if held is not None:
            claims[held.track_id] = {
                "role": "locked",
                "stalled": str(int(self._state.tracker_stalled)),
            }
        for extra in self._state.extras:
            claims.setdefault(extra.track.track_id, {"role": "extra"})
        return claims

    # -- a detector pass ran: re-anchor, recover, or coast ------------------

    def _verify(
        self, engine: Any, detections: "Sequence[Any]", frame: Any, now: float
    ) -> "tuple[Proposal, dict[str, str]]":
        """TRACKING-V2-PLAN wave C5b: when the locked target re-anchors, this
        is ALSO where up to `follow_top_k - 1` extra targets get their own
        chance to re-anchor or be freshly promoted (`_extras_verify`) --
        batched into the SAME proposal as the lock's own observation, because
        `apply()` ages EVERY live track once per call and a second call in
        the same frame would double-count it. Skipped entirely when the lock
        itself fails to re-anchor (the coast-fallback branch below): a stream
        whose actual lock cannot be confirmed is not the moment to spend
        effort on situational-awareness boxes, and the next successful verify
        pass picks extras back up from a clean slate.
        """
        params = self._engines.params
        boxes = [Box(d.x, d.y, d.width, d.height) for d in detections]
        index = self._select_target(boxes, now)

        if index >= 0:
            try:
                anchored = engine.init(frame(), boxes[index])
            except Exception as exc:  # noqa: BLE001
                self._engines.reset_engine(exc)
                return self._echo(detections, "engine raised on re-anchor")
            if anchored:
                locked_observation = observation_for(
                    detections[index],
                    self._follow_key(),
                    det_index=index,
                    # A re-anchor is CONFIRMED outright (TRACKING-PLAN §3.1):
                    # the operator chose this target and the detector just
                    # re-approved it, so `min_hits` -- an anti-flicker gate
                    # for ambient association -- does not apply.
                    authoritative=True,
                )
                # 2026-08-14 repair -- `TrackBook.apply()`'s own `captured_at`
                # map (its docstring). `None` (the default, meaning "every
                # observation's own capture instant is `now`") unless the
                # branch below actually resolves one for the locked
                # observation -- an extra never gets an entry, same stated
                # scope boundary as `propose.cost`'s own map.
                captured_at: "Optional[dict[object, float]]" = None
                if self._state.followed is not None:
                    # TRACKING-V3-PLAN wave V6 -- `self._state.followed` is
                    # still the PREVIOUS frame's held `Track` here (reassigned
                    # only in `settle`), so its `.history`/`.history_transform`
                    # are real PRIOR evidence to bracket a late detection
                    # against. A genuine no-op on a FRESH acquisition
                    # (nothing to bracket against yet) or when this frame
                    # measured no lag.
                    corrected_box, box_captured_at = late_corrected_box(
                        self._state.followed,
                        locked_observation.box,
                        now,
                        params=params,
                        lag_seconds=self._state.lag_seconds,
                        live_track_count=len(self._book.tracks),
                    )
                    if corrected_box is not locked_observation.box:
                        locked_observation = dataclasses.replace(
                            locked_observation, box=corrected_box
                        )
                    captured_at = {locked_observation.key: box_captured_at}
                claimed = {index}
                extra_observations, extra_candidates = self._extras_verify(
                    boxes, detections, frame, now, claimed
                )

                def settle(tracks: "list[Track]") -> "list[TrackedBox]":
                    locked_track = tracks[0]
                    self._state.followed = locked_track
                    self._lock.bind(locked_track.track_id)
                    self._state.tracker_stalled = False
                    self._state.extras = [
                        ExtraFollow(key=candidate.key, engine=candidate.engine, track=track)
                        for candidate, track in zip(extra_candidates, tracks[1:])
                    ]
                    tracks_by_index = {index: locked_track}
                    tracks_by_index.update(
                        {
                            candidate.det_index: track
                            for candidate, track in zip(extra_candidates, tracks[1:])
                        }
                    )
                    return [
                        box_for(
                            detection,
                            tracks_by_index.get(position),
                            # TRACKING-V3-PLAN wave V6 -- same reasoning as
                            # `propose.cost`'s own `boxes_out`: `track.box` is
                            # `observation.box` post-`_observe`, corrected for
                            # the LOCKED target when `late_corrected_box`
                            # above actually ran, and byte-identical to the
                            # raw detection for every extra (never corrected,
                            # out of that wave's scope) or unmatched position.
                            box=(
                                tracks_by_index[position].box
                                if position in tracks_by_index
                                else None
                            ),
                        )
                        for position, detection in enumerate(detections)
                    ]

                return (
                    Proposal(
                        observations=(locked_observation, *extra_observations),
                        captured_at=captured_at,
                        detector_ran=True,
                        settle=settle,
                        summary={"branch": "reanchor"},
                    ),
                    {
                        "branch": "reanchor",
                        "detections": str(len(detections)),
                        "extras": str(len(extra_candidates)),
                    },
                )

        if self._state.followed is None:
            return self._gallery_reanchor(engine, boxes, detections, frame, now)

        # The verify pass ran and did not re-confirm the held target
        # (TRACKING-PLAN §3.1, "IoU < threshold -> keep tracking, state
        # COASTING"). The coasted box is emitted BESIDE the detector's boxes,
        # because by definition it is not one of them.
        held = self._state.followed
        observation = self._coast_observation(engine, held, frame, now, detector_ran=True)
        if observation is None:
            return self._echo(detections, "engine raised while coasting")

        def settle_coast(tracks: "list[Track]") -> "list[TrackedBox]":
            emitted = [box_for(detection) for detection in detections]
            emitted.append(self._settle_followed(tracks[0], now))
            return emitted

        return (
            Proposal(
                observations=(observation,),
                detector_ran=True,
                settle=settle_coast,
                summary={"branch": "coast"},
            ),
            {"branch": "coast", "detections": str(len(detections))},
        )

    def _gallery_reanchor(
        self, engine: Any, boxes: "Sequence[Box]", detections: "Sequence[Any]", frame: Any, now: float
    ) -> "tuple[Proposal, dict[str, str]]":
        """TRACK-IDENTITY-PLAN wave L4: geometry found nothing to re-anchor
        to -- for a track-id-only lock, the dormant gallery is the last resort
        before the honest coast-to-`-1`. A no-op for every other lock shape
        (`_follow_recovery`'s own gate) and for a stream with memory disabled.
        """
        recovery_hit = self._follow_recovery(boxes, detections, now)
        if recovery_hit is not None:
            det_index, recovery = recovery_hit
            try:
                anchored = engine.init(frame(), boxes[det_index])
            except Exception as exc:  # noqa: BLE001
                self._engines.reset_engine(exc)
                return self._echo(detections, "engine raised on recovery")
            if anchored:
                # Only NOW taken out of the gallery (`_follow_recovery`'s own
                # docstring) -- `engine.init()` just proved the recovery is
                # actually usable, so the claim can no longer be wasted.
                identity = self._engines.memory.claim(recovery.track_id)
                if identity is not None:
                    recovered_observation = observation_for(
                        detections[det_index],
                        self._follow_key(),
                        det_index=det_index,
                        # A memory-confirmed re-acquire is CONFIRMED outright,
                        # exactly like a geometric re-anchor -- the gallery's
                        # own four gates already did the vetting `min_hits`
                        # exists to replace.
                        authoritative=True,
                    )

                    def settle_recovered(tracks: "list[Track]") -> "list[TrackedBox]":
                        locked_track = tracks[0]
                        self._state.followed = locked_track
                        self._lock.bind(locked_track.track_id)
                        self._state.tracker_stalled = False
                        return [
                            box_for(
                                detection,
                                locked_track if position == det_index else None,
                                identity_confidence=(
                                    recovery.confidence if position == det_index else 0.0
                                ),
                                dormant_millis=(
                                    recovery.dormant_millis if position == det_index else 0
                                ),
                                box=(locked_track.box if position == det_index else None),
                            )
                            for position, detection in enumerate(detections)
                        ]

                    return (
                        Proposal(
                            observations=(recovered_observation,),
                            recoveries={
                                recovered_observation.key: recovered_identity(identity, recovery)
                            },
                            detector_ran=True,
                            settle=settle_recovered,
                            summary={"branch": "recovered"},
                        ),
                        {
                            "branch": "recovered",
                            "track": str(recovery.track_id),
                            "identity_confidence": repr(recovery.confidence),
                        },
                    )
                # `claim()` lost a same-frame race -- another caller took this
                # identity between `_follow_recovery`'s read-only match and
                # here. Vanishingly unlikely for a single track-id-targeted
                # recovery, but handled the same way `memory.gallery`'s own
                # race case is: fall through to the honest "nothing acquired"
                # outcome below, never an error.

        # Nothing held and nothing to acquire (nor recovered from memory).
        # The operator's target request (if any) stands, so the next frame
        # tries again -- scheduler trigger (c). The book is NOT folded: this
        # is one of the two zero-`apply()` frames the invariant names.
        self._lock.unbind()
        return self._echo(detections, "nothing to acquire")

    # -- no detector pass: the tracker moves everything --------------------

    def _predict(
        self, engine: Any, frame: Any, now: float
    ) -> "tuple[Proposal, dict[str, str]]":
        """A tracker-only frame: move the locked target AND up to
        `follow_top_k - 1` extra targets, each by its own engine, in ONE
        batched fold (TRACKING-V2-PLAN wave C5b, review finding C6 -- FOLLOW
        used to be scene-blind between verify passes).

        The locked target is ALWAYS first, built independently of the extras:
        if its OWN engine raises, this frame is reported untracked exactly as
        it always has been (`_coast_observation`'s exception path resets the
        locked engine and returns `None`), and extras are never even touched
        that frame -- a problem with the operator's actual lock is not the
        moment to spend effort on situational-awareness boxes.
        """
        held = self._state.followed
        if held is None:  # pragma: no cover - the scheduler forbids it
            return Proposal(fold=False, summary={"branch": "idle"}), {"branch": "idle"}
        locked_observation = self._coast_observation(
            engine, held, frame, now, detector_ran=False
        )
        if locked_observation is None:
            return (
                Proposal(fold=False, summary={"branch": "engine raised"}),
                {"branch": "engine raised"},
            )

        extra_observations, surviving_extras = self._extras_observations(frame)

        def settle(tracks: "list[Track]") -> "list[TrackedBox]":
            emitted = [self._settle_followed(tracks[0], now)]
            self._state.extras = []
            for extra, track in zip(surviving_extras, tracks[1:]):
                # `track` is the SAME object `TrackBook` will keep handing
                # back for this key going forward -- re-wrapping it here
                # (rather than reusing `extra` as-is) is what keeps
                # `ExtraFollow.track` honest as "the book's own object", the
                # property `predict()`/the next verify pass's re-anchor test
                # both rely on.
                self._state.extras.append(
                    ExtraFollow(key=extra.key, engine=extra.engine, track=track)
                )
                emitted.append(from_track(track))
            return emitted

        return (
            Proposal(
                observations=(locked_observation, *extra_observations),
                detector_ran=False,
                settle=settle,
                summary={"branch": "predict"},
            ),
            {"branch": "predict", "extras": str(len(surviving_extras))},
        )

    # -- the pieces --------------------------------------------------------

    def _echo(
        self, detections: "Sequence[Any]", reason: str
    ) -> "tuple[Proposal, dict[str, str]]":
        """The detector's own boxes, nothing booked -- `fold=False`."""
        return (
            Proposal(
                fold=False,
                boxes=tuple(box_for(detection) for detection in detections),
                summary={"branch": reason},
            ),
            {"branch": reason, "detections": str(len(detections))},
        )

    def _follow_recovery(
        self, boxes: "Sequence[Box]", detections: "Sequence[Any]", now: float
    ) -> "Optional[tuple[int, Recovery]]":
        """Best-scoring detection this pass for the operator's SPECIFIC lost
        track id, or `None` (TRACK-IDENTITY-PLAN wave L4).

        Read-only -- mirrors `memory.gallery`'s own match/claim split, just
        with the claim deferred one step further: the caller here only takes
        the recovery (`ObjectMemory.claim`) once `engine.init()` on the
        winning box has actually succeeded, so a rare tracker I/O failure
        never burns the operator's one dormant identity for nothing -- unlike
        ASSOCIATE's unmatched-target loop, FOLLOW has no second candidate
        this same frame to fall back to if the claim is wasted.

        Gated to a TRACK-ID-ONLY lock target -- the same branch `lock.py`'s
        `select_target` reaches only via its own `box_of_track` fallback
        (`target.box is None and target.point is None`). A box- or point-
        locked target still has its ORIGINAL geometric reference to retry
        every pass; only a bare track id has nothing left once the live book
        can no longer resolve it -- memory is the only resort left. FOLLOW
        never resolves an appearance extractor, so every candidate here is
        scored on label and motion alone -- `descriptor=None`, `memory.py`'s
        own neutral-appearance (0.5) reading, never a rejection on that
        account.
        """
        memory = self._engines.memory
        if memory is None:
            return None
        target = self._lock.target
        if target is None or target.box is not None or target.point is not None:
            return None
        if target.track_id <= 0:
            return None
        now_millis = now * 1000.0
        best: "Optional[tuple[int, Recovery]]" = None
        for det_index, (box, detection) in enumerate(zip(boxes, detections)):
            recovery = memory.match_identity(
                target.track_id,
                box=box,
                label=detection.label,
                descriptor=None,
                now_millis=now_millis,
            )
            if recovery is None:
                continue
            if best is None or recovery.confidence > best[1].confidence:
                best = (det_index, recovery)
        return best

    def _extras_verify(
        self,
        boxes: "Sequence[Box]",
        detections: "Sequence[Any]",
        frame: Any,
        now: float,
        claimed: "set[int]",
    ) -> "tuple[list[Observation], list[ExtraCandidate]]":
        """This verify pass's extra-target observations, built but NOT yet
        booked (TRACKING-V2-PLAN wave C5b, review finding C6).

        Batched into the SAME proposal the caller builds for the locked
        target's own re-anchor -- see `_verify` for why one fold, not
        several. Mutates `claimed` with every detection index an extra takes,
        so the caller never lets one box serve two slots.

        **Policy, stated plainly per the plan's own instruction.** An
        EXISTING extra re-anchors on the best-IoU UNCLAIMED detection at or
        above `redetect_iou_threshold` -- the SAME test the locked target's
        own re-anchor already uses (`lock.best_iou_match`), reused rather
        than reinvented. One that fails to re-anchor is dropped outright (its
        engine released): an extra is opportunistic situational awareness,
        never a commitment, so it gets none of the locked target's
        coast/predict/LOST machinery. Whatever OPEN slots remain (a dropped
        extra, or `follow_top_k` freshly raised) are filled by the
        highest-CONFIDENCE unclaimed detections -- confidence, and only
        confidence, is the "worth a slot" policy. If this ever needs a second
        sort key, it has grown past what belongs in this file.
        """
        params = self._engines.params
        capacity = max(0, params.follow_top_k - 1)
        observations: "list[Observation]" = []
        candidates: "list[ExtraCandidate]" = []
        if capacity <= 0:
            self._engines.release_extras()
            return observations, candidates

        for extra in self._state.extras:
            available = [position for position in range(len(boxes)) if position not in claimed]
            held_box = predict(extra.track, now).box
            match = (
                lock_module.best_iou_match(
                    held_box,
                    [boxes[position] for position in available],
                    params.redetect_iou_threshold,
                )
                if available
                else -1
            )
            if match < 0:
                self._engines.drop_extra(extra)
                continue
            detection_index = available[match]
            try:
                anchored = extra.engine.init(frame(), boxes[detection_index])
            except Exception:  # noqa: BLE001 - one extra's failure costs that extra only
                self._engines.drop_extra(extra)
                continue
            if not anchored:
                self._engines.drop_extra(extra)
                continue
            claimed.add(detection_index)
            observations.append(
                observation_for(detections[detection_index], extra.key, det_index=detection_index)
            )
            candidates.append(
                ExtraCandidate(key=extra.key, engine=extra.engine, det_index=detection_index)
            )

        open_slots = capacity - len(candidates)
        if open_slots > 0:
            registry = self._registry_provider()
            if registry is not None:
                ranked = sorted(
                    (position for position in range(len(detections)) if position not in claimed),
                    key=lambda position: detections[position].confidence,
                    reverse=True,
                )[:open_slots]
                for detection_index in ranked:
                    # TRACKING-V3-PLAN wave V1: an extra respects the SAME
                    # served-level ceiling as the locked target's own engine
                    # -- otherwise a level-1 stream could grow a `cv2`-backed
                    # extra through this side door even though `create_engine`
                    # never allows one for the lock itself.
                    created = registry.follower(
                        params.engine_id,
                        max_age_frames=params.max_age_frames,
                        **self._engines.level_kwargs(registry),
                    )
                    if created is None:
                        continue
                    _engine_id, engine = created
                    try:
                        anchored = engine.init(frame(), boxes[detection_index])
                    except Exception:  # noqa: BLE001 - a fresh extra's own failure, nothing else
                        continue
                    if not anchored:
                        continue
                    claimed.add(detection_index)
                    self._state.extra_key_seq += 1
                    key = f"follow-extra:{self._lock.generation}:{self._state.extra_key_seq}"
                    observations.append(
                        observation_for(detections[detection_index], key, det_index=detection_index)
                    )
                    candidates.append(
                        ExtraCandidate(key=key, engine=engine, det_index=detection_index)
                    )

        return observations, candidates

    def _extras_observations(self, frame: Any) -> "tuple[list[Observation], list[ExtraFollow]]":
        """Advance every currently-held extra by its OWN engine's `update()`
        call (TRACKING-V2-PLAN wave C5b).

        An extra whose engine raises, reports lost, or returns an invalid box
        is simply DROPPED (its engine released, its slot freed for the next
        verify pass to fill) rather than predicted or coasted -- see
        `_extras_verify` for why an extra gets none of the locked target's
        stall/LOST machinery. Never touches `tracker_failed`/`box_invalid`/
        the `LockArbiter` -- the duty cycle's scheduler answers to the LOCKED
        target only, and an extra failing must never bring a verify pass
        forward on its account: that would let situational-awareness boxes
        dictate the cadence the operator's own lock is supposed to control.
        """
        observations: "list[Observation]" = []
        survivors: "list[ExtraFollow]" = []
        for extra in self._state.extras:
            try:
                update = extra.engine.update(frame())
            except Exception:  # noqa: BLE001 - one extra's failure costs that extra only
                self._engines.drop_extra(extra)
                continue
            if update is None or not update.box.valid:
                self._engines.drop_extra(extra)
                continue
            observations.append(
                Observation(
                    key=extra.key,
                    box=update.box,
                    label=extra.track.label,
                    confidence=extra.track.confidence,
                    source=SOURCE_TRACKER,
                )
            )
            survivors.append(extra)
        return observations, survivors

    def _coast_observation(
        self, engine: Any, held: Track, frame: Any, now: float, *, detector_ran: bool
    ) -> Optional[Observation]:
        """Engine update / predict-on-stall for the LOCKED target, stopping
        short of booking it.

        `None` means the engine raised: `reset_engine` has already reset it
        and bumped the book epoch, and the caller reports this frame
        untracked -- `_coast`'s pre-existing contract, unchanged.

        A tracker that reports lost, or returns a box that has collapsed or
        left the frame, keeps the last known box for this frame and raises
        the corresponding scheduler trigger for the next one -- degrading a
        frame is always preferred to dropping the target on a single bad
        update.

        Those triggers are raised **only on tracker-only frames**, and that
        is load-bearing rather than incidental. Triggers (b) and (d) exist to
        BRING FORWARD the next verify pass; re-raising them on a verify frame
        that already ran and still could not re-anchor would ask for a pass
        that has just happened, and the detector would then run on every
        single frame for as long as the tracker stayed unhappy -- the duty
        cycle collapsing back into continuous detection, silently, exactly
        when the target is hardest. Once a pass has been spent and failed,
        the miss counter and trigger (e) own the outcome: the track coasts
        and goes LOST on schedule.
        """
        params = self._engines.params
        if self._state.tracker_stalled:
            # The tracker has already told us it lost this target AND the
            # verify pass that followed could not re-anchor it. Asking it
            # again every frame buys nothing: it PREDICTS the box forward at
            # constant velocity rather than asking the engine again (review
            # finding C1) -- a stalled target keeps moving, and freezing it
            # here is exactly what used to make the eventual re-anchor test
            # fail against a position the object had long since left.
            box = predict(held, now).box
            predicted = True
        else:
            predicted = False
            try:
                update = engine.update(frame())
            except Exception as exc:  # noqa: BLE001
                self._engines.reset_engine(exc)
                return None

            if update is None or not update.box.valid:
                # Same predict-don't-freeze fix as above, for the one-off
                # failure that has not yet latched `tracker_stalled`.
                box = predict(held, now).box
                predicted = True
                if detector_ran:
                    self._state.tracker_stalled = True
                elif update is None:
                    self._state.tracker_failed = True
                else:
                    self._state.box_invalid = True
            else:
                box = update.box
                if not detector_ran and update.confidence < params.min_tracker_confidence:
                    # Review finding C2: both engines compute `confidence` and
                    # document it as the earliest honest signal that a verify
                    # pass is worth spending (trigger (b)) -- LK's surviving-
                    # corner fraction, NCC's own match score weakening before
                    # the update actually fails outright. Restricted to
                    # tracker-only frames for the exact reason the
                    # hard-failure branch above is.
                    self._state.tracker_failed = True

        return Observation(
            key=self._follow_key(),
            box=box,
            label=held.label,
            confidence=held.confidence,
            source=SOURCE_TRACKER,
            # True exactly when `box` came from `predict()` rather than from
            # the engine -- see `Observation.predicted`.
            predicted=predicted,
        )

    def _settle_followed(self, track: Track, now: float) -> TrackedBox:
        """Post-fold bookkeeping for the LOCKED target's own track, shared by
        the coast branch and the predict branch's batched fold."""
        self._state.followed = track
        if track.state == STATE_LOST:
            # TRACK-IDENTITY-PLAN wave L4: `track.py`'s own `_retire` -- the
            # AUTOMATIC remember-on-expiry path `TrackBook._expire` drives --
            # never runs for a single-target FOLLOW lock that goes LOST here.
            # `TrackBook.apply()` is the only thing that ages a track towards
            # that expiry, and the instant `self._state.followed` goes `None`
            # below, FOLLOW stops proposing for this key entirely until a NEW
            # acquisition re-anchors it -- so the track would otherwise sit in
            # the book unaged, indefinitely, and NEVER reach the gallery.
            # Without this explicit remember, the memory-based re-acquire
            # (`_follow_recovery`) would have nothing to find. Mirrors
            # `track.py`'s `_retire` exactly (same kwargs, the ELECTED label,
            # the same descriptor) -- just triggered by FOLLOW's own settle
            # instead of the book's expiry sweep. Re-remembering an id already
            # dormant is a no-op refresh, so a track that recovers and is lost
            # again costs nothing extra here.
            if self._engines.memory is not None:
                self._engines.memory.remember(
                    track_id=track.track_id,
                    label=track.elected_label,
                    box=track.box,
                    velocity=(track.velocity_x, track.velocity_y),
                    descriptor=track.descriptor,
                    now_millis=now * 1000.0,
                    first_seen_millis=track.first_seen * 1000.0,
                )
            # `max_age_frames` consecutive unconfirmed verify passes: the lock
            # is dropped and the next pass re-acquires per policy
            # (TRACKING-PLAN §3.1 trigger (e)). The track itself stays in the
            # book, unaged, until a future acquisition attempt starts folding
            # again -- harmless, since it is also now remembered above.
            self._state.followed = None
            self._lock.unbind()
        return from_track(track)

    def _select_target(self, boxes: "Sequence[Box]", now: float) -> int:
        """Which detection FOLLOW should hold on this pass, or -1. All the
        actual selection is `lock.py`'s; this only supplies the state.

        The held/known box fed into the re-anchor test is the PREDICTED one
        (review finding C1's other half): matching this frame's detections
        against where the target physically was several coasted frames ago is
        exactly the freeze that used to make a legitimate re-anchor fail its
        own IoU test. `egomotion` (TRACKING-V2-PLAN C2) already carried the
        underlying track through every frame of camera motion since it was
        last read, so `predict()` here needs no transform of its own.

        TRACKING-V3-PLAN wave V3: when that PREDICTED test fails, one more
        attempt is made before giving up on this pass -- matching against the
        LAST REAL observation this track ever received, carried into THIS
        frame by the same accumulated ego-motion transform ORU itself reads
        (`Track.history_transform`), not the compounded, possibly-wrong-
        direction VELOCITY `predict()` is still trusting. This is the
        prospective half of the same evidence-vs-extrapolation swap ORU makes
        retrospectively at the moment of a successful re-anchor: `nonlinear`'s
        object reverses heading the instant it is hidden, so the constant-
        velocity prediction runs the wrong way for the whole gap and can never
        clear the re-anchor test on its own, while the last REAL box it was
        ever seen in barely differs from where a reversed-but-still-nearby
        object actually is.

        Not gated on `track.misses > 0` -- deliberately, and unlike
        `track.py`'s own ORU gate: `misses` only advances INSIDE `_observe`
        (this frame's, not yet run when this is called), so the VERY FIRST
        verify pass after a real confirmation would otherwise never get this
        fallback, which is exactly the attempt where the drift is smallest and
        the fallback's odds are best. Gated instead by
        `reupdate_max_gap_millis` -- the SAME ceiling that bounds what ORU
        itself will reconstruct: a gap too old to trust for one is too old to
        trust for the other, and the dormant gallery is what serves it once
        genuinely LOST. A no-op, not a widened gate, when the PRIMARY test
        already succeeded: this is a SECOND candidate offered only on failure,
        never a looser threshold applied to the first one, so it cannot make an
        existing correct match worse.
        """
        params = self._engines.params
        held = self._state.followed
        held_box = predict(held, now).box if held is not None else None
        index = lock_module.select_target(
            boxes,
            held_box=held_box,
            target=self._lock.target,
            min_iou=params.redetect_iou_threshold,
            box_of_track=lambda track_id: self._box_of_track(track_id, now),
        )
        if index < 0 and held_box is not None:
            anchor = held.history.latest()
            if anchor is not None:
                gap_millis = (now - anchor.timestamp) * 1000.0
                if 0.0 < gap_millis <= params.reupdate_max_gap_millis:
                    frozen_box = held.history_transform.apply_box(anchor.observation.box)
                    index = lock_module.best_iou_match(
                        frozen_box, boxes, params.redetect_iou_threshold
                    )
        return index

    def _box_of_track(self, track_id: int, now: float) -> Optional[Box]:
        known = self._book.get(track_id)
        return predict(known, now).box if known is not None else None

    def _follow_key(self) -> str:
        """A fresh `TrackBook` key per applied lock, so re-acquiring after a
        release yields a NEW id instead of resurrecting the previous one."""
        return f"{FOLLOW}:{self._lock.generation}"
