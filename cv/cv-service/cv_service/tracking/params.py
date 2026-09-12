"""Tracking configuration: the one place a wire sentinel becomes a number.

`docs/extracts/TRACKING-ORCHESTRATION.md` §2.1 (charter), §4.1-§4.3 (the layering and
the resolve-once rule).

Configuration is a **layer**, not a field on the thing being configured, and
precedence runs strictly left to right::

    per-stream request  >  deployment env  >  code default
    TrackingConfig         CV_TRACK_*         cv_service/config.py

`resolve()` is the only function in cv-service that turns the wire's
``<=0 = server default`` sentinels into numbers, and `cv_service/config.py`
is the only place the ``CV_TRACK_*`` environment variables are read (this
module never touches ``os.environ``). Together those two facts are what make
"no cadence, threshold or count literal in `scheduler.py`, `session.py` or an
engine" checkable rather than aspirational -- every such value in this
package traces back to a `TrackingParams` field.

**Retired engine ids (CV-ORCHESTRATION wave W4, decision E16).** `resolve()`
is also the one place a retired ASSOCIATE engine id is rewritten to its
replacement (`RETIRED_ASSOCIATORS` below) -- a deployment's `CV_TRACK_
ASSOCIATE_ENGINE=bytetrack`, or a client's wire `engine_id="bytetrack"` in
ASSOCIATE mode, keeps tracking on `cost` instead of silently losing every
track id, which is what `EngineSet`'s `FOLLOW -> ASSOCIATE -> OFF` ladder
would otherwise do to a stream whose engine id simply stopped resolving.
ASSOCIATE only: `engine_id` doubles as the FOLLOW engine id too, and no
follower has ever been named `bytetrack`, so aliasing it there would corrupt
FOLLOW for no reason.

**Resolved on config CHANGE, never per frame.** `TrackingConfig` is restated
on *every* `FrameRequest` (deliberately -- TRACKING-PLAN invariant P2: the
mailbox may silently drop a frame, so a one-shot control message could be
lost with no error, while a restated desired state is self-healing). The
per-frame cost of that design is one protobuf equality check in
`grpc/servicers.py`; `resolve()` runs only when the check says the config
actually changed. See `StreamTrackingSession.apply_config`.

Pure stdlib. `TrackingRequest`/`LockRequest` are the plain, protobuf-free
counterparts of `cv_pb2.TrackingConfig`/`cv_pb2.TargetLock` -- the servicer
builds them; nothing here knows protobuf exists.
"""

from __future__ import annotations

import dataclasses
import logging
from dataclasses import dataclass
from typing import TYPE_CHECKING, Optional, Tuple

from cv_service.tracking.assign import AssignGates, AssignWeights
from cv_service.tracking.memory import MemoryParams

if TYPE_CHECKING:  # pragma: no cover - typing only
    from cv_service.config import Settings

# Mode names match `cv_pb2.TrackingMode`'s enum VALUE NAMES one-for-one, so
# the servicer translates with `cv_pb2.TrackingMode.Name(msg.mode)` in one
# direction and never needs a mapping table -- the same convention
# `cv_service/inference/detector.py`'s ENCODING_* constants already use for
# `ImageEncoding`.
MODE_UNSPECIFIED = "TRACKING_MODE_UNSPECIFIED"
MODE_OFF = "TRACKING_MODE_OFF"
MODE_ASSOCIATE = "TRACKING_MODE_ASSOCIATE"
MODE_FOLLOW = "TRACKING_MODE_FOLLOW"

# UNSPECIFIED (an old client, or a `tracking` field that was never set) is
# treated as OFF -- TRACKING-PLAN §4.A's proto3-additivity contract.
# How many verify opportunities a FOLLOW track gets before the wall clock may
# declare it LOST. Structural, not an operator knob: the knob is
# `CV_TRACK_MAX_AGE_MILLIS`, and this is what stops that knob from meaning
# something different in the two modes -- see `effective_max_age_millis`.
_LOST_VERIFY_OPPORTUNITIES = 3

_ACTIVE_MODES = frozenset({MODE_ASSOCIATE, MODE_FOLLOW})
_KNOWN_MODES = frozenset({MODE_UNSPECIFIED, MODE_OFF, MODE_ASSOCIATE, MODE_FOLLOW})

LOGGER = logging.getLogger("cv_service.tracking.params")

# CV-ORCHESTRATION wave W4 (decision E16): associator ids `registry.py`'s
# `BUILTIN_ASSOCIATORS` no longer constructs, mapped to the replacement
# `resolve()` serves instead. `bytetrack` is retired -- every associator now
# shares one evidence graph (`cv_service/orchestration/`), and its Kalman
# state, held behind ultralytics' `BYTETracker`, had no seam to warp, rescue
# against or hang a descriptor on -- but a deployment or client still naming
# it must not go silently untracked, so it resolves to the measured-
# equal-or-better `cost` (decision E11, `registry.py`'s own comment) instead
# of falling through `EngineSet`'s degradation ladder. A named module-level
# table, not an inline string comparison, per CLAUDE.md rule 1 (no bare
# literals branching in code) and so a second retirement is one dict entry,
# not a second `if`.
RETIRED_ASSOCIATORS: "dict[str, str]" = {"bytetrack": "cost"}

# Dedup for `_warn_retired_associator_once` -- process-wide, the same
# "log this surprising fact exactly once" idiom `TrackerRegistry._warned_
# unknown_ids`/`EngineSet._degraded_engine_ids` already use elsewhere in this
# package, just module-level here because `resolve()` is a plain function
# with no instance to hold it on.
_warned_retired_associators: "set[str]" = set()


def _warn_retired_associator_once(requested: str, replacement: str) -> None:
    """Log a retired-engine alias exactly once per process, not once per frame.

    `resolve()` runs on every config CHANGE (see the module docstring's
    "resolved on config change, never per frame"), so a stream whose operator
    never updates it would otherwise say nothing at all -- once, at the
    moment it is first substituted, is what an operator actually greps for.
    """
    if requested in _warned_retired_associators:
        return
    _warned_retired_associators.add(requested)
    LOGGER.warning(
        "tracking: ASSOCIATE engine_id=%r is retired (CV-ORCHESTRATION E16); "
        "resolving to %r instead",
        requested,
        replacement,
    )


@dataclass(frozen=True)
class LockRequest:
    """Plain counterpart of `cv_pb2.TargetLock` (TRACKING-PLAN §4.A).

    Exactly one of `track_id` / `point_x`+`point_y` / `box` identifies the
    target, and `release` overrides all of them. The one-of-three rule is
    validated Java-side (`PATCH .../config` returns 400); this side is
    deliberately forgiving and just applies the most specific form it was
    given, because a stricter cv-service would turn a client bug into a dead
    stream instead of a rejected request.
    """

    lock_seq: int = 0
    track_id: int = 0
    point_x: float = 0.0
    point_y: float = 0.0
    box: Optional[Tuple[float, float, float, float]] = None
    release: bool = False


@dataclass(frozen=True)
class TrackingRequest:
    """Plain counterpart of `cv_pb2.TrackingConfig` -- sentinels intact.

    Every numeric field still carries the wire's raw value, including the
    ``<=0 = server default`` sentinel. Nothing but `resolve()` may interpret
    them.
    """

    mode: str = MODE_UNSPECIFIED
    engine_id: str = ""
    verify_every_millis: int = 0
    redetect_iou_threshold: float = 0.0
    max_age_frames: int = 0
    min_hits: int = 0
    lock: Optional[LockRequest] = None
    # TRACKING-V2-PLAN wave C2 -- proto field 8, frozen at C0. "" = server
    # default for the deployment (`CV_TRACK_MOTION_ENGINE`); "off" disables
    # ego-motion compensation outright rather than falling back to a
    # different engine, which is why `resolve()` below does not fold it into
    # the blank-sentinel branch the way `engine_id` is.
    motion_engine_id: str = ""
    # TRACKING-V2-PLAN wave C3 -- proto field 9, frozen at C0 (same shape as
    # `motion_engine_id` above: "" = server default, "off" is a legitimate
    # resolved value, not a sentinel `resolve()` rewrites).
    appearance_engine_id: str = ""
    # TRACKING-V2-PLAN wave C4 -- proto field 10, frozen at C0. `<=0 =
    # server default` (`CV_TRACK_MEMORY_TTL_MILLIS`), the SAME sentinel
    # shape as `verify_every_millis`/`max_age_frames` above -- a positive
    # request value asks for that exact TTL, and `<=0` falls back to the
    # deployment default. Unlike `motion_engine_id`/`appearance_engine_id`'s
    # string `"off"`, there is no distinct value this int field could carry
    # to mean "disable the gallery for THIS request only" as opposed to
    # "use the deployment's own choice" -- disabling memory is therefore a
    # DEPLOYMENT decision (`CV_TRACK_MEMORY_TTL_MILLIS<=0`, a legitimate
    # resolved value, not a sentinel -- see `resolve()` below).
    memory_ttl_millis: int = 0
    # TRACKING-V3-PLAN wave V1 -- proto field 11. `<=0 = server default`
    # (`CV_TRACK_CAPABILITY_LEVEL`), the SAME sentinel shape as `verify_
    # every_millis`/`max_age_frames` above. Unlike those, the resolved
    # value this module hands to `TrackingParams` may ITSELF still be `0`
    # (the deployment default's own natural meaning, "auto-probe") --
    # `resolve()` below never turns this into a concrete level number, the
    # same "not fully resolved here" division of labour `motion_engine_id`'s
    # own docstring draws: deciding what a resolved value actually SERVES
    # needs live host state (`cv_service.tracking.levels.probe()`) this
    # module does not have, so that is `session.py`'s job.
    capability_level: int = 0
    # TRACKING-V3-PLAN wave V3 -- proto field 14. `<=0 = server default`
    # (`CV_TRACK_REUPDATE_MAX_GAP_MILLIS`), the SAME sentinel shape as
    # `verify_every_millis`/`max_age_frames` above. Unlike those, but
    # exactly like `memory_ttl_millis` above, the DEPLOYMENT default itself
    # may legitimately be `<=0` -- see that field's own comment and
    # `config.py`'s `DEFAULT_TRACK_REUPDATE_MAX_GAP_MILLIS`: a gap ORU can
    # never reconstruct (a non-positive ceiling, against every real gap
    # being positive) is how ORU is switched off fleet-wide, which invariant
    # P7 (reversibility) needs a way to do.
    reupdate_max_gap_millis: int = 0


@dataclass(frozen=True)
class TrackingParams:
    """Fully-resolved per-stream tracking configuration. No sentinels left.

    Held by `StreamTrackingSession` and read by `DutyCycleScheduler` and
    `TrackBook`; re-created only when the wire config changes.
    """

    mode: str
    engine_id: str
    verify_every_millis: int
    reacquire_every_millis: int
    redetect_iou_threshold: float
    max_age_frames: int
    min_hits: int
    # TRACKING-V2-PLAN wave C1 additions. Neither has a wire counterpart yet
    # (the frozen diff in TRACKING-V2-PLAN §2 does not add one) -- both are
    # deployment-only knobs, resolved straight from `Settings` with no
    # per-request sentinel to interpret, unlike every field above.
    track_max_age_millis: int
    min_tracker_confidence: float
    # TRACKING-V2-PLAN wave C2 addition. Unlike `engine_id`, this is never
    # rewritten to a concrete roster id here -- `"off"` is a legitimate
    # resolved value, not a sentinel, and it is `session.py`'s job (not
    # `resolve()`'s) to decide what engine, if any, actually serves it,
    # because that decision also needs a live `CameraPose` (see
    # `TrackerRegistry.compensator` and `StreamTrackingSession`'s
    # pose-unavailable-falls-back-to-flow policy).
    motion_engine_id: str
    # TRACKING-V2-PLAN wave C3 addition. Same "not fully resolved here" shape
    # as `motion_engine_id`: "off" is a legitimate resolved value, and
    # deciding whether anything actually SERVES a non-off request is
    # `session.py`'s `_resolve_appearance_extractor` (no live signal to check
    # like `pose`'s `CameraPose`, so no fallback ladder -- constructibility
    # IS availability here).
    appearance_engine_id: str
    # TRACKING-V2-PLAN wave C3 additions. Unlike every field above, these
    # have no wire sentinel to interpret at all (§2's frozen diff adds none
    # for cost weights/gates) -- `resolve()` builds the two frozen
    # `assign.py` dataclasses directly from `Settings`, with no per-request
    # override to fall back FROM. `session.py`'s `_run_cost_associate` is
    # what may further zero `cost_weights.appearance` for a stream where no
    # appearance extractor actually resolved -- that decision needs runtime
    # state `resolve()` does not have, same division of labour `motion_
    # engine_id`'s own docstring draws.
    cost_weights: AssignWeights
    cost_gates: AssignGates
    # TRACKING-V2-PLAN wave C4 addition. Built DIRECTLY by `resolve()`, same
    # shape as `cost_weights`/`cost_gates` above -- `ttl_millis` is the one
    # field with a wire sentinel to interpret (`memory_ttl_millis`, `Tracking
    # Request` above); the rest come straight from `Settings` with no
    # per-request override to fall back FROM. `ttl_millis <= 0` is memory
    # DISABLED for this stream -- `session.py`'s `_resolve_memory` is what
    # turns that into "no `ObjectMemory` is even constructed", never an
    # empty gallery still being consulted every frame (P5).
    memory_params: MemoryParams
    # TRACKING-V2-PLAN wave C5b addition (review finding C6). Same
    # "deployment-only, no wire field" shape as `track_max_age_millis`/
    # `min_tracker_confidence` above -- `session.py` reads it as "the LOCKED
    # target plus up to `follow_top_k - 1` other targets, each with its own
    # `SingleObjectTracker` instance". `1` reproduces FOLLOW's pre-C5b
    # single-target behaviour exactly -- see `config.py`'s `DEFAULT_TRACK_
    # FOLLOW_TOP_K` for why that, not a larger number, is what ships.
    follow_top_k: int
    # TRACKING-V2-PLAN wave C5c addition (review §4.6, "detection recall").
    # Same "deployment-only, no wire field" shape as `follow_top_k` directly
    # above -- `session.py`'s `_roi_rescue` reads `roi_enabled` as the whole
    # feature's on/off switch (checked FIRST, before anything else is even
    # computed -- a genuine no-op when `False`) and `roi_crop_factor` as how
    # large a crop it builds around a rescued candidate's predicted box. See
    # `config.py`'s `DEFAULT_TRACK_ROI_ENABLED` for why this ships opt-in.
    roi_enabled: bool
    roi_crop_factor: float
    roi_min_iou: float
    # TRACKING-V3-PLAN wave V1 addition (§5). The REQUESTED ceiling, `<=0`
    # sentinel already resolved against the deployment default -- but,
    # deliberately unlike every int field above, `0` remains a legitimate
    # RESOLVED value here (auto-probe), not merely "unset". `session.py`'s
    # `_resolve_capability_level` is the only place this becomes an actual
    # served level (`cv_service.tracking.levels.resolve`), exactly the
    # "resolve the sentinel here, decide what serves it there" split
    # `motion_engine_id`/`appearance_engine_id` already establish.
    capability_level: int
    # TRACKING-V3-PLAN wave V3 addition. Fully resolved (unlike `capability_
    # level` above, `0` is never a legitimate value here once resolved) --
    # `track.py`'s `_observe` reads this straight, no further translation.
    # `<=0` (a resolved value, not merely a sentinel this field could still
    # carry) means ORU never fires for this stream: `reupdate.py`'s own
    # `gap_millis > max_gap_millis` check rejects every gap once the ceiling
    # is non-positive, since a gap is by definition positive.
    reupdate_max_gap_millis: int
    # TRACKING-V3-PLAN wave V6 addition (§4.5). Deployment-only, no wire
    # field -- same "no per-request override to fall back FROM" shape as
    # `follow_top_k`/`roi_enabled` above. Whether `session.py` applies
    # late-detection back-correction AT ALL when a caller measures a
    # positive `detection_lag_millis` for this frame (today, only
    # `DetectPulled`'s own capture-skew estimate). `reupdate_max_gap_millis`
    # above already bounds HOW FAR any one correction may reconstruct
    # (reused, not duplicated -- both share the same "too old to trust"
    # ceiling); this is the INDEPENDENT switch invariant P7 needs for the
    # correction MECHANISM itself, so a fleet can run post-occlusion ORU
    # without also opting into per-detection back-correction, or the
    # reverse, rather than one knob answering two different questions.
    detection_lag_correction_enabled: bool
    # 2026-08-14 repair, part 2 (`docs/conclusions/TRACKING-BENCHMARK-
    # RESULTS.md` §4/§8). Deployment-only, no wire field -- same "no per-
    # request override to fall back FROM" shape as `follow_top_k`/
    # `roi_enabled` above. `reupdate.py`'s own guard reads this straight: a
    # reconstruction whose implied `|velocity_x|`/`|velocity_y|` exceeds it
    # (normalized frame-widths/heights per second, `Detection.velocity_x`/
    # `_y`'s own units) is refused outright (`None`, never clamped) rather
    # than trusted -- ORU made MOT17 identity switches WORSE in 15 of 21
    # scene/detector pairs precisely because it had no such test. `<=0`
    # disables the guard entirely, reproducing pre-repair behaviour exactly
    # (invariant P7) -- same shape as `reupdate_max_gap_millis` above.
    reupdate_max_velocity_per_second: float
    # 2026-08-15 density gate (`docs/conclusions/TRACKING-BENCHMARK-
    # RESULTS.md` §4b/§8). Deployment-only, no wire field -- same "no per-
    # request override to fall back FROM" shape `detection_lag_correction_
    # enabled`/`reupdate_max_velocity_per_second` above already use. §4b's
    # own density split (0 of 7 crowded scenes improved, +320 net IDSW,
    # against 5 of 14 sparse scenes improved, +57 net) is what motivates a
    # SECOND, independent refusal on top of the velocity guard: a bracket
    # can imply a plausible velocity and still be built from two different
    # objects, and that gets more likely, not less, as the scene fills up.
    # `reupdate.py`'s own guard reads this straight: when the number of
    # LIVE TRACKS in the book at the moment of re-anchor exceeds it, the
    # reconstruction is refused outright (`None`, never clamped) -- the
    # SAME contract `reupdate_max_velocity_per_second` already established.
    # Track count, not detections/frame (what §4b actually measured) --
    # `reupdate.py`'s own module docstring records why that signal is not
    # reachable at either real call site without threading a new argument
    # through several `session.py` layers, and states plainly that a track
    # count is a PROXY, not the measured quantity. `<=0` disables the gate
    # entirely, reproducing today's exact behaviour (invariant P7) -- same
    # shape as `reupdate_max_velocity_per_second` above. Defaulted to `0`
    # (DISABLED) in `config.py`'s `DEFAULT_TRACK_REUPDATE_MAX_TRACK_COUNT`:
    # unlike the velocity bound, no sweep against real footage has picked a
    # value for this one yet -- see that constant's own comment.
    reupdate_max_track_count: int
    # 2026-08-15 bracket-identity check (`docs/conclusions/TRACKING-
    # RECOVERY-RESEARCH.md` §2.1). Deployment-only, no wire field -- same
    # "no per-request override to fall back FROM" shape `reupdate_max_
    # velocity_per_second`/`reupdate_max_track_count` above already use.
    # Both guards above test the bracket's TIMING and the SCENE's crowding;
    # neither ever tests the bracket's own two observations against each
    # other. `docs/conclusions/TRACKING-BENCHMARK-RESULTS.md` §4b/§4c
    # measured that this leaves ORU net negative even with both guards
    # active (+377 IDSW vs ORU off; no density-gate threshold swept across
    # 6/8/10/12/15/20/30 tracks made ORU pay) -- these two fields back a
    # THIRD and FOURTH gate, each looking at the bracket's own two boxes
    # directly instead of a proxy signal around them.
    #
    # Check A -- shape consistency: `reupdate.py`'s own guard refuses a
    # reconstruction when the bracket's two box dimensions differ by more
    # than this many natural-log units on either axis (`|ln(w2/w1)|` or
    # `|ln(h2/h1)|`) -- a log-ratio, not a raw ratio, so growth and
    # shrinkage are symmetric and one bound serves both directions and
    # every object size (scale-invariant by construction). `<=0` disables
    # the check entirely, the SAME off-switch shape every ceiling above
    # uses (invariant P7).
    reupdate_max_shape_log_ratio: float
    # Check B -- motion plausibility: `reupdate.py`'s own guard forward-
    # predicts the bracket's earlier observation to the later one's own
    # timestamp using the TRACK's own PRE-GAP velocity (the constant-
    # velocity arithmetic `predict.py` implements, applied from the
    # bracket rather than the track's current, possibly-drifted box), and
    # refuses when that forecast lands more than this many BOX-DIAGONALS
    # from the real later observation's centre -- a size-scaled centre
    # distance, deliberately not IoU (`reupdate.py`'s own module docstring
    # has the full reasoning: IoU degrades to a hard `0.0` the instant two
    # boxes fail to overlap, the ORDINARY case after a multi-second gap on
    # a fast or small target). `<=0` disables the check entirely, the SAME
    # off-switch shape every ceiling above uses (invariant P7).
    #
    # Both fields default to `0.0` (DISABLED) in `config.py`'s `DEFAULT_
    # TRACK_REUPDATE_MAX_SHAPE_LOG_RATIO`/`DEFAULT_TRACK_REUPDATE_MAX_
    # MOTION_CENTER_DISTANCE`, and BOTH ARE PROVISIONAL: unlike the
    # velocity bound (derived from this platform's own documented
    # worst-case motion), nobody has yet swept either bound against the
    # real MOT17 matrix -- see each constant's own comment in `config.py`
    # for why picking a number here from intuition would repeat the exact
    # mistake the density gate's own default already avoids. Each is
    # independently disable-able, so a fleet (or the sweep itself) can run
    # A alone, B alone, both, or neither, and compare.
    reupdate_max_motion_center_distance: float
    # TRACK-IDENTITY-PLAN wave L1 addition. Deployment-only, no wire field --
    # same "no per-request override to fall back FROM" shape as `follow_
    # top_k`/`roi_enabled`/`detection_lag_correction_enabled` above.
    # `track.py`'s `_update_label_election` reads all three straight: how
    # many of a track's own recent `SOURCE_DETECTOR` observations feed its
    # per-label tally (older votes fall off the ring AND are exponentially
    # decayed), how far a challenger label's decayed score must exceed the
    # incumbent elected label's before it is even eligible, and how many
    # CONSECUTIVE passes it must hold that lead before the election
    # actually switches. See `config.py`'s `DEFAULT_TRACK_LABEL_VOTE_
    # WINDOW`/`_SWITCH_MARGIN`/`_SWITCH_STREAK` for why those three
    # numbers, not others, ship.
    label_vote_window: int
    label_switch_margin: float
    label_switch_streak: int

    @property
    def active(self) -> bool:
        """Whether any tracking work happens at all (mode is not OFF)."""
        return self.mode in _ACTIVE_MODES

    @property
    def effective_reacquire_millis(self) -> int:
        """How often FOLLOW may spend a pass re-acquiring, never slower than
        it would ordinarily verify.

        A rate limit that could exceed `verify_every_millis` would be
        backwards: an operator who asks for a very short cadence is asking
        the detector to look often, and re-acquiring a target they have LOST
        is more urgent than re-confirming one they still hold, not less. So
        this bites only where it was meant to -- a long cadence, where trigger
        (c) would otherwise run the detector on every single frame.
        """
        return min(self.reacquire_every_millis, self.verify_every_millis)

    @property
    def effective_max_age_millis(self) -> int:
        """The wall-clock LOST deadline, floored at N verify opportunities.

        `track_max_age_millis` alone unifies the two modes' *rule* but not
        their *meaning*, because the two modes do not offer the detector
        opportunities at the same rate. In ASSOCIATE a pass runs on every
        received frame, so the default is ~30 chances at the documented
        sample rate. In FOLLOW passes arrive on cadence, so the same flat
        number is barely one and a half chances -- and a FOLLOW track going
        LOST unbinds the lock, which makes scheduler trigger (c) spend a
        detector pass on EVERY frame until something is re-acquired. A knob
        meant to bound staleness would therefore have quietly destroyed the
        duty cycle precisely when the target is hardest, which is the failure
        this floor exists to prevent.

        Expressed as a floor rather than a replacement so an operator who
        deliberately raises `CV_TRACK_MAX_AGE_MILLIS` still gets what they
        asked for, and so ASSOCIATE -- where `verify_every_millis` is not
        consulted at all -- is left exactly as it was.
        """
        if self.mode != MODE_FOLLOW:
            return self.track_max_age_millis
        return max(
            self.track_max_age_millis,
            _LOST_VERIFY_OPPORTUNITIES * self.verify_every_millis,
        )

    def with_mode(self, mode: str, engine_id: str) -> "TrackingParams":
        """A copy one step down the degradation ladder.

        The `FOLLOW -> ASSOCIATE -> OFF` fallback changes the mode and the
        engine and nothing else -- cadence, IoU and the counts stay whatever
        the operator asked for, so a stream that recovers does not silently
        come back with different tuning.
        """
        return dataclasses.replace(self, mode=mode, engine_id=engine_id)


def normalize_mode(mode: str) -> str:
    """Map a wire mode name to a known one; anything unrecognized -> OFF.

    A mode name this build does not know can only come from a *newer* client
    against an older cv-service. Treating it as OFF is the same defensive
    posture proto3 additivity already gives an unset field, and it keeps the
    "never a dead stream" rule (TRACKING-ORCHESTRATION §3.4).
    """
    if mode not in _KNOWN_MODES:
        return MODE_OFF
    if mode == MODE_UNSPECIFIED:
        return MODE_OFF
    return mode


def resolve(request: TrackingRequest, settings: "Settings") -> TrackingParams:
    """Resolve one wire request against deployment defaults.

    THE ONLY PLACE a `<=0` sentinel becomes a number. Each field falls back
    to its `CV_TRACK_*`-backed `Settings` value; the engine id falls back to
    the default engine *for the resolved mode*, which is why the two engine
    knobs are separate (`CV_TRACK_ASSOCIATE_ENGINE` / `CV_TRACK_FOLLOW_ENGINE`)
    -- a single "default engine" would be meaningless across two protocols
    that share no method.
    """
    mode = normalize_mode(request.mode)
    engine_id = request.engine_id.strip() if request.engine_id else ""
    if not engine_id:
        engine_id = (
            settings.track_follow_engine
            if mode == MODE_FOLLOW
            else settings.track_associate_engine
        )
    # CV-ORCHESTRATION wave W4 (decision E16), ASSOCIATE only -- see
    # `RETIRED_ASSOCIATORS`'s own comment. `engine_id` doubles as the FOLLOW
    # engine id above, and no follower is named `bytetrack`, so this must
    # gate on the resolved MODE, not merely on the string, or a FOLLOW
    # request would be corrupted by a rule that was never about it.
    if mode == MODE_ASSOCIATE and engine_id in RETIRED_ASSOCIATORS:
        replacement = RETIRED_ASSOCIATORS[engine_id]
        _warn_retired_associator_once(engine_id, replacement)
        engine_id = replacement
    return TrackingParams(
        mode=mode,
        engine_id=engine_id,
        verify_every_millis=(
            request.verify_every_millis
            if request.verify_every_millis > 0
            else settings.track_verify_millis
        ),
        # No wire field: this bounds a runaway rather than expressing an
        # operator preference, so it is deployment-only, like the memory and
        # cost knobs.
        reacquire_every_millis=settings.track_reacquire_millis,
        redetect_iou_threshold=(
            request.redetect_iou_threshold
            if request.redetect_iou_threshold > 0
            else settings.track_iou
        ),
        max_age_frames=(
            request.max_age_frames if request.max_age_frames > 0 else settings.track_max_age_frames
        ),
        min_hits=(request.min_hits if request.min_hits > 0 else settings.track_min_hits),
        # No wire field exists for either of these yet (TRACKING-V2-PLAN
        # §2's frozen diff does not add one) -- both come straight from the
        # deployment layer, same shape `resolve()` already gives every other
        # `CV_TRACK_*` default, just with no request-level override to fall
        # back FROM.
        track_max_age_millis=settings.track_max_age_millis,
        min_tracker_confidence=settings.track_min_tracker_confidence,
        # Blank -> the deployment default (`CV_TRACK_MOTION_ENGINE`, itself
        # possibly "off"); anything else, including an explicit "off",
        # passes through untouched -- same blank-only-sentinel shape
        # `engine_id` above uses, just with a value space ("off" included)
        # `session.py` interprets rather than a roster id `resolve()` could
        # validate here.
        motion_engine_id=(
            request.motion_engine_id.strip()
            if request.motion_engine_id
            else settings.track_motion_engine
        ),
        appearance_engine_id=(
            request.appearance_engine_id.strip()
            if request.appearance_engine_id
            else settings.track_appearance_engine
        ),
        cost_weights=AssignWeights(
            iou=settings.track_cost_weight_iou,
            appearance=settings.track_cost_weight_appearance,
            label=settings.track_cost_weight_label,
        ),
        cost_gates=AssignGates(
            min_iou=settings.track_cost_gate_min_iou,
            max_appearance=settings.track_cost_gate_max_appearance,
            max_cost=settings.track_cost_gate_max_cost,
            high_confidence=settings.track_cost_gate_high_confidence,
        ),
        memory_params=MemoryParams(
            ttl_millis=(
                request.memory_ttl_millis
                if request.memory_ttl_millis > 0
                else settings.track_memory_ttl_millis
            ),
            capacity=settings.track_memory_capacity,
            gallery_size=settings.track_memory_gallery_size,
            max_appearance_distance=settings.track_memory_max_appearance_distance,
            max_speed=settings.track_memory_max_speed,
            blend_alpha=settings.track_memory_blend_alpha,
            min_confidence=settings.track_memory_min_confidence,
        ),
        # No wire field (TRACKING-V2-PLAN wave C5b) -- straight from
        # `Settings`, same shape as `track_max_age_millis` above.
        follow_top_k=settings.track_follow_top_k,
        # No wire field (TRACKING-V2-PLAN wave C5c) -- straight from
        # `Settings`, same shape as `follow_top_k` directly above.
        roi_enabled=settings.track_roi_enabled,
        roi_crop_factor=settings.track_roi_crop_factor,
        roi_min_iou=settings.track_roi_min_iou,
        # TRACKING-V3-PLAN wave V1 -- `<=0` (including the deployment
        # default itself defaulting to `0`) falls back to `Settings`,
        # exactly the `verify_every_millis` shape; `0` surviving into
        # `TrackingParams` is intentional (see that field's own docstring).
        capability_level=(
            request.capability_level
            if request.capability_level > 0
            else settings.track_capability_level
        ),
        # TRACKING-V3-PLAN wave V3 -- `<=0` falls back to `Settings`, exactly
        # the `memory_ttl_millis` shape: the DEPLOYMENT value itself may be
        # `<=0` too (see `TrackingParams.reupdate_max_gap_millis`'s own
        # docstring), and that is a legitimate "ORU off fleet-wide" choice
        # this call passes through unchanged, never replaced by a second
        # fallback.
        reupdate_max_gap_millis=(
            request.reupdate_max_gap_millis
            if request.reupdate_max_gap_millis > 0
            else settings.track_reupdate_max_gap_millis
        ),
        # TRACKING-V3-PLAN wave V6 -- straight from `Settings`, no wire
        # field (same shape as `follow_top_k`/`roi_enabled` above).
        detection_lag_correction_enabled=settings.track_detection_lag_correction_enabled,
        # 2026-08-14 repair, part 2 -- straight from `Settings`, no wire
        # field (same shape as `detection_lag_correction_enabled` above).
        reupdate_max_velocity_per_second=settings.track_reupdate_max_velocity_per_second,
        # 2026-08-15 density gate -- straight from `Settings`, no wire field
        # (same shape as `reupdate_max_velocity_per_second` directly above).
        reupdate_max_track_count=settings.track_reupdate_max_track_count,
        # 2026-08-15 bracket-identity check -- straight from `Settings`, no
        # wire field (same shape as `reupdate_max_track_count` directly
        # above). Each resolves independently -- neither falls back to or
        # depends on the other's value.
        reupdate_max_shape_log_ratio=settings.track_reupdate_max_shape_log_ratio,
        reupdate_max_motion_center_distance=settings.track_reupdate_max_motion_center_distance,
        # TRACK-IDENTITY-PLAN wave L1 addition -- deployment-only, no wire
        # field (same shape as `follow_top_k`/`roi_enabled` above).
        label_vote_window=settings.track_label_vote_window,
        label_switch_margin=settings.track_label_switch_margin,
        label_switch_streak=settings.track_label_switch_streak,
    )
