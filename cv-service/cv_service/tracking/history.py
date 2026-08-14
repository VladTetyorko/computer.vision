"""A bounded per-track ring of REAL observations -- the substrate ORU/OCM read.

TRACKING-V3-PLAN §4.1 (wave V2). `Track` (`track.py`) has always held a
*current state* -- `box`, `velocity_x/y`, `last_seen`, `last_confirmed` -- and
nothing else: the evidence that produced that state was discarded the moment
a newer observation overwrote it. Every observation-centric mechanism the
literature sweep in `TRACKING-V3-PLAN.md` §8 identifies (OC-SORT's ORU/OCM,
wired in by waves V3/V4) needs the opposite: the actual observations
themselves, so a gap can be re-derived from what was really seen rather than
from wherever the constant-velocity estimator happened to drift to.

**This module changes no behaviour by itself.** Nothing in this wave reads
what it records -- `ObservationRing` is wired into `Track` and populated, and
that is the whole wave. `reupdate.py` (wave V3, `ObservationRing.before()`)
and `assign.py`'s momentum term (wave V4, `ObservationRing.span()`) are the
first readers.

## The load-bearing rule: SOURCE_DETECTOR only, never merely "not predicted"

`Observation.predicted` (`engines/base.py`) already marks the system's own
extrapolation -- a coasted box -- so excluding it is necessary. It is not
sufficient. `SOURCE_TRACKER` observations reach `TrackBook._observe` in TWO
shapes: `predicted=True` (the coast-on-stall path, `session.py`'s
`_build_coast_observation`) and `predicted=False` (LK/NCC's own per-frame
optical-flow/template match, when the engine update genuinely succeeds).
Only the first is excluded by a bare `predicted` check.

The second is excluded here too, deliberately, because it is the SAME defect
`Observation.predicted`'s own docstring describes for velocity, one layer
down: a visual tracker's per-frame match is that engine's OWN local estimate
of where the target went, built from the previous frame's patch, not
independent evidence that the object is actually there. A `TrackerUpdate`
that has quietly drifted onto a similar-looking neighbour (a documented LK/
NCC failure mode -- the aperture problem, a partial occlusion boundary the
template locks onto) still reports `predicted=False`: nothing about the
FOLLOW duty cycle can tell a genuine match from a confidently wrong one
except the periodic verify pass. Recording it into the ring anyway would let
ORU (wave V3) bracket a gap with the tracker's own drift on one or both
ends -- rebuilding a "virtual trajectory" out of exactly the kind of
self-confirming estimate the whole mechanism exists to correct. `SOURCE_
DETECTOR` is the one signal in this package that means "the object detector,
an instrument with no memory of where it thinks the target went, agrees this
is it" -- the periodic verify pass in FOLLOW, or every associated detection
in ASSOCIATE.

FOLLOW's operator-chosen lock re-anchor (`Observation.authoritative=True`,
built by `session.py`'s `_follow_verify` via `observation_for(...)`, which
defaults `source=SOURCE_DETECTOR`) confirms this split rather than
complicating it: `authoritative` only ever decorates a `SOURCE_DETECTOR`
observation (it exists to skip the `min_hits` anti-flicker gate, not to mark
a different KIND of evidence), so it needs no special case here -- it is
already real evidence by the `source` test alone, and the ring does not need
to know `authoritative` exists.

## Store, don't copy

`Observation` and `Box` are frozen dataclasses (`engines/base.py`) -- every
field on an `Observation` is either a frozen dataclass, a primitive, or
`key: object`, which the ring never reads. Nothing about a stored entry can
change after it is recorded, which is what makes storing a reference safe:
this is NOT the `Track`/`TrackedBox.track` aliasing hazard the plan's own
review flagged (a *mutable* record handed out live, so a later mutation is
visible through an old reference) -- there is no mutable state here to
alias. Copying each `Observation` on the way in would cost an allocation
every detector confirmation for a safety property the type system already
guarantees.

## Bounded by construction

`collections.deque(maxlen=...)` evicts the oldest entry the instant a new
one is appended past capacity -- there is no separate trim step to forget to
call, and no path (including a stream running for hours, one entry per
detector confirmation) that lets this grow past its `capacity`. See
`_DEFAULT_CAPACITY`'s own comment for how that number was sized.

Pure stdlib (`collections.deque`, `dataclasses`) -- this module must be
importable at capability level L1 (TRACKING-V3-PLAN invariant P8), the same
constraint `predict.py`/`track.py` already meet, since waves V3-V6 all read
this ring and all must run on an ARMv6 companion with no `cv2`/`numpy` at
all.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass
from typing import Optional

from cv_service.tracking.engines.base import SOURCE_DETECTOR, Observation

# Default ring depth when nothing overrides it. `TrackingConfig.history_size`
# (TRACKING-V3-PLAN §3, wire field 12) is the eventual per-request override --
# NOT wired through `params.py`/`TrackingRequest` by this wave (out of this
# wave's own file scope, `history.py` + `track.py` only), so every `Track`
# is built with this default until a later wave threads the `<=0 = server
# default` sentinel `verify_every_millis` already uses.
#
# Sized against what actually reads the ring, not picked round:
#   - OCM's own default span is 3 observations (`momentum_span_frames`,
#     TRACKING-V3-PLAN §3) -- the ring has to comfortably outlive that many
#     detector confirmations even early in a track's life, or `span()` would
#     spend a track's first several confirmations returning `None` for no
#     reason.
#   - ORU (wave V3) only ever needs ONE bracketing pair per gap -- `before()`
#     walks backward from the re-anchoring observation to the single prior
#     entry, not a window of them. A gap old enough to need more retained
#     history than a small multiple of the OCM span is a gap `reupdate_max_
#     gap_millis` (wire field 14) is meant to refuse reconstructing anyway,
#     deferring instead to `memory.py`'s dormant-gallery recovery, which does
#     not consult this ring at all.
#   - `track.py`'s own `_LOST_RETENTION_MULTIPLIER = 2` already establishes
#     "double the nominal count" as this package's idiom for "how much grace
#     before giving up" on a related question (how long a LOST track survives
#     before its id is retired). Applying that same multiplier twice over to
#     the OCM span (3 -> 6 -> ~roughly 16 with headroom for a generous
#     `momentum_span_frames` override) lands on a number that is still a
#     handful of `Observation`s -- a few hundred bytes -- held per track for
#     the life of a stream, immaterial next to `Track`'s own footprint even
#     over hours, since it is capacity-bounded rather than growing with age.
_DEFAULT_CAPACITY = 16


@dataclass(frozen=True)
class TimedObservation:
    """A recorded `Observation`, paired with the instant its content was
    actually TRUE -- its CAPTURE instant, never the clock reading it was
    merely PROCESSED at.

    **Corrected 2026-08-14, wave V6's instrument repair.** This module's own
    docstring, and every prior revision of this one, called `timestamp` "the
    clock reading it arrived at" -- indistinguishable from capture time for
    every caller that existed through wave V3, because none of them knew of
    a detector lag to separate the two. Wave V6 (`reupdate.py`'s
    `late_correction`, §4.5) introduced the first caller that DOES: an
    offboard detector's box describes a frame that was already stale by
    `detection_lag_millis` when it landed. Recording that box's arrival
    time here instead of its capture time mixes two clocks in every later
    `reupdate()` call that brackets against it -- `now - bracket.timestamp`
    silently loses exactly `lag_seconds` off a real elapsed time, and once
    that elapsed time is small enough for the lost `lag_seconds` to
    dominate it, the reconstructed velocity does not merely err, it diverges
    (traced in `tests/trackeval/test_replay.py`'s own regression test and
    `tools/trackeval/BASELINE.md`'s `latency` writeup). `record()`'s own
    docstring below states the caller's obligation this creates; `session.
    py`'s `_late_corrected_box` is the one caller that actually has a lag to
    account for, and threads the resolved capture instant down through
    `TrackBook.apply()`'s `captured_at` parameter for exactly that reason.

    `Observation` itself carries no notion of "when" -- it is a per-frame,
    wire-shaped value. `reupdate.py`'s virtual-trajectory math (TRACKING-V3-
    PLAN §4.2, `z(t1) + (t-t1)/(t2-t1)*(z(t2)-z(t1))`) needs each bracketing
    endpoint's own timestamp to compute the interpolation fraction, not just
    its box -- so the ring has to hand the time back out alongside every
    entry it returns, not only use it internally to answer `before()`.

    This is a deliberate, documented departure from §4.1's literal sketch
    (`before(timestamp) -> Observation | None`, `span(...) -> tuple[
    Observation, Observation] | None`): the plan's own `reupdate()` sketch
    (§4.2) takes `now` for the NEW observation but has no parameter for `t1`,
    the bracketing entry's timestamp, and nowhere else to get it from. Monkey
    -patching a timestamp onto `engines/base.py`'s frozen, protocol-shared
    `Observation` for this ring's sake alone would leak a `history.py`
    concern into a type every engine constructs; a small wrapper local to
    this module is the narrower fix.
    """

    timestamp: float
    observation: Observation


class ObservationRing:
    """Bounded per-track ring of REAL observations (`SOURCE_DETECTOR`, not
    `predicted`) -- see this module's own docstring for the full "why".

    One instance per `Track`, held for the track's entire life
    (`Track.history`, `track.py`) and never shared between tracks: an
    `ObservationRing` has no notion of which track it belongs to (it stores
    `Observation`s exactly as given, `key` included but never read), so
    sharing one across tracks would silently interleave two different
    objects' evidence.
    """

    def __init__(self, capacity: int = _DEFAULT_CAPACITY) -> None:
        # A ring of zero or negative depth cannot hold the single entry
        # `before()`/`span()`'s own boundary cases assume is at least
        # conceivable -- clamped rather than raised, matching this package's
        # general "degrade, do not except" posture (P5) for a constructor
        # argument that will only ever come from a trusted internal default
        # or, eventually, a resolved wire sentinel.
        self._capacity = max(1, capacity)
        self._entries: "deque[TimedObservation]" = deque(maxlen=self._capacity)

    def __len__(self) -> int:
        return len(self._entries)

    @property
    def capacity(self) -> int:
        return self._capacity

    def record(self, observation: Observation, captured_at: float) -> None:
        """Record `observation` if, and only if, it is REAL evidence,
        timestamped at `captured_at` -- the instant `observation`'s content
        was actually TRUE, never merely the instant this call happens to be
        running at.

        **The parameter is named for the obligation it places on the
        caller, not for convenience.** For the overwhelming common case
        (push mode, or any pull stream whose skew is unknown -- every caller
        this ring had until wave V6) arrival and capture coincide, so
        passing the frame's own processing clock (`now`) is correct and
        `track.py`'s `_born`/`_adopt`/`_observe` do exactly that by default.
        It stops being correct the moment a caller knows the observation
        describes an EARLIER instant than the one it is processing it at --
        an offboard detector's box is stale by `detection_lag_millis` before
        it even lands (§4.5) -- and passing arrival time THERE is the exact
        defect `TimedObservation`'s own docstring documents: two later
        `reupdate()` brackets end up expressed in different clocks, and the
        elapsed time between them is wrong by the lag that got dropped on
        the floor. There is no way for this method to catch a caller that
        gets this wrong (a `float` carries no provenance), which is why the
        obligation is spelled out here instead: **if `observation` is not
        fresh, `captured_at` must be `now` minus however stale it is, never
        `now` itself.**

        A silent no-op for anything that is not REAL evidence (predicted,
        tracker-produced, or any future observation shape that is not
        `SOURCE_DETECTOR`) -- deliberately not an error and not logged: a
        caller is expected to call this for every observation a track ever
        receives (`track.py` does, from `_born`/`_adopt`/`_observe` alike)
        and let the ring itself own the decision, precisely so the
        evidence-vs-extrapolation rule lives in exactly one place rather
        than being re-derived correctly at every call site. See this
        module's docstring for why `source == SOURCE_DETECTOR` is the test,
        not merely `not observation.predicted`.
        """
        if observation.source != SOURCE_DETECTOR or observation.predicted:
            return
        self._entries.append(TimedObservation(captured_at, observation))

    def latest(self) -> Optional[TimedObservation]:
        """The most recently recorded REAL observation, or `None` for an
        empty ring (a track with no detector confirmation yet)."""
        if not self._entries:
            return None
        return self._entries[-1]

    def before(self, timestamp: float) -> Optional[TimedObservation]:
        """The last REAL observation recorded strictly before `timestamp` --
        ORU's `t1`, the bracket start for the gap ending at `timestamp`.

        `None` for: an empty ring, or a `timestamp` at or before every entry
        this ring still holds (nothing to bracket from -- the gap predates
        all retained history, which `reupdate.py` treats the same as "no
        bracketing pair" per its own §4.2 contract, deferring to `memory.py`
        instead of reconstructing from data this ring no longer has).

        Walks newest-to-oldest and returns the first hit: entries are
        appended in non-decreasing timestamp order (`record` is always
        called with the caller's own advancing clock), so the first entry
        strictly less than `timestamp` scanning backward is also the LAST
        one in forward order -- the closest real observation before the gap,
        which is what a bracket start needs to be.
        """
        for entry in reversed(self._entries):
            if entry.timestamp < timestamp:
                return entry
        return None

    def span(self, frames: int) -> "Optional[tuple[TimedObservation, TimedObservation]]":
        """The latest REAL observation, paired with the one `frames` REAL
        observations before it -- OCM's direction pair (wave V4).

        `frames` counts RECORDED entries, not raw video frames: since only
        `SOURCE_DETECTOR` evidence is ever recorded, "two observations
        `momentum_span_frames` apart" (TRACKING-V3-PLAN §4.3) already means
        "two detector confirmations apart" by construction -- there is
        nothing else in the ring to count. Returned oldest-first (`(older,
        newer)`) so a caller computes direction as `newer.center -
        older.center` without first having to work out which end is which.

        `None` when the request cannot be honestly satisfied: `frames <= 0`
        (a zero or negative span is not a direction), or fewer than `frames`
        REAL observations separate the ring's oldest retained entry from its
        newest (including the empty-ring and single-entry cases, and a
        `frames` wider than `capacity` itself, which can never be satisfied
        no matter how long the track has lived) -- never a clamped, shorter
        span standing in for the one actually asked for, which would quietly
        answer a DIFFERENT question (a noisier, closer-together direction
        estimate) than the one the caller's `momentum_span_frames` requested.
        """
        if frames <= 0:
            return None
        index = len(self._entries) - 1 - frames
        if index < 0:
            return None
        return (self._entries[index], self._entries[-1])


__all__ = ["ObservationRing", "TimedObservation"]
