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

from cv_service.tracking.engines.base import SOURCE_DETECTOR, SOURCE_TRACKER, Box, Observation
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


@dataclass
class Track:
    """One tracked object's identity, geometry and lifetime.

    Mutable by design: this is the book's own entry, updated in place once
    per frame. The session copies the fields it needs onto the response;
    nothing outside this module mutates a `Track`.
    """

    track_id: int
    key: object
    box: Box
    label: str
    confidence: float
    first_seen: float
    last_seen: float
    state: str = STATE_TENTATIVE
    source: str = SOURCE_DETECTOR
    velocity_x: float = 0.0
    velocity_y: float = 0.0
    age_frames: int = 0
    hits: int = 0
    misses: int = 0
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

    def forget_keys(self) -> None:
        """Retire every live track, keeping the id counter.

        Called when an engine is reset: the restarted engine will re-use its
        own identity tokens from the beginning, and adopting them onto
        existing tracks would attach a new object to an old id. Ids already
        handed out are never re-issued (`_next_id` is untouched).
        """
        if self._tracks:
            LOGGER.debug("tracking: retiring %d track(s) after an engine reset", len(self._tracks))
        self._tracks.clear()

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
            track = self._tracks.get(observation.key)
            if track is None:
                track = self._born(observation, now)
                self._tracks[observation.key] = track
            else:
                self._observe(track, observation, now, detector_ran=detector_ran)
            touched.add(observation.key)
            booked.append(track)

        if detector_ran:
            for key, track in self._tracks.items():
                if key not in touched:
                    track.misses += 1

        for track in self._tracks.values():
            track.age_frames += 1
            self._settle(track)

        self._expire()
        return booked

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
        if elapsed > 0.0:
            old_cx, old_cy = track.box.center
            new_cx, new_cy = observation.box.center
            track.velocity_x = (new_cx - old_cx) / elapsed
            track.velocity_y = (new_cy - old_cy) / elapsed
        track.box = observation.box
        track.label = observation.label
        track.confidence = observation.confidence
        track.source = observation.source
        track.last_seen = now
        if observation.authoritative:
            track._confirmed = True
        if observation.source == SOURCE_DETECTOR:
            track.hits += 1
            track.misses = 0
        elif detector_ran:
            # A verify pass ran and did not re-anchor this track: the box on
            # this frame is the tracker's own extrapolation (TRACKING-PLAN
            # §3.1, "IoU < threshold -> keep tracking, state COASTING").
            track.misses += 1

    def _settle(self, track: Track) -> None:
        if track.misses > self._params.max_age_frames:
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
    "iter_states",
]
