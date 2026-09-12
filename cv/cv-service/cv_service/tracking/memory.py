"""The dormant gallery: what lets a track id survive not being seen.

`docs/plans/done/TRACKING-V2-PLAN.md` §3.4, `docs/conclusions/TRACKING-REVIEW.md` §4.4.

**The defect this closes.** Before it, a track existed only while boxes kept
overlapping frame to frame, and was deleted when they stopped. Nothing in the
system held a representation of an *object*, so nothing could remember one:
an occlusion longer than the age limit, an excursion out of frame, or a pan
away and back all produced a brand-new id for the same physical thing. That
is the operator-visible complaint -- "it loses the subject" -- and no amount
of better association fixes it, because by the time association runs the
identity has already been discarded.

**The shape.** A track that goes LOST is not deleted; it is moved here, with
enough of itself to be recognised later: its id, an appearance signature, its
last kinematic state, and its provenance. Every candidate new identity is
tested against the gallery *before* an id is allocated, and a match hands
back the original id instead of minting a fresh one.

**Four gates, all required.** A single appearance score is not enough to give
an operator back a number they recognise, so a match must survive all of:
time (within the TTL), label compatibility, appearance (close enough to
something in the gallery), and motion plausibility (the object could actually
have got from where it was lost to where this candidate is, in the time that
has passed). Confidence is then *reported* rather than assumed -- an honest
"#7, re-acquired, 0.82" is the doctrine the wire already follows with
`detector_ran`, and it is why `identity_confidence` exists on `Detection`.

**Bounded by construction.** At most `capacity` identities, each holding at
most `gallery_size` descriptors, each expiring `ttl_millis` after it was
lost. An eight-hour flight cannot grow this. That matters more than it
sounds: the whole point of putting memory here rather than in a database is
that it must run on a companion computer.

Pure stdlib -- no `cv2`, no `numpy`. `ObjectMemory` compares descriptors
through `Descriptor.distance`, which is plain arithmetic, so the identity
core stays testable with no `cv` extra and the records stay serializable,
which is what keeps a future per-asset or cross-stream tier reachable.
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass, field, replace
from typing import Optional

from cv_service.tracking.engines.base import Box, Descriptor

LOGGER = logging.getLogger("cv_service.tracking.memory")

# A candidate whose centre is exactly at the edge of what the object could
# have reached scores this, and one that has not moved at all scores 1.0.
# Structural rather than an operator knob: it is the shape of the motion
# score, not its reach -- the reach is `max_speed`.
_MOTION_FLOOR = 0.2


@dataclass(frozen=True)
class Recovery:
    """A dormant identity matched to a new candidate.

    `confidence` is reported on the wire as `Detection.identity_confidence`,
    never rounded to a boolean: an operator being handed back `#7` deserves
    to know how sure the system is, and a UI that says "re-acquired, 0.6"
    is honest in a way that silently reusing the number is not.
    """

    track_id: int
    confidence: float
    dormant_millis: int
    # CV-ORCHESTRATION wave W1 -- `ObjectState.memory.match_distance`/
    # `.gallery_matches` (plan §4.5). Defaults keep every EXISTING caller
    # (this module's own `_score`, and any test constructing a `Recovery`
    # directly) source-compatible: `1.0` is `best_distance`'s own "no
    # descriptor to compare" answer (max distance, i.e. no match), and `0`
    # gallery entries considered is honest for a caller that never asks
    # `match`/`match_identity` to look.
    #
    # `match_distance` is the appearance distance this recovery actually
    # scored at (`_score`'s own `distance`, before the `1.0 - distance`
    # flip into `appearance`) -- reported raw because the wire's own
    # `Belief`/`Memory` split already reports a confidence separately;
    # duplicating the flipped number would just be `1.0 - match_distance`
    # under a different name.
    #
    # `gallery_considered` is how many dormant identities were on the table
    # when this one won -- `match`'s own gallery size at call time, or `1`
    # from `match_identity` (which never considers more than the one id the
    # caller named). Filled by `match`/`match_identity` themselves via
    # `dataclasses.replace`, not by `_score` (which only ever sees one
    # entry and has no way to know the gallery's total size).
    match_distance: float = 1.0
    gallery_considered: int = 0


@dataclass(frozen=True)
class MemoryParams:
    """Resolved configuration for the gallery. No sentinels left.

    Defaults are deliberately conservative: a wrong recovery is worse than a
    missed one, because it attaches an operator's attention to the wrong
    object while telling them it is the right one.
    """

    ttl_millis: int = 30_000
    capacity: int = 32
    gallery_size: int = 4
    max_appearance_distance: float = 0.45
    # Normalized frame widths per second. A target crossing the whole frame
    # in one second is already extreme for anything a drone watches.
    max_speed: float = 1.0
    blend_alpha: float = 0.7
    min_confidence: float = 0.35


@dataclass
class DormantIdentity:
    """One remembered object. Mutable: updated in place when re-remembered."""

    track_id: int
    label: str
    box: Box
    velocity: tuple[float, float]
    lost_at_millis: float
    first_seen_millis: float
    descriptor: Optional[Descriptor] = None
    gallery: list[Descriptor] = field(default_factory=list)
    recoveries: int = 0

    def best_distance(self, other: Optional[Descriptor]) -> float:
        """Closest match across the gallery, on [0, 1].

        The gallery rather than the blended descriptor alone because an
        object seen from two aspects has two genuinely different signatures,
        and an average of them can resemble neither.
        """
        if other is None:
            return 1.0
        candidates = [self.descriptor] if self.descriptor is not None else []
        candidates.extend(self.gallery)
        if not candidates:
            return 1.0
        return min(candidate.distance(other) for candidate in candidates)


class ObjectMemory:
    """The per-stream dormant gallery.

    One instance per `StreamTrackingSession`, never shared -- ids are
    per-stream, so a gallery shared between streams could hand one stream's
    number to another stream's object.
    """

    def __init__(self, params: MemoryParams) -> None:
        self._params = params
        self._dormant: dict[int, DormantIdentity] = {}

    def retune(self, params: MemoryParams) -> None:
        """Adopt newly-resolved configuration. On config change only."""
        self._params = params
        self._enforce_capacity()

    @property
    def params(self) -> MemoryParams:
        return self._params

    def size(self) -> int:
        return len(self._dormant)

    def identities(self) -> list[DormantIdentity]:
        """Diagnostics and tests, never the hot path."""
        return sorted(self._dormant.values(), key=lambda entry: entry.track_id)

    def remember(
        self,
        *,
        track_id: int,
        label: str,
        box: Box,
        velocity: tuple[float, float],
        descriptor: Optional[Descriptor],
        now_millis: float,
        first_seen_millis: Optional[float] = None,
    ) -> None:
        """Move a track that has gone LOST into the gallery.

        Re-remembering an id already dormant refreshes it rather than
        duplicating it, which is what happens when a recovered track is lost
        again -- the common case for a target behind intermittent cover.
        """
        if track_id <= 0:
            return
        existing = self._dormant.get(track_id)
        if existing is None:
            entry = DormantIdentity(
                track_id=track_id,
                label=label,
                box=box,
                velocity=velocity,
                lost_at_millis=now_millis,
                first_seen_millis=(
                    now_millis if first_seen_millis is None else first_seen_millis
                ),
                descriptor=descriptor,
            )
            if descriptor is not None:
                entry.gallery.append(descriptor)
            self._dormant[track_id] = entry
        else:
            existing.label = label or existing.label
            existing.box = box
            existing.velocity = velocity
            existing.lost_at_millis = now_millis
            self._absorb(existing, descriptor)
        self._enforce_capacity()

    def match(
        self,
        *,
        box: Box,
        label: str,
        descriptor: Optional[Descriptor],
        now_millis: float,
    ) -> Optional[Recovery]:
        """Best dormant identity for this candidate, or `None`.

        Read-only: a caller that decides to take the recovery calls `claim`.
        Separating the two is what lets the session test a candidate without
        committing to it, and keeps this method free of the question of what
        the caller does with the answer.
        """
        self.forget_expired(now_millis)
        # Captured after `forget_expired`, so it is the gallery this
        # candidate was actually weighed against, not a stale pre-expiry
        # count.
        considered = len(self._dormant)
        best: Optional[Recovery] = None
        for entry in self._dormant.values():
            scored = self._score(entry, box, label, descriptor, now_millis)
            if scored is None:
                continue
            if best is None or scored.confidence > best.confidence:
                best = scored
        if best is None:
            return None
        return replace(best, gallery_considered=considered)

    def match_identity(
        self,
        track_id: int,
        *,
        box: Box,
        label: str,
        descriptor: Optional[Descriptor],
        now_millis: float,
    ) -> Optional[Recovery]:
        """Score ONE specific dormant identity against a candidate, or `None`.

        `match` searches the whole gallery for the best-scoring entry -- right
        for ASSOCIATE, where any dormant id is a legitimate answer for an
        unmatched detection. FOLLOW's re-acquire is a different question: the
        operator asked for a SPECIFIC track id back, so a different, better-
        scoring dormant identity winning would silently redirect the lock to
        an object nobody asked to follow. This method answers only "is THIS
        the one the operator lost", by reusing the same four gates.
        """
        self.forget_expired(now_millis)
        entry = self._dormant.get(track_id)
        if entry is None:
            return None
        scored = self._score(entry, box, label, descriptor, now_millis)
        if scored is None:
            return None
        # Exactly one identity was ever on the table -- see `Recovery.
        # gallery_considered`'s own docstring.
        return replace(scored, gallery_considered=1)

    def claim(self, track_id: int) -> Optional[DormantIdentity]:
        """Take an identity out of the gallery; it is live again."""
        entry = self._dormant.pop(track_id, None)
        if entry is not None:
            entry.recoveries += 1
            LOGGER.info(
                "tracking: recovered track #%d after %d dormant identities considered",
                track_id,
                len(self._dormant) + 1,
            )
        return entry

    def forget_expired(self, now_millis: float) -> None:
        """Drop identities past the TTL. Idempotent, cheap, never raises."""
        expired = [
            track_id
            for track_id, entry in self._dormant.items()
            if now_millis - entry.lost_at_millis > self._params.ttl_millis
        ]
        for track_id in expired:
            del self._dormant[track_id]

    def clear(self) -> None:
        self._dormant.clear()

    # -- gates --------------------------------------------------------------

    def _score(
        self,
        entry: DormantIdentity,
        box: Box,
        label: str,
        descriptor: Optional[Descriptor],
        now_millis: float,
    ) -> Optional[Recovery]:
        """All four gates, then a confidence. `None` if any gate rejects."""
        elapsed_millis = now_millis - entry.lost_at_millis
        if elapsed_millis < 0.0 or elapsed_millis > self._params.ttl_millis:
            return None
        if not _labels_compatible(entry.label, label):
            return None

        distance = entry.best_distance(descriptor)
        # A candidate with no descriptor is judged on motion alone rather
        # than rejected: a stream with no appearance engine must still be
        # able to recover an id, just less confidently.
        if descriptor is not None and distance > self._params.max_appearance_distance:
            return None

        motion = self._motion_score(entry, box, elapsed_millis)
        if motion is None:
            return None

        appearance = 1.0 - distance if descriptor is not None else 0.5
        confidence = appearance * motion
        if confidence < self._params.min_confidence:
            return None
        return Recovery(
            track_id=entry.track_id,
            confidence=max(0.0, min(1.0, confidence)),
            dormant_millis=int(elapsed_millis),
            match_distance=distance,
        )

    def _motion_score(
        self, entry: DormantIdentity, box: Box, elapsed_millis: float
    ) -> Optional[float]:
        """Could the object have got here, and how comfortably?

        The reach is a distance the object could plausibly have covered, plus
        the two boxes' own extents -- without that slack a target that simply
        reappeared where it vanished would be judged on sub-pixel noise. A
        candidate beyond the reach is rejected outright rather than scored
        low: "that is on the other side of the frame" is a fact, not a
        preference.
        """
        elapsed_seconds = max(0.0, elapsed_millis) / 1000.0
        old_cx, old_cy = entry.box.center
        new_cx, new_cy = box.center
        travelled = math.hypot(new_cx - old_cx, new_cy - old_cy)

        slack = (entry.box.width + entry.box.height + box.width + box.height) / 4.0
        reach = self._params.max_speed * elapsed_seconds + slack
        if reach <= 0.0:
            return None
        if travelled > reach:
            return None
        used = travelled / reach
        return 1.0 - (1.0 - _MOTION_FLOOR) * used

    # -- housekeeping -------------------------------------------------------

    def _absorb(self, entry: DormantIdentity, descriptor: Optional[Descriptor]) -> None:
        if descriptor is None:
            return
        entry.descriptor = (
            descriptor
            if entry.descriptor is None
            else entry.descriptor.blend(descriptor, self._params.blend_alpha)
        )
        entry.gallery.append(descriptor)
        if len(entry.gallery) > self._params.gallery_size:
            # Most-recent-K, deliberately: appearance drifts with lighting and
            # aspect, so the newest views are the ones a re-appearance is most
            # likely to resemble. Keeping the oldest would anchor the gallery
            # to how the object looked when it was first seen, which after a
            # long dormancy is the least useful thing to compare against.
            del entry.gallery[0 : len(entry.gallery) - self._params.gallery_size]

    def _enforce_capacity(self) -> None:
        if len(self._dormant) <= self._params.capacity:
            return
        # Oldest loss first: the least likely to still be re-acquirable, and
        # the closest to expiring anyway.
        ordered = sorted(self._dormant.values(), key=lambda entry: entry.lost_at_millis)
        for entry in ordered[: len(self._dormant) - self._params.capacity]:
            del self._dormant[entry.track_id]


def _labels_compatible(left: str, right: str) -> bool:
    """An unknown label never contradicts anything -- see `assign.py`."""
    if not left or not right:
        return True
    return left == right
