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

Pure stdlib.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Iterable, Optional, Sequence

from cv_service.tracking.engines.base import (
    SOURCE_DETECTOR,
    SOURCE_TRACKER,
    Box,
    Descriptor,
    Observation,
    Transform,
)
from cv_service.tracking.params import TrackingParams

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
    _confirmed: bool = field(default=False, repr=False)


class TrackBook:
    """The per-stream `{key -> Track}` book and its state machine.

    One instance per `StreamTrackingSession`; never shared. Holds the
    resolved `TrackingParams` (for `min_hits`/`max_age_frames`) and adopts a
    new one via `retune` when the wire config changes.
    """

    def __init__(self, params: TrackingParams) -> None:
        self._params = params
        self._tracks: dict[object, Track] = {}
        self._next_id = 1
        self._epoch = 0

    def retune(self, params: TrackingParams) -> None:
        self._params = params

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
        """
        if transform.identity:
            return
        for track in self._tracks.values():
            track.box = transform.apply_box(track.box)
            vx, vy = track.velocity_x, track.velocity_y
            track.velocity_x = transform.a * vx + transform.b * vy
            track.velocity_y = transform.d * vx + transform.e * vy

    def apply(
        self,
        observations: Sequence[Observation],
        now: float,
        *,
        detector_ran: bool,
    ) -> list[Track]:
        """Book `observations` and age every track they did not touch.

        Returns the `Track` for each observation, in the same order, so the
        caller can put track facts back on the right box without re-matching
        geometry. `now` is a monotonic seconds timestamp.
        """
        touched: set[object] = set()
        booked: list[Track] = []
        for observation in observations:
            book_key = self._namespaced(observation.key)
            track = self._tracks.get(book_key)
            if track is None:
                track = self._born(observation, now)
                self._tracks[book_key] = track
            else:
                self._observe(track, observation, now, detector_ran=detector_ran)
            touched.add(book_key)
            booked.append(track)

        if detector_ran:
            for key, track in self._tracks.items():
                if key not in touched:
                    track.misses += 1

        for track in self._tracks.values():
            track.age_frames += 1
            self._settle(track, now)

        self._expire()
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

    def _born(self, observation: Observation, now: float) -> Track:
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
        return track

    def _observe(
        self, track: Track, observation: Observation, now: float, *, detector_ran: bool
    ) -> None:
        elapsed = now - track.last_seen
        if elapsed > 0.0 and not observation.predicted:
            # Evidence only, and smoothed. Two defects sit behind these two
            # conditions, both measured rather than supposed:
            #
            # Updating from a PREDICTED box makes the motion model
            # self-confirming -- the velocity re-derived from an
            # extrapolated box is exactly the velocity that produced it, so
            # one bad estimate is preserved forever and no amount of
            # coasting can correct it.
            #
            # Taking the instantaneous single-frame difference makes the
            # estimate noise, not motion: at a realistic detector jitter a
            # perfectly STATIC target measures a non-zero velocity, and
            # extrapolating that across an occlusion walks its box off the
            # object and fails the re-anchor that would have recovered it.
            # The smoothing costs a little lag on a genuine acceleration,
            # which is the right trade -- a coasting box is already an
            # approximation, and a wrong direction is far worse than a late one.
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

    def _expire(self) -> None:
        limit = self._params.max_age_frames * _LOST_RETENTION_MULTIPLIER
        expired = [key for key, track in self._tracks.items() if track.misses > limit]
        for key in expired:
            # The id itself is never re-issued -- `_next_id` only ever grows.
            del self._tracks[key]


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
    "Track",
    "TrackBook",
    "observation_for",
    "observe_descriptor",
    "iter_states",
]
