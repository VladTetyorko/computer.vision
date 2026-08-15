"""The track lifecycle machine: identity and age, no pixels and no policy.

`docs/plans/done/TRACKING-PLAN.md` §3.2, `docs/extracts/TRACKING-ORCHESTRATION.md` §2.1.

    TENTATIVE -> CONFIRMED -> COASTING -> LOST -> (expired, id retired)

`TrackBook` owns **track id allocation**, deliberately, rather than passing
an engine's own ids through. `Observation.key` is whatever identity token
the engine uses; the book maps it to a per-stream id starting at 1. Three
things fall out, and each is a defect avoided rather than a nicety:

* Ids are per stream and start at 1 (TRACKING-PLAN §4.A) no matter what the
  engine numbers them.
* An engine reset (the degradation rule, TRACKING-ORCHESTRATION §3.4)
  restarts the engine's own numbering; without this indirection that would
  silently re-use a track id **inside one stream's life**, which §3.2
  forbids. `forget_keys()` is what the session calls on a reset so the
  restarted numbering lands on fresh ids instead of adopting old ones.
* `ultralytics`' `BYTETracker` numbers tracks from a **process-global**
  counter that its own constructor resets (verified, see
  `engines/bytetrack.py`), so two concurrent streams would otherwise both
  be handed "track 1" for different objects.

**What counts as a miss:** a detector pass that ran and did not confirm the
track. A tracker-only frame (the FOLLOW duty cycle between verify passes)
never advances `misses` -- the detector was not asked, so it cannot have
failed to find anything. That is what keeps `max_age_frames` meaning "how
long may the detector keep failing to re-find this" in both modes, and it is
why "FOLLOW runs the detector at the configured cadence and no more" is true
rather than approximately true.

**`Track.history` (TRACKING-V3-PLAN wave V2, `history.py`)** is the evidence
this module's state machine has always summarized but never kept: a bounded
`ObservationRing` of the REAL observations behind `box`/`velocity_x/y`. Wave
V2 only populated it (`_born`/`_adopt`/`_observe` each record into it
unconditionally, letting the ring itself decide what survives). See
`history.py`'s own module docstring for the full "why", including why
`SOURCE_DETECTOR`, not merely "not predicted", is the bar for what counts as
real.

**`_observe` is the first reader (TRACKING-V3-PLAN wave V3, `reupdate.py`).**
When a track re-anchors after at least one confirmed failure to re-find it
(`misses > 0`), `reupdate()` rebuilds the gap from the ring's own last real
entry and the fresh detection, and its answer REPLACES this module's
ordinary single-frame velocity measurement outright -- see `_observe`'s own
comments for why that measurement is exactly the defect ORU exists to
correct, and `Track.history_transform`'s docstring for the companion
coordinate-frame fix (`reupdate.py`'s module docstring, §4.1b) that keeps
the ring's evidence expressed in the right frame when the camera moved
during the gap.

Pure stdlib.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from time import perf_counter
from typing import TYPE_CHECKING, Iterable, Optional, Sequence

from cv_service.tracking.engines.base import (
    IDENTITY,
    SOURCE_DETECTOR,
    SOURCE_TRACKER,
    Box,
    Descriptor,
    Observation,
    Transform,
)
from cv_service.tracking.history import ObservationRing
from cv_service.tracking.params import TrackingParams
from cv_service.tracking.reupdate import reupdate

if TYPE_CHECKING:  # pragma: no cover - typing only, keeps this module's own
    # import graph decoupled from `memory.py` at runtime (both are pure
    # stdlib and importing it for real would be harmless, but `TrackBook`
    # only ever needs a reference handed to it -- see `set_memory` below).
    from cv_service.tracking.memory import ObjectMemory

LOGGER = logging.getLogger("cv_service.tracking.track")

# State names match `cv_pb2.TrackState`'s enum VALUE NAMES one-for-one.
STATE_TENTATIVE = "TRACK_STATE_TENTATIVE"
STATE_CONFIRMED = "TRACK_STATE_CONFIRMED"
STATE_COASTING = "TRACK_STATE_COASTING"
STATE_LOST = "TRACK_STATE_LOST"

# How much longer than `max_age_frames` a LOST track is retained before its
# id is retired. LOST is not the end of the line -- TRACKING-PLAN §3.2 keeps
# lost tracks in the buffer precisely so a re-appearance after an occlusion
# recovers the SAME id, which is the plan's touchable outcome #1. This
# multiplier is structural (how much grace the "same number through a pole"
# promise gets), not an operator knob: the knob is `max_age_frames`, and this
# scales with it.
_LOST_RETENTION_MULTIPLIER = 2

# How much of a new velocity measurement to believe. Structural, not an
# operator knob: it is how much the motion model trusts one frame against its
# own history, and the number an operator tunes is `max_age`, not this.
_VELOCITY_SMOOTHING = 0.3

# How much of a new appearance OBSERVATION to believe, mirroring `_VELOCITY_
# SMOOTHING`'s reasoning exactly: one frame's descriptor is one crop under
# one moment's lighting/pose, not the object's identity, so it nudges the
# track's own signature rather than replacing it outright. Structural, not
# an operator knob -- `CV_TRACK_COST_WEIGHT_APPEARANCE` tunes how much the
# BLENDED descriptor counts toward the match cost, this tunes how fast it
# updates.
_DESCRIPTOR_SMOOTHING = 0.3


def _blend(previous: float, measured: float) -> float:
    return _VELOCITY_SMOOTHING * measured + (1.0 - _VELOCITY_SMOOTHING) * previous


@dataclass
class Track:
    """One tracked object's identity, geometry and lifetime.

    Mutable by design: this is the book's own entry, updated in place once
    per frame. The session copies the fields it needs onto the response;
    nothing outside this module mutates a `Track`.

    `last_confirmed` is deliberately NOT `last_seen`: `_observe` advances
    `last_seen` on every touch, including a tracker-only coast, so in FOLLOW
    it is always ~now even while the target has gone unconfirmed for
    minutes. `last_confirmed` only moves on a `SOURCE_DETECTOR` observation
    -- a real re-anchor, not an extrapolation -- which is what lets
    `_settle`'s wall-clock rule (review finding B7) and `predict.py`'s
    confidence decay ask "how long has it actually been since the detector
    last agreed this is the object" instead of "how long since any frame".

    `descriptor` (TRACKING-V2-PLAN wave C3) is this track's own appearance
    signature, EMA-updated by `observe_descriptor` below from DETECTOR
    observations only -- the same evidence-vs-extrapolation rule `velocity_
    x`/`velocity_y` already follow via `Observation.predicted` (see
    `_observe`): blending a descriptor extracted from a PREDICTED box would
    make the signature self-confirming exactly the way a velocity re-derived
    from its own extrapolation would. `None` until the first detector-sourced
    appearance evidence arrives, or forever on a stream with no appearance
    extractor active.

    `history` (TRACKING-V3-PLAN wave V2, `history.py`) is this track's own
    bounded ring of REAL observations -- the same evidence-vs-extrapolation
    discipline `velocity_x`/`velocity_y` and `descriptor` already apply,
    extended to an actual record rather than only a current state. Every
    booking path below (`_born`, `_adopt`, `_observe`) records into it
    unconditionally; the ring itself, not its callers, decides what counts
    as real (`ObservationRing.record`'s own docstring). A fresh
    `ObservationRing` per `Track` via `default_factory` -- never shared,
    never aliased from one track onto another.

    `history_transform` (TRACKING-V3-PLAN wave V3, §4.1b) is "the transform
    from `history.latest()`'s own capture frame to wherever `box` is
    expressed NOW" -- composed forward by `TrackBook.warp()` every frame
    (same loop, same per-frame `Transform`, that already warps `box`/
    `velocity_x/y`) and reset to `IDENTITY` by `_observe` exactly when the
    ring admits a genuinely NEW entry. `reupdate.py`'s `reupdate()` is the
    one reader: it warps a bracketing ring entry's box into the CURRENT
    frame with this before interpolating, so a gap spanned by real camera
    motion does not get reconstructed in the wrong coordinate system. See
    `reupdate.py`'s own module docstring for the measurement that chose
    this over the two other candidates the plan named.

    `reupdated` (TRACKING-V3-PLAN wave V3) is a THIS-FRAME flag, not
    persistent state -- `_observe` resets it to `False` on every call before
    deciding whether ORU actually ran, mirroring `identity_confidence`/
    `dormant_millis` on `session.py`'s `TrackedBox` (an event that happened
    on this one frame, not a property of the identity). Reading it directly
    off `Track` rather than moving it to `TrackedBox` is safe for the SAME
    reason `velocity_x`/`state`/`age_frames` already are: every code path
    that emits a `Detection` for a track called `_observe` on it THIS frame
    (a matched ASSOCIATE detection, or FOLLOW's locked target/extras, which
    `apply()` touches every single frame) -- an untouched ASSOCIATE track
    emits no `Detection` at all, so a stale value here is never serialized.
    """

    track_id: int
    key: object
    box: Box
    label: str
    confidence: float
    first_seen: float
    last_seen: float
    last_confirmed: float
    state: str = STATE_TENTATIVE
    source: str = SOURCE_DETECTOR
    velocity_x: float = 0.0
    velocity_y: float = 0.0
    age_frames: int = 0
    hits: int = 0
    misses: int = 0
    descriptor: Optional[Descriptor] = None
    history: ObservationRing = field(default_factory=ObservationRing)
    history_transform: Transform = IDENTITY
    reupdated: bool = False
    _confirmed: bool = field(default=False, repr=False)


@dataclass(frozen=True)
class RecoveredIdentity:
    """What `apply()` needs to book an observation under a REMEMBERED id
    instead of minting a fresh one (TRACKING-V2-PLAN wave C4).

    Built by `session.py`'s `_run_cost_associate` from `ObjectMemory.claim()`
    's return value -- `TrackBook` itself holds no knowledge of `ObjectMemory`
    beyond the `_retire`-time hand-off (`set_memory`, below), so this small
    value is the one place a recovery's INBOUND facts cross into the book,
    mirroring how `Observation` is the one place a detection's facts do.
    """

    track_id: int
    first_seen: float
    descriptor: Optional[Descriptor] = None
    velocity: "tuple[float, float]" = (0.0, 0.0)


class TrackBook:
    """The per-stream `{key -> Track}` book and its state machine.

    One instance per `StreamTrackingSession`; never shared. Holds the
    resolved `TrackingParams` (for `min_hits`/`max_age_frames`) and adopts a
    new one via `retune` when the wire config changes.
    """

    def __init__(self, params: TrackingParams, *, memory: "Optional[ObjectMemory]" = None) -> None:
        self._params = params
        self._tracks: dict[object, Track] = {}
        self._next_id = 1
        self._epoch = 0
        # TRACKING-V2-PLAN wave C4 -- see `set_memory`/`_retire` below.
        self._memory = memory
        # TRACKING-V3-PLAN wave V3 -- this frame's ORU accounting
        # (`DetectionResponse.reupdate_millis`/`reupdated_tracks`, wire
        # fields 22/23). `reset_reupdate_stats`/`last_reupdate_millis`/
        # `last_reupdated_tracks` below are the read/reset seam
        # `session.py`'s `process()` uses -- see `reset_reupdate_stats`'s
        # own docstring for why the reset has to happen OUTSIDE `apply()`.
        self._last_reupdate_millis = 0
        self._last_reupdated_tracks = 0

    def retune(self, params: TrackingParams) -> None:
        self._params = params

    @property
    def last_reupdate_millis(self) -> int:
        """This frame's ORU cost, in milliseconds -- 0 if `reupdate()` was
        never even attempted this frame. Read by `session.py` right after
        the ONE `apply()` call a frame is ever allowed to make (`apply()`'s
        own docstring); see `reset_reupdate_stats` for why a frame that
        skips `apply()` entirely still reports 0 rather than a stale value."""
        return self._last_reupdate_millis

    @property
    def last_reupdated_tracks(self) -> int:
        """How many tracks this frame's `apply()` call backfilled via ORU."""
        return self._last_reupdated_tracks

    def reset_reupdate_stats(self) -> None:
        """Zero this frame's ORU accounting.

        Called by `session.py`'s `process()` BEFORE the mode dispatch, every
        frame -- NOT inside `apply()` itself. `apply()` is only called from
        SOME of `process()`'s branches (never from the `OFF`/no-engine one),
        so resetting inside it would leave `last_reupdate_millis`/
        `last_reupdated_tracks` holding whatever the LAST ACTIVE frame
        measured on a frame that ran no tracking at all -- the same
        "reset every frame, read once" shape `StreamTrackingSession`'s own
        `_roi_ran`/`_roi_millis` already use, just living on this class
        because `_observe` (the thing that actually measures the cost) is
        this class's own private method.
        """
        self._last_reupdate_millis = 0
        self._last_reupdated_tracks = 0

    def set_memory(self, memory: "Optional[ObjectMemory]") -> None:
        """Adopt (or drop) the dormant gallery `_retire` hands LOST tracks to.

        `StreamTrackingSession` owns the `ObjectMemory` instance and calls
        this whenever it is (re)built or released -- lazily on first use and
        on a genuine stop, the same build-once-lazily shape as its motion/
        appearance engines (`_resolve_memory`/`_release_memory`). `None` is
        the deployment- or request-disabled state (`TrackingParams.memory_
        params.ttl_millis <= 0`) and is a completely normal value here, not
        a not-yet-configured placeholder -- `_retire` below treats it as the
        genuine no-op P5 requires.
        """
        self._memory = memory

    @property
    def tracks(self) -> list[Track]:
        """Live tracks, ordered by id ascending."""
        return sorted(self._tracks.values(), key=lambda track: track.track_id)

    def get(self, track_id: int) -> Optional[Track]:
        for track in self._tracks.values():
            if track.track_id == track_id:
                return track
        return None

    def bump_epoch(self) -> None:
        """Namespace future engine keys into a fresh epoch, without touching
        a single live track (review finding D1/D2, TRACKING-V2-PLAN wave C1).

        Called instead of `forget_keys()` when an engine is reset or rebuilt
        but tracking STAYS active: a restarted engine re-uses its own
        identity tokens from the beginning (an OpenCV exception on one
        target, or an operator switching FOLLOW from `lk` to `ncc`), and
        without this, its first re-issued key would land on whatever track
        this book still has filed under that same raw key, silently
        attaching a new object to an old id -- exactly the bug `forget_keys`
        was written to avoid, just one track at a time instead of the whole
        book.

        The wipe `forget_keys` performed was strictly more than that bug
        needed: it also retired every OTHER track that engine key collision
        could never have touched, and any track that was mid-occlusion lost
        its chance to recover its id when the object reappeared. Bumping the
        epoch keeps every existing track exactly as it was -- same id, same
        state, aging and coasting on the normal miss/LOST schedule -- while
        making the new epoch's keys structurally unable to collide with the
        old one's, engine key for engine key, forever. What this does NOT do
        yet is re-associate a coasting survivor onto the restarted engine's
        new numbering when the same object reappears -- that correlation is
        wave C3/C4's `assign.py`/`memory.py`; this wave only stops the
        instant amnesia of wiping the whole book on one bad frame.
        """
        self._epoch += 1

    def forget_keys(self) -> None:
        """Retire every live track, keeping the id counter.

        A genuine wipe, for a caller that means it -- tracking going OFF
        (`StreamTrackingSession._reset_state`), where there is no "still
        active, still coasting" state for anything to survive as. An engine
        reset or rebuild while tracking STAYS active wants `bump_epoch`
        instead (see its docstring for why the difference matters). Ids
        already handed out are never re-issued (`_next_id` is untouched).
        """
        if self._tracks:
            LOGGER.debug("tracking: retiring %d track(s) after an engine reset", len(self._tracks))
        self._tracks.clear()

    def warp(self, transform: Transform) -> None:
        """Carry every live track's stored box (and velocity) through one
        frame of camera motion (TRACKING-V2-PLAN wave C2).

        Called once per frame, in `StreamTrackingSession.process()`, BEFORE
        anything reads a track -- prediction, the FOLLOW re-anchor test, a
        lock-by-id lookup, coasting. That placement is the whole fix: an
        earlier version of this correction warped only the box a `predict()`
        call happened to READ, using THAT FRAME's transform regardless of
        how long the track had actually been unread. A track stalled for N
        frames while the camera panned at a constant rate then picked up
        only the LAST frame's delta -- an up-to-N-x undercorrection, worst
        exactly when compensation matters most (a stalled tracker, nothing
        but prediction left). Warping the STORED state every single frame
        instead means N stalled frames accumulate N single-frame warps, one
        per `process()` call, matching N frames of real camera motion --
        and it means `predict()` itself needs no transform argument at all:
        by the time it runs, `track.box`/`velocity_*` are already correct
        for the CURRENT frame.

        No-op for `IDENTITY` -- cheap (skips every track without even
        entering the loop), and it is what keeps an OFF/ASSOCIATE stream (no
        compensation attempted in this wave) or a FOLLOW stream with nothing
        constructible bit-for-bit untouched.

        `box` warps by the FULL affine -- a position has somewhere to
        translate to. `velocity_x`/`velocity_y` warp by the LINEAR part
        only (`a`, `b`, `d`, `e` -- no `c`/`f`): a rate is a difference of
        two positions, so any constant translation term cancels out of it
        by construction, and only rotation/scale changes how fast something
        reads in the now-current frame.

        **Still does NOT touch `track.history` itself** (TRACKING-V3-PLAN
        wave V2). A recorded `Observation.box` stays expressed in whatever
        frame it was captured in, forever -- unlike `box`/`velocity_*`,
        which this method keeps current every frame precisely so a READER
        never has to reconstruct history. **What V3 adds instead**:
        `track.history_transform` accumulates this SAME per-frame
        `transform`, so a reader (`reupdate.py`) can warp a stored box into
        the current frame ON DEMAND without this method ever touching the
        ring entries themselves -- see `Track.history_transform`'s own
        docstring and `reupdate.py`'s module docstring for the measurement
        that chose this over re-warping the whole ring here.
        """
        if transform.identity:
            return
        for track in self._tracks.values():
            track.box = transform.apply_box(track.box)
            vx, vy = track.velocity_x, track.velocity_y
            track.velocity_x = transform.a * vx + transform.b * vy
            track.velocity_y = transform.d * vx + transform.e * vy
            track.history_transform = track.history_transform.compose(transform)

    def apply(
        self,
        observations: Sequence[Observation],
        now: float,
        *,
        detector_ran: bool,
        recoveries: "Optional[dict[object, RecoveredIdentity]]" = None,
        captured_at: "Optional[dict[object, float]]" = None,
    ) -> list[Track]:
        """Book `observations` and age every track they did not touch.

        Returns the `Track` for each observation, in the same order, so the
        caller can put track facts back on the right box without re-matching
        geometry. `now` is a monotonic seconds timestamp.

        `recoveries` (TRACKING-V2-PLAN wave C4) maps an observation's OWN
        `key` to a `RecoveredIdentity` for the observations the caller has
        already matched against the dormant gallery -- consulted ONLY when
        `book_key` is not already tracked (a genuine new birth), so an
        update to an existing live track is never affected by it. `None`/
        absent is the overwhelming common case and costs one dict lookup per
        new birth.

        `captured_at` (2026-08-14 repair, TRACKING-V3-PLAN §4.1) maps an
        observation's OWN `key` to the instant ITS content was actually
        true, for `ObservationRing.record` alone (`history.py`'s own
        `TimedObservation`/`record` docstrings carry the full "why"). `None`,
        or a key this map does not carry, means exactly what it always has:
        `now` IS this observation's own capture instant, correct for every
        caller that has no lag to report (push mode, `bytetrack` ASSOCIATE,
        FOLLOW's extras, a fresh birth, a dormant-gallery recovery) and
        every call site written before this repair. Only `session.py`'s
        `_late_corrected_box` -- the one place in this package that
        resolves a per-detection capture instant different from arrival --
        ever populates a real entry.
        """
        touched: set[object] = set()
        booked: list[Track] = []
        for observation in observations:
            book_key = self._namespaced(observation.key)
            observed_at = captured_at.get(observation.key, now) if captured_at else now
            track = self._tracks.get(book_key)
            if track is None:
                recovery = recoveries.get(observation.key) if recoveries else None
                track = (
                    self._adopt(observation, recovery, now, captured_at=observed_at)
                    if recovery is not None
                    else self._born(observation, now, captured_at=observed_at)
                )
                self._tracks[book_key] = track
            else:
                self._observe(
                    track, observation, now, detector_ran=detector_ran, captured_at=observed_at
                )
                if track.reupdated:
                    self._last_reupdated_tracks += 1
            touched.add(book_key)
            booked.append(track)

        if detector_ran:
            for key, track in self._tracks.items():
                if key not in touched:
                    track.misses += 1

        for track in self._tracks.values():
            track.age_frames += 1
            self._settle(track, now)

        self._expire(now)
        return booked

    def _namespaced(self, key: object) -> object:
        """Wrap an engine's own identity token with the book's current epoch.

        This is the whole mechanism `bump_epoch` relies on: two keys that
        are `==` as raw engine tokens (e.g. both `1`, from a restarted
        engine's numbering) are never `==` once namespaced, because the
        epoch differs. `Track.key` (below) still stores the RAW token --
        only the book's own internal dict key is namespaced -- so nothing
        outside this module ever has to know an epoch exists.
        """
        return (self._epoch, key)

    # -- state machine ------------------------------------------------------

    def _born(self, observation: Observation, now: float, *, captured_at: float) -> Track:
        track = Track(
            track_id=self._next_id,
            key=observation.key,
            box=observation.box,
            label=observation.label,
            confidence=observation.confidence,
            first_seen=now,
            last_seen=now,
            # A track is only ever born from a `SOURCE_DETECTOR` observation
            # in practice (ASSOCIATE's associator output, or FOLLOW's
            # authoritative re-anchor) -- `first_seen`/`last_confirmed` start
            # equal for the same reason they start equal to `last_seen`.
            last_confirmed=now,
            source=observation.source,
            hits=1 if observation.source == SOURCE_DETECTOR else 0,
            _confirmed=observation.authoritative,
        )
        self._next_id += 1
        # apply() advances age_frames for every live track, this one
        # included, after the observations are booked -- starting at -1 is
        # what makes a track read `age_frames == 0` on its birth frame
        # ("frames since this track was born", TRACKING-PLAN §4.A).
        track.age_frames = -1
        # TRACKING-V3-PLAN wave V2 -- the birth observation is real evidence
        # too (see the docstring comment above: a track is only ever born
        # from `SOURCE_DETECTOR`), so it belongs in the ring same as every
        # later update. Unconditional: `ObservationRing.record` itself is
        # what decides what counts as real, not this call site.
        #
        # `captured_at` (2026-08-14 repair): `apply()`'s own resolved value,
        # `now` for every caller with no lag to report -- a brand-new birth
        # (an unmatched target, a `bytetrack` key, a fresh FOLLOW lock) is
        # never a `_late_corrected_box` candidate (that path only ever reads
        # an EXISTING track's `.history`, `session.py`'s own module
        # docstring), so this is `now` in every real call today. Stated
        # rather than assumed: see `TrackBook.apply`'s own docstring for why
        # that is a deliberate scope boundary, not an oversight.
        track.history.record(observation, captured_at)
        return track

    def _adopt(
        self, observation: Observation, recovery: RecoveredIdentity, now: float, *, captured_at: float
    ) -> Track:
        """Book `observation` under a REMEMBERED identity instead of minting
        one (TRACKING-V2-PLAN wave C4).

        The counterpart to `_born()` for the one case `_born()` must never
        handle: a detection `session.py` has already had `ObjectMemory.
        match()` + `.claim()` judge, above threshold, to be the SAME object
        as `recovery.track_id` -- an id THIS book itself retired earlier
        (`_retire`, below). Calling `_born()` here would get two things
        wrong at once: it mints a FRESH id from `self._next_id`, defeating
        the entire point of the gallery (the operator no longer recognises
        the number), and it leaves `recovery.track_id` sitting unclaimed for
        `self._next_id` to eventually reach and collide with. Neither can
        happen through this method -- `recovery.track_id` is used exactly as
        given, and `_next_id` is bumped past it defensively (in practice
        never a live branch: every id `ObjectMemory` ever hands back was
        minted by THIS SAME monotonic counter, so `recovery.track_id <
        self._next_id` already holds by construction -- the bump documents
        that invariant rather than silently relying on it).

        `_confirmed=True` outright: the same "does not serve the
        anti-flicker gate" carve-out `Observation.authoritative` already
        documents for FOLLOW's operator-chosen lock. An id the operator has
        already seen CONFIRMED, then lost, then had handed back above the
        gallery's own confidence floor, is not a fresh ambiguous detection
        that must re-earn `min_hits` frames of evidence -- it is the SAME
        evidence, continued. Forcing it back through TENTATIVE would render
        the recovery as a guess for `min_hits` frames after the wire has
        already reported it recovered (`Detection.identity_confidence`, on
        this very frame) -- a worse UI than a fresh id, not a more honest
        one.

        `recovery.first_seen`/`.descriptor`/`.velocity` restore the
        gallery's own record of this identity instead of starting cold:
        `first_seen` keeps the ORIGINAL acquisition time (the track has been
        "born" once, not once per recovery), `descriptor` seeds the EMA
        `observe_descriptor` continues to blend rather than restarting it
        from nothing, and `velocity` gives the very next frame's `predict()`
        a real estimate instead of an assumed standstill.
        """
        track = Track(
            track_id=recovery.track_id,
            key=observation.key,
            box=observation.box,
            label=observation.label,
            confidence=observation.confidence,
            first_seen=recovery.first_seen,
            last_seen=now,
            last_confirmed=now,
            source=observation.source,
            hits=1 if observation.source == SOURCE_DETECTOR else 0,
            velocity_x=recovery.velocity[0],
            velocity_y=recovery.velocity[1],
            descriptor=recovery.descriptor,
            _confirmed=True,
        )
        track.age_frames = -1
        if recovery.track_id >= self._next_id:
            self._next_id = recovery.track_id + 1
        # TRACKING-V3-PLAN wave V2 -- same reasoning as `_born`'s own call:
        # the observation that triggered this recovery is real evidence,
        # even though the identity it is booked under is not new. Note what
        # this deliberately does NOT do: `recovery` (from `ObjectMemory`)
        # carries no observation history of its own -- a recovered track's
        # ring starts fresh from this one entry, not backfilled with
        # whatever evidence produced the ORIGINAL track before it went
        # dormant. `ObjectMemory` was never asked to retain that (it keeps a
        # box/velocity/descriptor snapshot, not a history), so there is
        # nothing to restore even if this method wanted to.
        #
        # `captured_at` (2026-08-14 repair): same reasoning as `_born`'s own
        # comment -- a recovery is a BRAND-NEW booking as far as this ring
        # is concerned, never a `_late_corrected_box` candidate, so this is
        # `now` in every real call today.
        track.history.record(observation, captured_at)
        return track

    def _observe(
        self,
        track: Track,
        observation: Observation,
        now: float,
        *,
        detector_ran: bool,
        captured_at: float,
    ) -> None:
        # TRACKING-V3-PLAN wave V3 -- reset FIRST, unconditionally, so a
        # track this method does not reconstruct this frame never reports a
        # PRIOR frame's reupdate as if it were this one's (`Track.
        # reupdated`'s own docstring on why that would otherwise be safe to
        # read directly off `Track` in the first place).
        track.reupdated = False
        elapsed = now - track.last_seen
        if elapsed > 0.0 and not observation.predicted:
            reconstruction = None
            if observation.source == SOURCE_DETECTOR and track.misses > 0:
                # A REAL re-anchor (`SOURCE_DETECTOR`, per the ring's own
                # admission test -- `z2` must be genuine evidence, the same
                # bar `z1` is already held to) after at least one CONFIRMED
                # failure to re-find this track since the last one (`misses
                # > 0`, `track.py`'s own canonical "was there a gap" signal
                # -- the same one `_settle` already uses for LOST). Gating
                # on `misses`, not merely "was `elapsed` short", is what
                # keeps this OFF for the steady, already-clean case: FOLLOW
                # between verify passes where LK/NCC keeps succeeding every
                # frame (`predicted=False`, `misses` stays 0) touches this
                # branch too, on every one of those frames, and letting ORU
                # override an EMA that is already tracking well there would
                # trade continuous per-frame evidence for one coarse
                # average -- a regression, not a fix, and not what any
                # scenario in `BASELINE.md` needs.
                started = perf_counter()
                # `captured_at`, not `now` -- 2026-08-14 repair. `reupdate()`
                # treats its own temporal argument as `observation`'s OWN
                # capture instant (`z2` in its docstring's interpolant), and
                # `captured_at` is precisely that (`apply()`'s own docstring:
                # `now` whenever the caller has no lag to report, which is
                # every call before this repair -- so this is a no-op change
                # for every existing caller and only differs for a
                # `_late_corrected_box` candidate). Passing `now` here
                # unconditionally was a SECOND instance of the exact defect
                # `TimedObservation`'s own docstring documents: a lagged,
                # UNCORRECTED `observation` (late correction failed this
                # frame -- no bracket yet, or past `max_gap_millis`) is true
                # at `now - lag`, not `now`, and this is the one other call
                # in this package that treats `observation` as being AT a
                # caller-supplied instant rather than reading it off the
                # observation itself.
                reconstruction = reupdate(
                    track,
                    track.history,
                    observation,
                    captured_at,
                    max_gap_millis=self._params.reupdate_max_gap_millis,
                    max_velocity_per_second=self._params.reupdate_max_velocity_per_second,
                )
                self._last_reupdate_millis += int(round((perf_counter() - started) * 1000.0))
            if reconstruction is not None:
                # ORU's own answer REPLACES the measurement below outright,
                # rather than being blended with it: the whole point is that
                # `track.box`/`track.velocity_*` going into this frame are
                # the estimator's own accumulated drift, not a second
                # opinion worth averaging in (TRACKING-V3-PLAN §4.2, "delete
                # the accumulated extrapolation error rather than
                # inheriting it").
                track.velocity_x = reconstruction.velocity_x
                track.velocity_y = reconstruction.velocity_y
                track.reupdated = True
            else:
                # Evidence only, and smoothed. Two defects sit behind these
                # two conditions, both measured rather than supposed:
                #
                # Updating from a PREDICTED box makes the motion model
                # self-confirming -- the velocity re-derived from an
                # extrapolated box is exactly the velocity that produced it,
                # so one bad estimate is preserved forever and no amount of
                # coasting can correct it.
                #
                # Taking the instantaneous single-frame difference makes the
                # estimate noise, not motion: at a realistic detector jitter
                # a perfectly STATIC target measures a non-zero velocity,
                # and extrapolating that across an occlusion walks its box
                # off the object and fails the re-anchor that would have
                # recovered it. The smoothing costs a little lag on a
                # genuine acceleration, which is the right trade -- a
                # coasting box is already an approximation, and a wrong
                # direction is far worse than a late one.
                old_cx, old_cy = track.box.center
                new_cx, new_cy = observation.box.center
                measured_x = (new_cx - old_cx) / elapsed
                measured_y = (new_cy - old_cy) / elapsed
                if track.hits <= 1:
                    track.velocity_x = measured_x
                    track.velocity_y = measured_y
                else:
                    track.velocity_x = _blend(track.velocity_x, measured_x)
                    track.velocity_y = _blend(track.velocity_y, measured_y)
        track.box = observation.box
        track.label = observation.label
        track.confidence = observation.confidence
        track.source = observation.source
        track.last_seen = now
        if observation.authoritative:
            track._confirmed = True
        if observation.source == SOURCE_DETECTOR:
            track.last_confirmed = now
            track.hits += 1
            track.misses = 0
        elif detector_ran:
            # A verify pass ran and did not re-anchor this track: the box on
            # this frame is the tracker's own extrapolation (TRACKING-PLAN
            # §3.1, "IoU < threshold -> keep tracking, state COASTING").
            track.misses += 1
        # TRACKING-V3-PLAN wave V2 -- unconditional, same as `_born`/`_adopt`
        # above: `ObservationRing.record` is what decides whether THIS
        # observation (coasted, tracker-produced, or a genuine detector
        # confirmation) actually gets kept.
        #
        # TRACKING-V3-PLAN wave V3 addition: `before_latest`/`after_latest`
        # detects whether THIS call is what the ring just admitted, using
        # ONLY the ring's own public `latest()` (never re-deriving `history.
        # py`'s admission predicate here, which is out of this wave's file
        # scope) -- `TimedObservation` is stored BY REFERENCE (`history.py`'s
        # own "store, don't copy"), so identity comparison is exact and
        # free. `history_transform` resets to `IDENTITY` exactly when the
        # ring's own notion of "latest real observation" just changed: that
        # entry was captured on THIS frame, so there is (yet) no camera
        # motion between it and now for `reupdate()`'s next read to correct.
        before_latest = track.history.latest()
        track.history.record(observation, captured_at)
        if track.history.latest() is not before_latest:
            track.history_transform = IDENTITY

    def _settle(self, track: Track, now: float) -> None:
        # Two ageing rules, deliberately kept both (TRACKING-V2-PLAN §6,
        # review finding B7): `misses` only advances on a detector pass that
        # ran and did not touch this track, so `max_age_frames` means "how
        # many CONSECUTIVE FAILED VERIFY ATTEMPTS" -- in ASSOCIATE that is
        # every received frame, so at the documented 10 fps sample rate it
        # is ~3s of real time; in FOLLOW a verify pass only happens on
        # cadence, so the SAME 30-frame count could otherwise take a whole
        # minute of real time to reach for a track the detector never
        # re-confirms even once. `track_max_age_millis` is the wall-clock
        # backstop that makes both modes mean the same thing regardless of
        # cadence: however few or many verify attempts have been spent, a
        # track this long unconfirmed by the detector is stale. Whichever
        # rule fires first wins -- a tight cadence can still exhaust
        # `max_age_frames` quickly (its own contract, and what feeds
        # ByteTrack's `track_buffer`), while a slow one is caught by the
        # clock instead of quietly outliving both an operator's patience and
        # the truth.
        since_confirmed_millis = (now - track.last_confirmed) * 1000.0
        if (
            track.misses > self._params.max_age_frames
            or since_confirmed_millis > self._params.effective_max_age_millis
        ):
            track.state = STATE_LOST
            return
        if track.hits >= self._params.min_hits:
            track._confirmed = True
        if not track._confirmed:
            # The anti-flicker gate: below `min_hits` detector confirmations
            # a track never reaches an id-bearing state, so a one-frame
            # false positive spawns no confirmed id (TRACKING-PLAN §3.2).
            track.state = STATE_TENTATIVE
            return
        if track.source == SOURCE_DETECTOR and track.misses == 0:
            track.state = STATE_CONFIRMED
        else:
            track.state = STATE_COASTING

    def _expire(self, now: float) -> None:
        limit = self._params.max_age_frames * _LOST_RETENTION_MULTIPLIER
        expired = [key for key, track in self._tracks.items() if track.misses > limit]
        for key in expired:
            # The id itself is never re-issued -- `_next_id` only ever grows.
            self._retire(self._tracks.pop(key), now)

    def _retire(self, track: Track, now: float) -> None:
        """Hand `track` to the dormant gallery, if one is configured, right
        before it stops existing (TRACKING-V2-PLAN wave C4).

        This is the ONLY place a track leaves `self._tracks` for an "it
        might come back" reason -- `_expire()` is its one caller (`forget_
        keys()`'s full wipe is the OTHER, deliberate exception: tracking
        going OFF has no "still coasting" state for anything to survive as,
        so nothing is remembered there either). Before this wave, a track
        that reached this point simply ceased to exist, which is the exact
        defect the plan exists to close ("a track that is deleted without
        being remembered is exactly the defect this wave removes"). Routing
        every such removal through one private method -- rather than a bare
        `del self._tracks[key]` inline in `_expire`'s loop -- is what makes
        it structurally hard for a future deletion path to reintroduce that
        defect: there is exactly one way to retire a track, and it always
        offers memory first.

        `self._memory` may be `None` -- memory disabled deployment- or
        request-wide (`TrackingParams.memory_params.ttl_millis <= 0`,
        `session.py`'s `_resolve_memory`) is the genuine no-op P5 requires:
        no gallery object exists to consult, so this costs one attribute
        check, never a call into an empty gallery.
        """
        if self._memory is None:
            return
        self._memory.remember(
            track_id=track.track_id,
            label=track.label,
            box=track.box,
            velocity=(track.velocity_x, track.velocity_y),
            descriptor=track.descriptor,
            now_millis=now * 1000.0,
            first_seen_millis=track.first_seen * 1000.0,
        )


def observe_descriptor(
    track: Track, descriptor: Optional[Descriptor], *, alpha: float = _DESCRIPTOR_SMOOTHING
) -> None:
    """EMA `track`'s appearance signature toward a DETECTOR-sourced `descriptor`.

    TRACKING-V2-PLAN wave C3. Lives here, not in `session.py`, for the same
    reason `TrackBook.warp()` does: "nothing outside this module mutates a
    `Track`" (this class's own docstring) means the mutation itself has to
    happen inside a function this module defines, even when a caller outside
    it (`session.py`'s `_run_cost_associate`) is the one deciding WHEN to
    call it. `descriptor=None` is a no-op -- a box the appearance extractor
    could not describe, or a stream with no extractor at all, must not erase
    an already-accumulated signature on the strength of one missing frame.
    """
    if descriptor is None:
        return
    if track.descriptor is None:
        track.descriptor = descriptor
        return
    # `Descriptor.blend(other, alpha)` returns `alpha*self + (1-alpha)*other`
    # (`engines/base.py`) -- `alpha` here is the OLD signature's own weight,
    # so `1.0 - alpha` recovers `_DESCRIPTOR_SMOOTHING`'s documented meaning
    # ("how much of a NEW observation to believe").
    track.descriptor = track.descriptor.blend(descriptor, 1.0 - alpha)


def observation_for(
    detection: object,
    key: object,
    *,
    det_index: int,
    source: str = SOURCE_DETECTOR,
    authoritative: bool = False,
) -> Observation:
    """Adapt one duck-typed detector `Detection` into an `Observation`.

    Kept here rather than in an engine so both the associator path and the
    FOLLOW re-anchor path build observations identically. `detection` only
    has to expose `label`/`confidence`/`x`/`y`/`width`/`height` -- the shape
    `cv_service.inference.detector.Detection` has, without importing it (that
    module pulls in `cv2`/`numpy`; this one must stay pure stdlib).
    """
    return Observation(
        key=key,
        box=Box(detection.x, detection.y, detection.width, detection.height),
        label=detection.label,
        confidence=detection.confidence,
        source=source,
        det_index=det_index,
        authoritative=authoritative,
    )


def iter_states(tracks: Iterable[Track]) -> dict[str, int]:
    """State histogram -- diagnostics/tests only, never on the hot path."""
    histogram: dict[str, int] = {}
    for track in tracks:
        histogram[track.state] = histogram.get(track.state, 0) + 1
    return histogram


__all__ = [
    "STATE_TENTATIVE",
    "STATE_CONFIRMED",
    "STATE_COASTING",
    "STATE_LOST",
    "SOURCE_DETECTOR",
    "SOURCE_TRACKER",
    "RecoveredIdentity",
    "Track",
    "TrackBook",
    "observation_for",
    "observe_descriptor",
    "iter_states",
]
