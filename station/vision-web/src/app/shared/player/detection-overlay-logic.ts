import type { Detection, DetectionResult, WorldObject } from '../../core/api/models';
import type { Transport } from './player-recovery';
import { CV_STATUS_FRESH_SECONDS } from '../../core/detections/detections-logic';

/**
 * Pure logic behind the client-side vector detection overlay (docs/main/CYCLES-PLAN.md §11, CD-b item
 * 6): which completed `DetectionResult` batch best matches the frame currently on-screen, given
 * HLS's live-edge latency. Split out so the sync math is unit-testable without a `<canvas>`,
 * hls.js, or a poller — mirrors `shared/player/player-recovery.ts`.
 */

/**
 * Declutter levels (docs/plans/done/CV-FLY-INTERACTION-RESEARCH.md §3.6, wave W4) — the per-tile
 * density control, extended from W3's two-state `'overlay' | 'off'` to four named states. Avionics
 * practice (research §4.6): discrete, named declutter modes, never a slider. `'all'` draws every
 * tier (T0-T3, today's old `'overlay'` posture); `'priority'` draws T0+T1 (full boxes) plus T3 (dots)
 * but hides T2 (ambient); `'locked'` draws only T0 (the FOLLOW-locked track and/or the hovered box);
 * `'off'` draws nothing at all — see `shouldDrawOverlay`'s own doc comment for what that means beyond
 * "no boxes" (no hover/click either). The type keeps the name `BoxesMode` — every consumer already
 * names its own signal/input `boxesMode`, and the control still answers the same question ("how are
 * boxes drawn") — only the *values* changed shape, from a rendering toggle to a density level. See
 * {@link tiersForDeclutterLevel} for which tiers each level actually draws.
 */
export type BoxesMode = 'all' | 'priority' | 'locked' | 'off';

/** Every declutter level, in cycle order — exported so a segmented-control template (`cv-control-
 *  panel.html`, `live.html`) can `@for` over one typed source of truth instead of each restating the
 *  four literals (and risking one drifting out of sync with {@link cycleBoxesMode}'s own order). */
export const DECLUTTER_LEVELS: readonly BoxesMode[] = ['all', 'priority', 'locked', 'off'];
const DECLUTTER_CYCLE = DECLUTTER_LEVELS;

/** The declutter level every fresh player/tile/facade seeds its own signal to — research §3.6's own
 *  "Priority is the sane default", not `'all'` (the undifferentiated everything-draws posture the
 *  whole tier system in this wave exists to move away from). */
export const DEFAULT_DECLUTTER_LEVEL: BoxesMode = 'priority';

/** Type guard for a persisted `declutterLevel` value (`core/settings/settings-store.ts` — H12, wave
 *  W7: the one shared, persisted declutter preference) — a corrupt/pre-wave `localStorage` value
 *  (`undefined`, a stale `'overlay'`/`'burned'` literal, anything not one of the four current
 *  {@link DECLUTTER_LEVELS}) is rejected here rather than adopted, mirroring every other persisted
 *  field's own restore-time validation in that store. */
export function isBoxesMode(value: unknown): value is BoxesMode {
  return typeof value === 'string' && (DECLUTTER_LEVELS as readonly string[]).includes(value);
}

/** `B` (Fly) / the wall tile's own toggle button / the Live and cv-control-panel segmented controls —
 *  cycles All → Priority → Locked-only → Off → All (research §3.6). A `current` no longer present in
 *  the cycle (a stale value from before this wave, e.g. `'overlay'`/`'burned'` surviving in a
 *  pre-wave persisted signal) restarts from the cycle's first entry rather than throwing or standing
 *  still — the same degrade choice W3's own two-state cycle already made. */
export function cycleBoxesMode(current: BoxesMode): BoxesMode {
  const index = DECLUTTER_CYCLE.indexOf(current);
  return index === -1 ? DECLUTTER_CYCLE[0] : DECLUTTER_CYCLE[(index + 1) % DECLUTTER_CYCLE.length];
}

/** The declutter level's own display name — every control that renders the four states
 *  (`cv-control-panel.html`'s "Boxes rendering" section, `live.html`'s segmented control, the wall
 *  tile's toggle button title) reads this instead of restating the four labels as literals in three
 *  different templates. */
export function declutterLevelLabel(mode: BoxesMode): string {
  switch (mode) {
    case 'all':
      return 'All';
    case 'priority':
      return 'Priority';
    case 'locked':
      return 'Locked only';
    case 'off':
      return 'Off';
  }
}

/**
 * How much slack (in units of "one detection batch interval") to tolerate beyond the raw latency
 * estimate before discarding a result as "not on screen yet" — docs/main/CYCLES-PLAN.md §11 item 6's
 * "±1 batch of slack", covering ordinary jitter between the CV pipeline's sampling cadence and the
 * poll cycle that fetched `results`.
 */
export const DEFAULT_SLACK_BATCHES = 1;

/**
 * Picks which completed `DetectionResult` best represents what is on-screen *right now*.
 *
 * HLS runs the `<video>` element several seconds behind the true live edge
 * (`shared/player/player.ts`'s own `behindLive`/hls.js `latency`). A detection batch's `capturedAt` is a
 * *source* timestamp — the instant the frame was sampled off the pipeline, not when the browser
 * displays it. The frame currently visible was captured at approximately
 * `nowMs - latencySeconds*1000`; the right batch to draw is therefore the newest one whose
 * `capturedAt` does not run *ahead* of that estimated on-screen instant — drawing a box for a
 * not-yet-displayed frame would make it appear early, jittering ahead of the video.
 *
 * `results` is expected newest-first, `VisionApi.streamDetections`'s own contract. `slackBatches`
 * additionally tolerates a batch or so of scheduling jitter, sized from the results' own observed
 * cadence (`averageBatchIntervalMs`) rather than a fixed guess, since `inferenceFps` is
 * user-configurable per stream.
 */
export function selectDetectionResult(
  results: readonly DetectionResult[],
  nowMs: number,
  latencySeconds: number | null,
  slackBatches: number = DEFAULT_SLACK_BATCHES,
): DetectionResult | undefined {
  if (results.length === 0) {
    return undefined;
  }
  const onScreenAtMs = estimatedOnScreenAtMs(nowMs, latencySeconds);
  const slackMs = averageBatchIntervalMs(results) * Math.max(0, slackBatches);

  for (const result of results) {
    if (Date.parse(result.capturedAt) <= onScreenAtMs + slackMs) {
      return result;
    }
  }
  // Every result appears "in the future" relative to the on-screen frame (e.g. the latency
  // estimate hasn't settled yet, right after attach) — the oldest available beats showing nothing,
  // UNLESS that oldest result is itself stale in absolute wall-clock terms (research §3.4's staleness
  // bound, `isDetectionStale`): a fallback exists for attach jitter, not as an unbounded policy that
  // would draw a genuinely old batch as if it were fresh forever.
  const oldest = results[results.length - 1];
  return isDetectionStale(nowMs - Date.parse(oldest.capturedAt)) ? undefined : oldest;
}

/**
 * The estimated on-screen instant — `nowMs` minus the sync latency, a `null` or negative latency
 * degrading to `0` (`overlaySyncLatencySeconds`'s own "not yet measured" rule). {@link
 * selectDetectionResult} (picking the right batch) and `shared/player/player.ts#redrawOverlay`'s own
 * extrapolation target (docs/plans/done/CV-CLEAN-FEED-PLAN.md §7, wave W7 — projecting the picked
 * batch's boxes forward to this same instant) both call this one function rather than each re-deriving
 * the null/negative guard, so the two can never quietly disagree about which instant is "on screen
 * right now".
 */
export function estimatedOnScreenAtMs(nowMs: number, latencySeconds: number | null): number {
  const latencyMs = latencySeconds !== null && latencySeconds >= 0 ? latencySeconds * 1000 : 0;
  return nowMs - latencyMs;
}

/** The observed average gap between consecutive `capturedAt` values — one "batch" of `slackBatches`,
 *  and the unit {@link detectionAlphaPercent} scales its own fade threshold off. */
export function averageBatchIntervalMs(results: readonly DetectionResult[]): number {
  if (results.length < 2) {
    return 0;
  }
  let sum = 0;
  let count = 0;
  for (let i = 0; i < results.length - 1; i++) {
    const delta = Date.parse(results[i].capturedAt) - Date.parse(results[i + 1].capturedAt);
    if (delta > 0) {
      sum += delta;
      count++;
    }
  }
  return count > 0 ? sum / count : 0;
}

/**
 * The latency figure {@link selectDetectionResult} should sync boxes against, given which transport
 * is actually attached (docs/plans/done/MEDIA-SOT-PLAN.md §6/§8 wave M8).
 *
 * HLS keeps its existing behaviour exactly: `behindLiveSeconds` (`shared/player/player.ts`'s own
 * measured live-edge distance) passes through unchanged.
 *
 * WHEP used to hard-pin `behindLiveSeconds` to `0` for this purpose — a real number, not `null`, but
 * one that ignores the glass-to-glass delay a live WebRTC track genuinely has (~0.2–0.5s, MEDIA-SOT-
 * PLAN.md §6's own measurement), which made boxes **lead** the picture by that much. This function
 * is the fix: for `'webrtc'`, the already-measured `getStats()`-derived figure
 * (`player-recovery.ts#estimateWhepLatencySeconds` — half the round-trip time plus the jitter term,
 * i.e. the same number the latency badge already shows) is used instead. `null` (not yet measured —
 * e.g. the first tick or two right after attach, before a candidate-pair report has carried a round-
 * trip time) degrades to `0` rather than blocking the overlay outright: `0` is what
 * `selectDetectionResult` has always treated as "near-zero latency", the same fallback a `null`
 * `behindLiveSeconds` already gets there.
 */
export function overlaySyncLatencySeconds(
  transport: Transport,
  behindLiveSeconds: number | null,
  whepLatencySeconds: number | null,
): number | null {
  return transport === 'webrtc' ? (whepLatencySeconds ?? 0) : behindLiveSeconds;
}

// --- Forward-projection (docs/plans/done/CV-CLEAN-FEED-PLAN.md §7, wave W7) ------------------------------
// W1 deleted the server-side `DetectionExtrapolator` that used to velocity-project every burned-in
// box onto the exact frame being published; the client overlay `selectDetectionResult` picked above
// only *selects* a batch by latency, it never moves anything — so whenever video latency runs behind
// detection arrival lag (WHEP especially; the 2s poll fallback worst of all) the newest batch on hand
// is inherently stale relative to the picture. `extrapolateDetections` ports the deleted server
// behavior client-side, mirroring `DetectionExtrapolator`'s matching/velocity/freeze semantics exactly
// (see that class's own javadoc, `git show a4735f24^:contexts/vision-perception/.../DetectionExtrapolator.java`
// for the reference this was ported from).

/**
 * How far past a batch's own `capturedAt` {@link extrapolateDetections} projects a matched box's
 * center before freezing (not dropping — a stalled/outaged detector holds its last known position
 * rather than vanishing). Mirrors the server default this wave ports:
 * `StreamPipelineSettings#defaults().extrapolationMaxMillis()` / `application.yaml`'s
 * `vision.application.pipeline.extrapolation.max-millis` (`DetectionExtrapolator
 * .MAX_EXTRAPOLATION_MILLIS`). Hand-mirrored, not fetched from the server at runtime — nothing
 * enforces the two stay equal, so a tuning change on one side without the other quietly reintroduces
 * this wave's own defect (in reverse: over- or under-projecting relative to the server's old behavior).
 */
export const EXTRAPOLATION_MAX_MS = 800;

/**
 * Max normalized (0-1) box-center distance for a same-label match in {@link extrapolateDetections}'s
 * second matching pass, used only where at least one side is untracked. Mirrors
 * `StreamPipelineSettings#defaults().extrapolationMatchGate()` / `application.yaml`'s
 * `vision.application.pipeline.extrapolation.match-gate` (`DetectionExtrapolator
 * .MATCH_GATE_DISTANCE`) — see {@link EXTRAPOLATION_MAX_MS}'s own comment on why this is hand-mirrored
 * rather than server-fetched.
 *
 * **Renamed, wave W3.2** (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6): this whole matching/
 * projection pipeline is no longer invoked for every tracked object — {@link resolveDisplayDetections}
 * scopes it to the *unmatched* subset only (no track id, or a track id with no {@link WorldObject}
 * yet), so a name implying "every tracked object" would no longer be accurate.
 */
export const UNMATCHED_MATCH_GATE_DISTANCE = 0.15;

function boxCenter(box: Detection['box']): { readonly x: number; readonly y: number } {
  return { x: box.x + box.width / 2, y: box.y + box.height / 2 };
}

function centerDistance(a: { readonly x: number; readonly y: number }, b: { readonly x: number; readonly y: number }): number {
  return Math.hypot(a.x - b.x, a.y - b.y);
}

function clampUnit(value: number): number {
  return Math.max(0, Math.min(1, value));
}

/**
 * Matches `selectedDetections` to `previousDetections` — {@link extrapolateDetections}'s own two
 * passes, ported from `DetectionExtrapolator#match` unchanged:
 *  1. **By track id, exactly, with no gate.** Equal, non-`undefined` track ids on both sides are the
 *     same object — cv-service's tracker already decided that, and no distance heuristic can improve
 *     on an identity (this is what keeps velocity correct through an occlusion, or across two
 *     same-label objects that cross, where a distance gate would confidently swap them).
 *  2. **By nearest normalized box-center, gated**, over whatever pass 1 left unmatched: same `label`,
 *     within `matchGate` center distance, closest pairs assigned first, each detection used at most
 *     once — **except** a pair whose two sides both carry a track id, which the tracker has already
 *     declared to be different objects (matching them on proximity would reintroduce exactly the swap
 *     pass 1 exists to prevent). With tracking off, `track` is `undefined` everywhere, pass 1 matches
 *     nothing and only this pass runs — byte-identical to how this behaved before tracking existed.
 *
 * @returns one entry per `selectedDetections` index: the matched `previousDetections` index, or `-1`
 */
function matchDetections(
  selectedDetections: readonly Detection[],
  previousDetections: readonly Detection[],
  matchGate: number,
): number[] {
  const matchedPreviousIndex = new Array<number>(selectedDetections.length).fill(-1);
  const previousUsed = new Array<boolean>(previousDetections.length).fill(false);

  // Pass 1: track id.
  const previousIndexByTrackId = new Map<number, number>();
  previousDetections.forEach((detection, index) => {
    const trackId = detection.track?.id;
    if (trackId !== undefined && !previousIndexByTrackId.has(trackId)) {
      previousIndexByTrackId.set(trackId, index);
    }
  });
  if (previousIndexByTrackId.size > 0) {
    selectedDetections.forEach((detection, index) => {
      const trackId = detection.track?.id;
      if (trackId === undefined) {
        return;
      }
      const previousIndex = previousIndexByTrackId.get(trackId);
      if (previousIndex !== undefined && !previousUsed[previousIndex]) {
        matchedPreviousIndex[index] = previousIndex;
        previousUsed[previousIndex] = true;
      }
    });
  }

  // Pass 2: gated nearest-center, over what pass 1 left unmatched.
  interface Candidate {
    readonly selectedIndex: number;
    readonly previousIndex: number;
    readonly distance: number;
  }
  const candidates: Candidate[] = [];
  selectedDetections.forEach((detection, selectedIndex) => {
    if (matchedPreviousIndex[selectedIndex] >= 0) {
      return;
    }
    const center = boxCenter(detection.box);
    previousDetections.forEach((previousDetection, previousIndex) => {
      if (
        previousUsed[previousIndex] ||
        previousDetection.label !== detection.label ||
        (detection.track && previousDetection.track)
      ) {
        return;
      }
      const distance = centerDistance(center, boxCenter(previousDetection.box));
      if (distance <= matchGate) {
        candidates.push({ selectedIndex, previousIndex, distance });
      }
    });
  });
  candidates.sort((a, b) => a.distance - b.distance);
  for (const candidate of candidates) {
    if (matchedPreviousIndex[candidate.selectedIndex] < 0 && !previousUsed[candidate.previousIndex]) {
      matchedPreviousIndex[candidate.selectedIndex] = candidate.previousIndex;
      previousUsed[candidate.previousIndex] = true;
    }
  }

  return matchedPreviousIndex;
}

/**
 * Extrapolates `selected`'s box center along the velocity implied by `previous` → `selected` over
 * `deltaMs`, `extrapolateMs` further ahead (already capped by the caller) — ported from
 * `DetectionExtrapolator#extrapolate`. `selected`'s width/height and every other field (label,
 * confidence, model, track) are kept as-is; only the box's `x`/`y` move, each independently clamped
 * back into `[0,1]` after subtracting half the box's own width/height back off the projected center
 * (mirrors the server's exact clamp — the box's *origin*, not its center, is what gets clamped, so a
 * box already touching an edge can still project its far edge past `[0,1]`, byte-identical to the
 * server's own behavior).
 *
 * **Renamed, wave W3.2** (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6): only called for a
 * detection {@link resolveDisplayDetections} has already classified as *unmatched* — no track id, or a
 * track id with no {@link WorldObject} yet — never for an object the wire already has a `kinematics`
 * fact for.
 */
function projectUnmatchedOne(selected: Detection, previous: Detection, deltaMs: number, extrapolateMs: number): Detection {
  const selectedCenter = boxCenter(selected.box);
  const previousCenter = boxCenter(previous.box);
  const velocityXPerMs = (selectedCenter.x - previousCenter.x) / deltaMs;
  const velocityYPerMs = (selectedCenter.y - previousCenter.y) / deltaMs;

  const newCenterX = selectedCenter.x + velocityXPerMs * extrapolateMs;
  const newCenterY = selectedCenter.y + velocityYPerMs * extrapolateMs;

  return {
    ...selected,
    box: {
      x: clampUnit(newCenterX - selected.box.width / 2),
      y: clampUnit(newCenterY - selected.box.height / 2),
      width: selected.box.width,
      height: selected.box.height,
    },
  };
}

/**
 * Forward-projects `selected`'s detections to `targetMs` (typically {@link estimatedOnScreenAtMs}'s
 * own output) using the velocity implied by `previous` → `selected`, mirroring the deleted server-side
 * `DetectionExtrapolator.at(Instant)` (see this section's own header comment). Matched detections
 * (see {@link matchDetections}) get a new, velocity-projected box center; unmatched ones — including
 * every detection in `selected` when there is nothing to match against — pass through completely
 * unchanged, same object reference included.
 *
 * Returns `selected.detections` verbatim (raw, no projection) when:
 *  - `previous` is `undefined` — nothing to derive a velocity from;
 *  - `previous.capturedAt` is not strictly before `selected.capturedAt` — a degenerate or duplicate
 *    timestamp pair has no meaningful velocity (mirrors `DetectionExtrapolator`'s own `deltaSeconds
 *    <= 0` guard);
 *  - `targetMs` is at or before `selected`'s own capture time — there is nothing to project *forward*
 *    to yet.
 *
 * Otherwise, the projection horizon is capped at `maxExtrapolationMs` past `selected.capturedAt` — a
 * `targetMs` further out than that **freezes** at exactly the capped projection rather than running
 * boxes off into the distance forever (a stalled/outaged detector holds its last known trajectory, it
 * doesn't keep inventing motion).
 *
 * **Scope narrowed, wave W3.2** (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6): still a generic,
 * independently-testable function (every test below calls it directly), but its one production call
 * site, {@link resolveDisplayDetections}, now feeds it only the *unmatched* subset of a batch — a
 * detection with no track id, or a track id {@link WorldObject} hasn't reported yet. A matched,
 * visible object reads its motion straight off the wire (`kinematics.predictedBox`/`box`) instead.
 */
export function extrapolateDetections(
  selected: DetectionResult,
  previous: DetectionResult | undefined,
  targetMs: number,
  maxExtrapolationMs: number = EXTRAPOLATION_MAX_MS,
  matchGate: number = UNMATCHED_MATCH_GATE_DISTANCE,
): readonly Detection[] {
  const selectedDetections = selected.detections;
  if (previous === undefined) {
    return selectedDetections;
  }

  const selectedCapturedAtMs = Date.parse(selected.capturedAt);
  const previousCapturedAtMs = Date.parse(previous.capturedAt);
  if (previousCapturedAtMs >= selectedCapturedAtMs) {
    return selectedDetections;
  }
  if (targetMs <= selectedCapturedAtMs) {
    return selectedDetections;
  }

  const deltaMs = selectedCapturedAtMs - previousCapturedAtMs;
  const cappedTargetMs = Math.min(targetMs, selectedCapturedAtMs + maxExtrapolationMs);
  const extrapolateMs = cappedTargetMs - selectedCapturedAtMs;

  const previousDetections = previous.detections;
  const matchedPreviousIndex = matchDetections(selectedDetections, previousDetections, matchGate);

  return selectedDetections.map((detection, index) => {
    const previousIndex = matchedPreviousIndex[index];
    return previousIndex < 0
      ? detection
      : projectUnmatchedOne(detection, previousDetections[previousIndex], deltaMs, extrapolateMs);
  });
}

/**
 * The newest batch in `results` (newest-first, {@link selectDetectionResult}'s own contract) strictly
 * older than `selected` — {@link extrapolateDetections}'s own `previous` argument, resolved by
 * `shared/player/player.ts#redrawOverlay` right after `selectDetectionResult` picks `selected`.
 * `selected` is expected to be one of `results`' own elements (whatever `selectDetectionResult`
 * returned) — the search starts right after its position, and `selected` being absent from `results`
 * altogether (found via reference equality) is itself treated as "no predecessor" rather than matching
 * some unrelated entry by timestamp alone. Skips over any duplicate-timestamp neighbor (`capturedAt`
 * not *strictly* older) rather than matching it, since {@link extrapolateDetections} already treats a
 * non-positive delta as degenerate and would fall back to raw regardless — better to look one batch
 * further back for an actual velocity than settle for a `previous` that can only ever produce "no
 * projection".
 *
 * @returns `undefined` when `selected` is the oldest entry present, or absent from `results`
 *          altogether — both of which {@link extrapolateDetections} already treats as "no predecessor,
 *          draw raw"
 */
export function findPredecessorResult(
  results: readonly DetectionResult[],
  selected: DetectionResult,
): DetectionResult | undefined {
  const index = results.indexOf(selected);
  if (index === -1) {
    return undefined;
  }
  const selectedCapturedAtMs = Date.parse(selected.capturedAt);
  for (let i = index + 1; i < results.length; i++) {
    if (Date.parse(results[i].capturedAt) < selectedCapturedAtMs) {
      return results[i];
    }
  }
  return undefined;
}

// --- Render tier / label / motion from the wire (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6,
// wave W3.2) ----------------------------------------------------------------------------------------
// `WorldModel` (vision-perception, server-side) now owns render tier, elected label, and tracked-
// object motion for anything it has already folded a wire `WorldObject` for (the `tracks:<assetId>`
// live topic, `core/detections/detections-store.ts#worldObjects`) — this is where the client stops
// re-deriving those three facts for a *matched* detection and reads them off the wire instead. The
// machinery above (`matchDetections`/{@link projectUnmatchedOne}/{@link extrapolateDetections}) keeps
// earning its keep for the *unmatched* remainder: an untracked detection, or a tracked one whose world
// object hasn't arrived yet — the two feeds are not frame-locked
// (`core/detections/detections-store.ts#worldObjects`'s own doc comment) — still has the same HLS/WHEP
// glass-to-glass staleness problem this file has always solved locally.

/**
 * `worldObjects` keyed by `state.id` (the track id — the correlation key against `Detection.track?.id`,
 * both numeric ids from the same cv-service tracker) — the one lookup both
 * {@link resolveDisplayDetections}/{@link resolveDetectionTiers} and
 * `detections-strip-logic.ts#stripChips` build against, so a detection and a world object are never
 * matched by two independently-written key functions.
 */
export function worldObjectsByTrackId(worldObjects: readonly WorldObject[]): ReadonlyMap<number, WorldObject> {
  const byTrackId = new Map<number, WorldObject>();
  for (const worldObject of worldObjects) {
    byTrackId.set(worldObject.state.id, worldObject);
  }
  return byTrackId;
}

/**
 * The one call `shared/player/player.ts#drawDetections` makes to decide what to draw — replacing the
 * old three-function trio this file used to run for every batch: raw extrapolation, plus a client-side
 * label-election stopgap and its own apply step (both retired, see this section's own header comment).
 * Every detection in `selected.detections` falls into exactly one of three buckets:
 *
 *  - **Matched, visible** — `detection.track.id` maps (via `worldObjectsById`) to a {@link WorldObject}
 *    whose `render.tier !== 'HIDDEN'`. Its box becomes the wire's own `kinematics.predictedBox` — a
 *    server-frame-cadence coast to *this frame's instant*, **not** the same latency problem
 *    {@link estimatedOnScreenAtMs}/this file's own HLS glass-to-glass math solves; using it anyway is a
 *    deliberate, disclosed simplification, not an equivalent replacement — falling back to
 *    `kinematics.box`, then the detection's own raw `box`. Its label becomes `identity.label` when
 *    elected, else the detector's own raw label.
 *  - **Matched, hidden** — maps to a `WorldObject` with `render.tier === 'HIDDEN'` (deny-filtered or
 *    denied). Dropped from the returned list **entirely**, not merely left untiered — see
 *    {@link resolveDetectionTiers}'s own doc comment for why "absent from the tier map" alone would not
 *    be a safe way to hide a box on this codebase's own `?? 'T2'` fallback at the draw call site.
 *  - **Unmatched** — no track id, or a track id with no world object yet, including every detection on
 *    a host that never binds `worldObjects` at all (e.g. `features/command/asset-panel.html`, which
 *    thereby degrades to exactly this file's pre-W3.2 behavior). Runs through the untouched
 *    `matchDetections`/{@link extrapolateDetections} pipeline, scoped to just this subset — pass 1's
 *    exact track-id match still matters here: it is what stops a same-label crossing-object swap during
 *    the transient gap before a genuinely tracked object's first `tracks:` arrival.
 *
 * Reassembled in `selected.detections`' own original order. **One disclosed behavior change**: a
 * genuinely-tracked detection with no matching world object yet no longer gets the historical
 * multi-batch label smoothing the retired client-side election used to provide for every tracked
 * detection — it shows its raw per-batch label until its first `tracks:` arrival, typically converging
 * within a frame or two.
 */
export function resolveDisplayDetections(
  selected: DetectionResult,
  previous: DetectionResult | undefined,
  targetMs: number,
  worldObjectsById: ReadonlyMap<number, WorldObject>,
  maxExtrapolationMs: number = EXTRAPOLATION_MAX_MS,
  matchGate: number = UNMATCHED_MATCH_GATE_DISTANCE,
): readonly Detection[] {
  const selectedDetections = selected.detections;
  const unmatched: Detection[] = [];
  const unmatchedOriginalIndex: number[] = [];
  const resolved: (Detection | undefined)[] = new Array(selectedDetections.length);

  selectedDetections.forEach((detection, index) => {
    const trackId = detection.track?.id;
    const worldObject = trackId !== undefined ? worldObjectsById.get(trackId) : undefined;
    if (worldObject === undefined) {
      unmatchedOriginalIndex.push(index);
      unmatched.push(detection);
      return;
    }
    if (worldObject.render.tier === 'HIDDEN') {
      return; // suppressed — `resolved[index]` stays undefined and is dropped by the filter below
    }
    resolved[index] = {
      ...detection,
      label: worldObject.state.identity?.label ?? detection.label,
      box: worldObject.state.kinematics?.predictedBox ?? worldObject.state.kinematics?.box ?? detection.box,
    };
  });

  const projectedUnmatched = extrapolateDetections(
    { ...selected, detections: unmatched },
    previous,
    targetMs,
    maxExtrapolationMs,
    matchGate,
  );
  projectedUnmatched.forEach((detection, i) => {
    resolved[unmatchedOriginalIndex[i]] = detection;
  });

  return resolved.filter((detection): detection is Detection => detection !== undefined);
}

/**
 * Whether the canvas overlay should actually draw *anything* right now — `'off'` suppresses it
 * entirely, every other declutter level draws once a result is available (which tiers, specifically,
 * is {@link tiersForDeclutterLevel}'s job, not this function's — this is only the top-level "is the
 * canvas live at all" gate, unchanged in shape since W3). The video itself is always clean pixels now
 * (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-1: server-side burn-in is deleted, not defaulted off), so
 * `'off'` means genuinely no boxes anywhere — and, per `Player#overlayInteractive`'s own use of this
 * function, no hover/click either, not merely "hide the client canvas over the server's own baked-in
 * ones" the way it used to.
 */
export function shouldDrawOverlay(mode: BoxesMode, hasResult: boolean): boolean {
  return mode !== 'off' && hasResult;
}

/**
 * Which draw tiers a given declutter level actually renders (research §3.6) — the one place this
 * rule lives; `Player#redrawOverlay` filters every detection through this before drawing, and the
 * label-candidate pass reuses it rather than re-deriving which tiers are even on screen. `'off'`
 * returns an empty set — `shouldDrawOverlay` already short-circuits the whole canvas before this is
 * ever consulted for that mode, but an empty set is the semantically correct answer regardless.
 */
export function tiersForDeclutterLevel(mode: BoxesMode): ReadonlySet<DetectionTier> {
  switch (mode) {
    case 'all':
      return new Set<DetectionTier>(['T0', 'T1', 'T2', 'T3']);
    case 'priority':
      return new Set<DetectionTier>(['T0', 'T1', 'T3']);
    case 'locked':
      return new Set<DetectionTier>(['T0']);
    case 'off':
      return new Set<DetectionTier>();
  }
}

// --- Staleness honesty (docs/plans/done/CV-FLY-INTERACTION-RESEARCH.md §3.4, D7) -----------------------
// The renderer already knows a batch's age (`capturedAt` vs `now`) — this turns that age into an
// honest signal instead of drawing a stale batch at full confidence forever. Two thresholds, both
// scaled off the stream's own observed cadence rather than one flat guess:
//  - past `STALE_FADE_BATCH_MULTIPLIER` observed batch-intervals, a batch still draws, but dimmed —
//    "probably fine, trust it a little less";
//  - past `DETECTION_STALE_CUTOFF_SECONDS`, a batch doesn't draw at all and the stage says so instead
//    (`detectionsPausedNotice`) — the same freshness window `DetectionsStore`'s own status dot already
//    uses (`core/detections/detections-logic.ts#CV_STATUS_FRESH_SECONDS`, imported rather than
//    redeclared) so the canvas, the status dot, and this notice can never quietly disagree about what
//    "too old to call live" means.

/** A batch older than this many observed batch-intervals still draws, but at
 *  {@link STALE_FADE_ALPHA_PERCENT} instead of full alpha. */
export const STALE_FADE_BATCH_MULTIPLIER = 2;

/** Alpha percent applied once a batch crosses the fade threshold — dimmed, not gone. */
export const STALE_FADE_ALPHA_PERCENT = 40;

/** Detections older than this hard cutoff stop drawing entirely — the exact window `DetectionsStore`
 *  already prunes its own results to (`CV_STATUS_FRESH_SECONDS`), imported so the two can never drift
 *  apart and disagree. */
export const DETECTION_STALE_CUTOFF_SECONDS = CV_STATUS_FRESH_SECONDS;

/**
 * The alpha percent a batch this old should draw at, scaled off the stream's own observed batch
 * cadence ({@link averageBatchIntervalMs}) — 100% while fresh, {@link STALE_FADE_ALPHA_PERCENT} once
 * older than {@link STALE_FADE_BATCH_MULTIPLIER} batch-intervals. A `batchIntervalMs` of `0` (a
 * single-batch history — nothing to measure a cadence from yet) never fades; there's no baseline to
 * call this batch old *relative to*. Not called once a batch has crossed
 * {@link DETECTION_STALE_CUTOFF_SECONDS} — {@link isDetectionStale} gates that first, and the caller
 * stops drawing entirely rather than fading to invisible.
 */
export function detectionAlphaPercent(ageMs: number, batchIntervalMs: number): number {
  if (batchIntervalMs <= 0) {
    return 100;
  }
  return ageMs > batchIntervalMs * STALE_FADE_BATCH_MULTIPLIER ? STALE_FADE_ALPHA_PERCENT : 100;
}

/** Whether a batch this old (milliseconds) has crossed the hard cutoff and should stop drawing
 *  entirely, handing off to {@link detectionsPausedNotice}. */
export function isDetectionStale(ageMs: number): boolean {
  return ageMs > DETECTION_STALE_CUTOFF_SECONDS * 1000;
}

/**
 * "Detections paused — last seen Ns ago" (research §3.4) once the feed has gone stale — mirrors
 * `stream-state-logic.ts#videoNotice`'s own shape/honesty rule for the sibling *video* axis, applied
 * here to the *detection* feed instead. `null` means "say nothing", covering both "detections are
 * fresh" and "nothing has ever arrived to report a pause from" (no honest age to show).
 *
 * `latestCapturedAt` must be the **unfiltered** last-seen timestamp (e.g. `DetectionsStore`'s own raw
 * latest result, not the freshness-pruned `results()` list) — once a batch ages past
 * {@link DETECTION_STALE_CUTOFF_SECONDS} it is no longer present in a freshness-filtered list at all,
 * which is exactly the moment this notice needs to start reporting how long ago it was.
 */
export function detectionsPausedNotice(latestCapturedAt: string | undefined, nowMs: number): string | null {
  if (latestCapturedAt === undefined) {
    return null;
  }
  const ageMs = Math.max(0, nowMs - Date.parse(latestCapturedAt));
  if (!isDetectionStale(ageMs)) {
    return null;
  }
  return `Detections paused — last seen ${Math.round(ageMs / 1000)}s ago`;
}

// --- Composite-model box hues (docs/plans/done/OPS-CORE-PLAN.md §Q3b) --------------------------------------

/**
 * The key `modelHue` colors a box by — cv-service's composite mode (docs/plans/done/CV-MODELS-PLAN.md item
 * 4, `registry.py#detect_composite`) prefixes every `label` `"{short_name}:{label}"` (e.g.
 * `"orion12l:tank"`) only when more than one member model actually ran, so this is the *one*
 * signal that distinguishes which member produced a given box.
 *
 * Deliberately **not** `Detection.modelId`: in composite mode every detection in the same batch
 * carries the identical `modelId` (the request's own comma-joined composite id, e.g.
 * `"yolo11n.pt,orion12l.pt"`) — proto's `Detection` message has no per-detection model-tag field,
 * so `modelId` can't distinguish members within one batch even though it exists on the wire. A
 * plain single-model stream never prefixes its labels at all (`tag_labels` is only true for >1
 * resolved member — see that function's own doc comment), so `DEFAULT_MODEL_KEY` is what every
 * detection from *any* single model — not just cv-service's own default — resolves to.
 */
export const DEFAULT_MODEL_KEY = 'default';

export function detectionModelKey(detection: Pick<Detection, 'label'>): string {
  const separatorIndex = detection.label.indexOf(':');
  return separatorIndex > 0 ? detection.label.slice(0, separatorIndex) : DEFAULT_MODEL_KEY;
}

/** Fixed to sit in the same vivid/legible band as `--accent` (`#4f8cff` ≈ `hsl(219 100% 65%)`) so a
 *  hashed model color never reads as washed-out or too dark against the video underneath. */
const MODEL_HUE_SATURATION = 90;
const MODEL_HUE_LIGHTNESS = 65;

/** The exact color every box has always been drawn in — see `modelHue`'s own doc comment. */
export const DEFAULT_BOX_COLOR = '#4f8cff';
/** `DEFAULT_BOX_COLOR` at 85% alpha, byte-identical to the label background this file always drew. */
const DEFAULT_BOX_FILL = 'rgb(79 140 255 / 85%)';

/** Simple deterministic string hash (djb2-ish) → a hue in `[0, 360)`, no external dependency. */
function hashHue(key: string): number {
  let hash = 0;
  for (let i = 0; i < key.length; i++) {
    hash = (hash * 31 + key.charCodeAt(i)) >>> 0;
  }
  return hash % 360;
}

/**
 * Stable per-model box color (docs/plans/done/OPS-CORE-PLAN.md §Q3b): `DEFAULT_MODEL_KEY` always resolves to
 * the exact color every box has always been drawn in — a single-model stream (the overwhelming
 * common case, whichever model it happens to be) sees zero visual change. Any other key hashes
 * deterministically into a hue at a fixed saturation/lightness, so the same model always draws the
 * same color across frames, reconnects, and page loads with no stateful color registry to keep in
 * sync. `alphaPercent` (default fully opaque, for the box stroke) also covers the semi-transparent
 * label background `shared/player/player.ts#drawBox` paints behind each box's text.
 */
export function modelHue(modelKey: string, alphaPercent = 100): string {
  if (modelKey === DEFAULT_MODEL_KEY) {
    return alphaPercent >= 100 ? DEFAULT_BOX_COLOR : DEFAULT_BOX_FILL;
  }
  const hue = hashHue(modelKey);
  return alphaPercent >= 100
    ? `hsl(${hue} ${MODEL_HUE_SATURATION}% ${MODEL_HUE_LIGHTNESS}%)`
    : `hsl(${hue} ${MODEL_HUE_SATURATION}% ${MODEL_HUE_LIGHTNESS}% / ${alphaPercent}%)`;
}

/**
 * Every distinct model key present in `detections`, in first-seen order — `shared/player/player.ts`'s
 * legend gate shows a chip only when this has ≥2 entries: "the frame actually on screen mixes
 * models right now", not merely "composite mode is configured" (a frame where only one member
 * model detected anything still reads, correctly, as single-model).
 */
export function distinctModelKeys(detections: readonly Pick<Detection, 'label'>[]): readonly string[] {
  const seen = new Set<string>();
  for (const detection of detections) {
    seen.add(detectionModelKey(detection));
  }
  return [...seen];
}

// --- Track-aware rendering (docs/plans/done/TRACKING-PLAN.md §4/§10, wave T7) ------------------------------
// Everything below gates on `detection.track` alone — one null check (per §4.G's own "the client
// gets a single null check gating all track rendering" design) — so a stream running an old/absent
// server, or a detection tracking hasn't (yet) assigned an id to, draws byte-identically to before
// this wave: `shared/player/player.ts#drawBox` only reaches these functions once `detection.track`
// is present.

/**
 * The box label `shared/player/player.ts#drawBox` draws — `"#7 car 82%"` once `detection.track` is
 * present (docs/plans/done/TRACKING-PLAN.md §10 touchable outcome #1's "stable box numbers"), the exact,
 * unchanged `"car 82%"` otherwise. Also used for the hover tooltip's own text, so the two always
 * agree on whether a box is carrying an id.
 */
export function formatDetectionLabel(detection: Detection): string {
  const confidence = `${(detection.confidence * 100).toFixed(0)}%`;
  return detection.track
    ? `#${detection.track.id} ${detection.label} ${confidence}`
    : `${detection.label} ${confidence}`;
}

/**
 * **`trackHue` (per-track hash hue) is gone as of this wave** (docs/plans/active/
 * CV-FLY-INTERACTION-RESEARCH.md §3.2/D5, wave W4): with 15 tracked objects on screen, 15 saturated
 * hash-derived colors read as confetti, encoding nothing a viewer could actually use — identity was
 * already carried by the `#id` text and box constancy, never the color. Color now carries *tier* and
 * *class* instead — see {@link tierBoxColor}/{@link classBucketHue} below for the replacement, and
 * `shared/player/player.ts#drawBox` for how a box's color is actually chosen per tier.
 */

/** One point of a per-track trail — a tracked box's normalized center, `[0,1]` against frame dimensions. */
export interface TrailPoint {
  readonly x: number;
  readonly y: number;
}

/** How far back a trail reaches (docs/plans/done/TRACKING-PLAN.md §10 touchable outcome #3 — "a trail behind a
 *  tracked car", not a full trajectory history; S2's map trails are the durable, longer-lived kind). */
export const TRAIL_WINDOW_MS = 2_000;

/**
 * Per-track fading trails (docs/plans/done/TRACKING-PLAN.md §10 touchable outcome #3), recomputed fresh on
 * every call from `results` — `DetectionsStore.results()`, which already retains a short
 * newest-first history (up to 50 batches, `DetectionsStore#DETECTIONS_LIMIT`). **Not a mutable
 * accumulator**: there is nothing stateful in this module for `results` to be cleared *from* — a
 * stream switch already empties `DetectionsStore.results()` to `[]` (`DetectionsStore#track`'s own
 * doc comment), so the very next call here naturally returns an empty map, which is what "cleared on
 * stream change" means in a pure-function world.
 *
 * Points for one track come out **oldest-first** (draw order for a fading polyline); a track with
 * only one point in the window (a track that just appeared) still gets an entry — the caller decides
 * whether a single-point trail is worth stroking.
 */
export function trackTrails(
  results: readonly DetectionResult[],
  nowMs: number,
  windowMs: number = TRAIL_WINDOW_MS,
): ReadonlyMap<number, readonly TrailPoint[]> {
  const cutoffMs = nowMs - windowMs;
  const byTrack = new Map<number, TrailPoint[]>();
  // `results` is newest-first (VisionApi.streamDetections's own contract); walk it back-to-front so
  // each track's own point list comes out oldest-first without a second reverse pass.
  for (let i = results.length - 1; i >= 0; i--) {
    const result = results[i];
    const capturedAtMs = Date.parse(result.capturedAt);
    if (!Number.isFinite(capturedAtMs) || capturedAtMs < cutoffMs) {
      continue;
    }
    for (const detection of result.detections) {
      const trackId = detection.track?.id;
      if (trackId === undefined) {
        continue;
      }
      const point: TrailPoint = {
        x: detection.box.x + detection.box.width / 2,
        y: detection.box.y + detection.box.height / 2,
      };
      const points = byTrack.get(trackId);
      if (points) {
        points.push(point);
      } else {
        byTrack.set(trackId, [point]);
      }
    }
  }
  return byTrack;
}

// --- Sticky labels per track: retired, wave W3.2 (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6) ---
// This file's own client-side label-election stopgap (see TRACK-IDENTITY-PLAN.md §L3 item 1, and
// `git log` on this file for its prior shape) is deleted outright — label election is now a
// server-side WorldModel/ObjectState fact (`state.identity.label`, wire-elected), consumed via
// {@link resolveDisplayDetections} above. See that function's own doc comment for the one disclosed
// behavior change this causes for a detection with no world object yet.

// --- HiDPI canvas backing store (docs/plans/done/MEDIA-SOT-PLAN.md §8 wave M8) -----------------------------
// The overlay canvas used to size its backing store 1:1 with its CSS box (`canvas.width =
// video.clientWidth`), so every box/trail/label drew at 1 device pixel per CSS pixel — soft/blurry on
// any HiDPI display, quietly undercutting the reason the client overlay exists at all ("crisp boxes
// at any bitrate", `shared/player/player.ts`'s own class doc). The fix is backing-store-only: the CSS
// box size (and therefore `letterboxRect`'s own math, and hit-testing off
// `canvas.getBoundingClientRect()`) never changes, only how many physical pixels each CSS pixel maps
// to — `shared/player/player.ts#redrawOverlay` applies this via `ctx.setTransform(devicePixelRatio,
// ...)` right after resizing, so every existing draw call keeps working in CSS-pixel coordinates
// unmodified.

/** A canvas's backing-store size (device pixels) for a given CSS box size and `devicePixelRatio` —
 *  `Math.round` because `canvas.width`/`height` are integers and a fractional DPR (e.g. Windows'
 *  125% → `1.25`) would otherwise silently truncate instead of rounding to the nearest pixel. */
export function canvasBackingSize(
  cssWidth: number,
  cssHeight: number,
  devicePixelRatio: number,
): { readonly width: number; readonly height: number } {
  const ratio = devicePixelRatio > 0 ? devicePixelRatio : 1;
  return {
    width: Math.round(cssWidth * ratio),
    height: Math.round(cssHeight * ratio),
  };
}

// --- Priority tiers (docs/plans/done/CV-FLY-INTERACTION-RESEARCH.md §3.2, wave W4) --------------------------
// Every detection gets a computed draw tier, a pure function of what the client already knows (lock
// state, hover, track/trail history, box geometry) — fixes D2 (always-on labels), D3 (no priority
// model — a locked target looks like a tree), D5 (per-track confetti hues), D8 (trails scale with
// track count). Recomputed fresh every redraw, same "no stateful accumulator" posture as
// `trackTrails` above.

export type DetectionTier = 'T0' | 'T1' | 'T2' | 'T3';

/**
 * How many non-committed detections {@link detectionTiers} promotes to T1 purely by (box area ×
 * confidence) — research §3.2's own "top-K by (area × confidence) among the remainder". The
 * FOLLOW-locked track and the hovered box (T0) are excluded before this budget is spent, and a
 * detection already promoted by movement ({@link MOVING_DISPLACEMENT_THRESHOLD}) doesn't consume a
 * second slot — the two promotion routes are an *or*, not two independent budgets. `5`, named rather
 * than left as a literal, matches the "~5" the research doc itself proposes.
 */
export const NOTABLE_TOP_K = 5;

/**
 * Minimum normalized (0-1, against frame width/height) displacement a tracked object's trail must
 * cover across {@link TRAIL_WINDOW_MS} to count as "moving" for T1 promotion (research §3.2), rather
 * than sensor/detector jitter on a parked object. Deliberately low: a false promotion costs little
 * (one more full-weight box in a tier that already draws up to {@link NOTABLE_TOP_K} boxes besides),
 * while a genuinely stationary tracked car (the research doc's own "12 parked cars" example)
 * flickering between T1 and T2 across a tight threshold would be worse than an occasional early
 * promotion.
 */
export const MOVING_DISPLACEMENT_THRESHOLD = 0.02;

/**
 * A box smaller than this on *both* axes (CSS px, at the canvas's current letterboxed video size)
 * draws as a T3 dot instead of a box+label — research §3.2's own "a label would be bigger than the
 * object". This check runs *before* movement/top-K promotion and wins regardless: a moving or
 * high-score detection this small still degrades to a dot ({@link detectionTiers} never spends the
 * {@link NOTABLE_TOP_K} budget on a detection that will render as a dot anyway). The FOLLOW-locked
 * track and the hovered box (T0) are the only tiers exempt — checked first, with no size caveat of
 * their own in the research table's T0 row: the one thing the operator explicitly chose to look at is
 * never shrunk away.
 */
export const SUB_SCALE_PX = 12;

/** Inputs {@link detectionTiers} needs beyond the detection list itself — everything the client
 *  already knows without a new poll (research §3.2's own framing: "pure function of what the client
 *  already knows"). */
export interface DetectionTierContext {
  /** `0` = no lock held — the exact wire sentinel `StreamTracksResponse#lockedTrackId` already uses
   *  (`core/api/models.ts`), passed straight through rather than translated to `undefined`. */
  readonly lockedTrackId: number;
  /** Reference-identity match against one entry of the same `detections` array — mirrors
   *  `shared/player/player.ts#drawBox`'s own pre-existing `hoveredDetection === detection` check. */
  readonly hoveredDetection: Detection | null;
  /** {@link trackTrails}'s own output, already computed once per redraw for the trail layer — reused
   *  here rather than recomputed, so one redraw never runs the trail scan twice. */
  readonly trails: ReadonlyMap<number, readonly TrailPoint[]>;
  /** The letterboxed video rect's CSS-pixel size ({@link SUB_SCALE_PX}'s own comparison unit) —
   *  `redrawOverlay`'s own `content.width`/`content.height`. */
  readonly contentWidthPx: number;
  readonly contentHeightPx: number;
  /**
   * The label currently hovered on the detections strip's remote-control chips
   * (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-3, wave W5, research §3.5) — `null` when nothing is
   * hovered. An exact `detection.label` match promotes into T1 the same way {@link isMovingTrack}
   * does: unconditional, budget-exempt, so hovering "person" never gets crowded out by
   * {@link NOTABLE_TOP_K} already being spent elsewhere. Deliberately does *not* reach T3 (sub-scale
   * dots stay dots — a hover can't make an object bigger) or override T0 (the FOLLOW lock and the
   * single hovered *box* still win).
   */
  readonly hoveredClass: string | null;
}

function isMovingTrack(detection: Detection, trails: ReadonlyMap<number, readonly TrailPoint[]>): boolean {
  const trackId = detection.track?.id;
  if (trackId === undefined) {
    return false;
  }
  const points = trails.get(trackId);
  if (!points || points.length < 2) {
    return false;
  }
  const first = points[0];
  const last = points[points.length - 1];
  return Math.hypot(last.x - first.x, last.y - first.y) >= MOVING_DISPLACEMENT_THRESHOLD;
}

function isSubScale(detection: Detection, context: DetectionTierContext): boolean {
  const widthPx = detection.box.width * context.contentWidthPx;
  const heightPx = detection.box.height * context.contentHeightPx;
  return widthPx < SUB_SCALE_PX && heightPx < SUB_SCALE_PX;
}

/**
 * Assigns every detection in one batch a draw tier (research §3.2) — priority order, matching the
 * research table read top-to-bottom:
 *  1. **T0** — the hovered box, or the box carrying the FOLLOW-locked track id. Checked first and
 *     unconditionally (no size/movement test): the one thing the operator is actively pointing at or
 *     has committed to never demotes.
 *  2. **T3** — everything else under {@link SUB_SCALE_PX} on both axes. Checked before T1 so the
 *     {@link NOTABLE_TOP_K} budget is never spent on a detection that will render as a dot regardless.
 *  3. **T1** — tracked-and-moving ({@link isMovingTrack}), matches {@link DetectionTierContext.hoveredClass}
 *     (wave W5's class-hover promotion), or in the top {@link NOTABLE_TOP_K} of the size-eligible
 *     remainder by (box area × confidence).
 *  4. **T2** — everything left.
 * Alert-rule promotion (the research table's third T1 criterion) is deliberately absent: there is no
 * `alerting` field on the wire yet (research §8.3 lists it as an open backend candidate) — nothing
 * here fabricates one client-side.
 */
export function detectionTiers(
  detections: readonly Detection[],
  context: DetectionTierContext,
): ReadonlyMap<Detection, DetectionTier> {
  const tiers = new Map<Detection, DetectionTier>();
  const remainder: Detection[] = [];

  for (const detection of detections) {
    const isLocked = context.lockedTrackId !== 0 && detection.track?.id === context.lockedTrackId;
    if (detection === context.hoveredDetection || isLocked) {
      tiers.set(detection, 'T0');
    } else {
      remainder.push(detection);
    }
  }

  const subScale: Detection[] = [];
  const sizeEligible: Detection[] = [];
  for (const detection of remainder) {
    (isSubScale(detection, context) ? subScale : sizeEligible).push(detection);
  }
  for (const detection of subScale) {
    tiers.set(detection, 'T3');
  }

  const notable = new Set<Detection>();
  for (const detection of sizeEligible) {
    if (isMovingTrack(detection, context.trails) || detection.label === context.hoveredClass) {
      notable.add(detection);
    }
  }
  const ranked = sizeEligible
    .filter((detection) => !notable.has(detection))
    .map((detection) => ({ detection, score: detection.box.width * detection.box.height * detection.confidence }))
    .sort((a, b) => b.score - a.score);
  for (const { detection } of ranked) {
    if (notable.size >= NOTABLE_TOP_K) {
      break;
    }
    notable.add(detection);
  }

  for (const detection of sizeEligible) {
    tiers.set(detection, notable.has(detection) ? 'T1' : 'T2');
  }

  return tiers;
}

/**
 * The one call `shared/player/player.ts#drawDetections` makes to decide each detection's draw tier
 * (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6, wave W3.2) — replacing the direct
 * {@link detectionTiers} call for whichever of `displayDetections` {@link resolveDisplayDetections}
 * already matched to a non-`HIDDEN` {@link WorldObject}. A `HIDDEN` one never reaches here at all —
 * `resolveDisplayDetections` already dropped it from `displayDetections` — so this function's returned
 * map is guaranteed to carry an entry for every detection it is given, exactly like
 * {@link detectionTiers} on its own always has: `shared/player/player.ts`'s several
 * `tiers.get(...) ?? 'T2'` fallbacks stay genuinely dead code, never load-bearing.
 *
 * A matched detection's tier is the server's own `render.tier` (`'T0'`-`'T3'` are byte-identical to
 * {@link DetectionTier} by that wire type's own doc comment — a safe cast, `'HIDDEN'` never reaches
 * here), unless `context.hoveredDetection` is this exact detection, which promotes it to `'T0'`
 * regardless: hover is client-only UI state `WorldModel` explicitly does not compute
 * (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6's own `render` row), so it is the one legitimate
 * client-side override left over server tier assignment. `lockedTrackId`-based T0 needs no such
 * override — a FOLLOW-locked object's `render.tier` is already `'T0'` server-side.
 *
 * Every unmatched detection (see {@link resolveDisplayDetections}'s own bucket doc) is delegated to
 * {@link detectionTiers}, completely unchanged, scoped to just that subset — `context` (lock,
 * trails, hover, content size, hovered class) is passed straight through.
 */
export function resolveDetectionTiers(
  displayDetections: readonly Detection[],
  worldObjectsById: ReadonlyMap<number, WorldObject>,
  context: DetectionTierContext,
): ReadonlyMap<Detection, DetectionTier> {
  const tiers = new Map<Detection, DetectionTier>();
  const unmatched: Detection[] = [];

  for (const detection of displayDetections) {
    const trackId = detection.track?.id;
    const worldObject = trackId !== undefined ? worldObjectsById.get(trackId) : undefined;
    if (worldObject === undefined) {
      unmatched.push(detection);
      continue;
    }
    tiers.set(detection, detection === context.hoveredDetection ? 'T0' : (worldObject.render.tier as DetectionTier));
  }

  if (unmatched.length > 0) {
    for (const [detection, tier] of detectionTiers(unmatched, context)) {
      tiers.set(detection, tier);
    }
  }

  return tiers;
}

// --- Tiered rendering weights (docs/plans/done/CV-FLY-INTERACTION-RESEARCH.md §3.2, wave W4) -----------------

/** T2's own base alpha — "1px stroke at ~55% alpha, no label" (research §3.2's own T2 row). */
export const T2_ALPHA_PERCENT = 55;

/**
 * T1/T2/T3 additionally dim to this alpha (multiplicatively against their own base) once a FOLLOW
 * lock is active — "lock-dims-rest" (research §3.2), the adaptive-declutter rule every avionics/DJI
 * analog in §4 uses: committing to a target *is* the escalation. T0 is exempt (it usually *is* the
 * lock, and the hovered box should never dim either way). Releasing the lock is not a separate code
 * path — {@link tierAlphaPercent} is a pure function of the current lock state, re-evaluated every
 * redraw, so the very next frame after a release already renders at full presence.
 */
export const LOCK_DIM_ALPHA_PERCENT = 40;

/** The alpha percent a detection in `tier` should draw at, before staleness fade — combine
 *  multiplicatively with {@link detectionAlphaPercent} at the call site (`shared/player/player.ts`),
 *  never additively (two independent dimming reasons compound, they don't override each other). */
export function tierAlphaPercent(tier: DetectionTier, lockActive: boolean): number {
  const base = tier === 'T2' ? T2_ALPHA_PERCENT : 100;
  if (tier === 'T0' || !lockActive) {
    return base;
  }
  return Math.round((base * LOCK_DIM_ALPHA_PERCENT) / 100);
}

/** T3's own dot marker radius (CSS px) — small enough to read as "a marker, not a box" beside a real
 *  box at the same scale. */
export const SUB_SCALE_DOT_RADIUS_PX = 3;

/** Stroke widths per tier — `shared/player/player.ts#drawBox`'s own `lineWidth` switch, named rather
 *  than bare literals scattered across that file. T2's is also its own "thin box" per research §3.2. */
export const T0_STROKE_WIDTH_PX = 3;
export const T1_STROKE_WIDTH_PX = 2;
export const T2_STROKE_WIDTH_PX = 1;

// --- Class-bucket box colors (docs/plans/done/CV-FLY-INTERACTION-RESEARCH.md §3.2/D5, wave W4) ---------------
// Per-track hash hues made a dense scene read as confetti (D5, see `trackHue`'s own removal note
// above) — color now carries *class*, not *identity* (identity stays the `#id` text and box
// constancy, unchanged). Three buckets, each a **fixed** hue (never hashed — research's own "one
// stable hue each"), at the identical `MODEL_HUE_SATURATION`/`MODEL_HUE_LIGHTNESS` vivid/legible band
// `modelHue` already uses. Hues are picked from this app's own palette ramps (`styles.css`,
// docs/plans/done/VISUAL-REFRESH-PLAN.md F1) and kept ≥56° from every hue this file already draws
// with meaning elsewhere: `DEFAULT_BOX_COLOR`/`--blue-500` (≈219°, reserved for T0 below), the hover
// amber / `--amber-500` family (≈38-41°), and `--red-500` (≈0°, reserved for danger/critical
// everywhere else in this app — matching the dataviz "status colors are reserved, never reused for a
// plain series" rule this app's own token file already follows).

export type ClassBucket = 'person' | 'vehicle' | 'other';

/** `--rose-500`'s own hue (`styles.css`) — reused here as a fixed categorical color, not for its
 *  usual "live/happening now" meaning; the canvas overlay and a "LIVE" badge never share a visual
 *  context, so the two meanings never collide on screen. */
const PERSON_BUCKET_HUE = 340;
/** `--green-500`'s own hue (`styles.css`). */
const VEHICLE_BUCKET_HUE = 146;
/** Not tied to any named token — the catch-all for every class that isn't recognizably a person or a
 *  vehicle (the overwhelming majority of an open-vocab model's ~4585-class vocabulary,
 *  docs/plans/done/CV-CONTROL-PLAN.md Wave E's own measurement). */
const OTHER_BUCKET_HUE = 275;

const PERSON_LABEL_KEYWORDS: readonly string[] = ['person', 'pedestrian', 'human', 'man', 'woman', 'child', 'rider'];
const VEHICLE_LABEL_KEYWORDS: readonly string[] = [
  'car', 'truck', 'bus', 'van', 'motorcycle', 'motorbike', 'bicycle', 'bike', 'vehicle',
  'boat', 'ship', 'airplane', 'aircraft', 'plane', 'train', 'tank', 'drone', 'uav', 'scooter', 'trailer',
];

/** Strips a composite-mode `"model:label"` prefix the same way {@link detectionModelKey} does, so a
 *  bucket decision is made against the bare class name regardless of which member model tagged it. */
function bareClassLabel(label: string): string {
  const separatorIndex = label.indexOf(':');
  return separatorIndex > 0 ? label.slice(separatorIndex + 1) : label;
}

/**
 * A simple keyword heuristic over the label text — deliberately lenient (substring match against a
 * short list, not an exact enum) since an open-vocab model's real vocabulary is thousands of synonym/
 * scene labels, not a fixed COCO-style list. A label matching neither keyword set reads as
 * {@link ClassBucket} `'other'`, never a fabricated guess at a more specific bucket.
 */
export function classBucket(label: string): ClassBucket {
  const lower = bareClassLabel(label).toLowerCase();
  if (PERSON_LABEL_KEYWORDS.some((keyword) => lower.includes(keyword))) {
    return 'person';
  }
  if (VEHICLE_LABEL_KEYWORDS.some((keyword) => lower.includes(keyword))) {
    return 'vehicle';
  }
  return 'other';
}

function bucketHue(bucket: ClassBucket): number {
  switch (bucket) {
    case 'person':
      return PERSON_BUCKET_HUE;
    case 'vehicle':
      return VEHICLE_BUCKET_HUE;
    case 'other':
      return OTHER_BUCKET_HUE;
  }
}

/** The stable, fixed-hue color for one {@link ClassBucket} — same shape/alpha contract as
 *  {@link modelHue} (an `hsl()` string, alpha embedded once `alphaPercent < 100`). */
export function classBucketHue(bucket: ClassBucket, alphaPercent = 100): string {
  const hue = bucketHue(bucket);
  return alphaPercent >= 100
    ? `hsl(${hue} ${MODEL_HUE_SATURATION}% ${MODEL_HUE_LIGHTNESS}%)`
    : `hsl(${hue} ${MODEL_HUE_SATURATION}% ${MODEL_HUE_LIGHTNESS}% / ${alphaPercent}%)`;
}

/**
 * The stroke/fill color for one non-T0, non-hovered detection — {@link modelHue} when the batch
 * actually mixes ≥2 models this frame (`composite` — composite mode, `distinctModelKeys`'s own gate,
 * the multi-model legend case the research disposition table says to keep unchanged),
 * {@link classBucketHue} otherwise. T0 and hover are **not** decided here —
 * `shared/player/player.ts#drawBox` branches those first (the committed-target accent and the amber
 * hover override both take priority over this function entirely, never blended with it).
 */
export function tierBoxColor(detection: Detection, composite: boolean, alphaPercent = 100): string {
  return composite
    ? modelHue(detectionModelKey(detection), alphaPercent)
    : classBucketHue(classBucket(detection.label), alphaPercent);
}

/** T1's own label text — {@link formatDetectionLabel} minus the confidence percent (research
 *  §3.2/D4: confidence leaves every label but T0's and the hover tooltip's, which both keep the full,
 *  unchanged {@link formatDetectionLabel}). Not called for T2/T3, which never draw a label at all. */
export function formatTierLabel(detection: Detection, tier: DetectionTier): string {
  if (tier === 'T0') {
    return formatDetectionLabel(detection);
  }
  return detection.track ? `#${detection.track.id} ${detection.label}` : detection.label;
}

// --- Label collision-yield (docs/plans/done/CV-FLY-INTERACTION-RESEARCH.md §3.3, wave W4) --------------------

/** Total labels {@link placeLabels} paints per frame, across every tier combined — research §3.3's
 *  own "cap total painted labels (~10)". T0's label is not specially exempted from this cap in code,
 *  but in practice never collides with it: {@link detectionTiers} only ever promotes the hovered box
 *  and/or the one FOLLOW-locked track to T0, so as long as the caller places T0 candidates first
 *  (every call site in this file does), the T0 label is placed before the cap could ever be reached. */
export const MAX_PAINTED_LABELS = 10;

export type LabelSlot = 'above' | 'below' | 'inside-top';

const LABEL_SLOT_ORDER: readonly LabelSlot[] = ['above', 'below', 'inside-top'];

export interface LabelBoxRect {
  readonly x: number;
  readonly y: number;
  readonly width: number;
  readonly height: number;
}

/** One label {@link placeLabels} is asked to place — `key` is what {@link PlacedLabel}'s own
 *  hysteresis map is keyed on, stable across frames only for a tracked detection (its track id
 *  namespaced by the caller, e.g. `"#7"`); an untracked detection's key simply never benefits from
 *  hysteresis — there's nothing frame-to-frame-stable to key it on. */
export interface LabelCandidate {
  readonly key: string;
  readonly box: LabelBoxRect;
  readonly labelWidth: number;
  readonly labelHeight: number;
}

export interface PlacedLabel {
  readonly key: string;
  readonly rect: LabelBoxRect;
  readonly slot: LabelSlot;
}

function slotRect(slot: LabelSlot, box: LabelBoxRect, width: number, height: number): LabelBoxRect {
  switch (slot) {
    case 'above':
      return { x: box.x, y: box.y - height, width, height };
    case 'below':
      return { x: box.x, y: box.y + box.height, width, height };
    case 'inside-top':
      return { x: box.x, y: box.y, width, height };
  }
}

function rectsOverlap(a: LabelBoxRect, b: LabelBoxRect): boolean {
  return a.x < b.x + b.width && a.x + a.width > b.x && a.y < b.y + b.height && a.y + a.height > b.y;
}

/**
 * Greedy, priority-ordered label placement (research §3.3) — `candidates` must already be in
 * draw-priority order (T0 first; `shared/player/player.ts#redrawOverlay`, this function's only
 * caller, sorts its T0/T1 candidates that way before calling). For each candidate in turn: try its
 * {@link previousSlots} entry first if it has one (hysteresis — "prefer last frame's placement if
 * still valid", avoiding a label hopping position every frame off a marginal box move), then fall
 * through `above → below → inside-top`; a candidate that collides in all three positions against
 * every already-placed label this frame paints no label at all (the box itself still draws — identity
 * is recoverable by hover, per the research doc's own "the box still draws" rule). Stops once
 * {@link MAX_PAINTED_LABELS} labels have been placed.
 */
export function placeLabels(
  candidates: readonly LabelCandidate[],
  previousSlots: ReadonlyMap<string, LabelSlot>,
): readonly PlacedLabel[] {
  const placed: PlacedLabel[] = [];
  for (const candidate of candidates) {
    if (placed.length >= MAX_PAINTED_LABELS) {
      break;
    }
    const preferred = previousSlots.get(candidate.key);
    const order = preferred ? [preferred, ...LABEL_SLOT_ORDER.filter((slot) => slot !== preferred)] : LABEL_SLOT_ORDER;
    for (const slot of order) {
      const rect = slotRect(slot, candidate.box, candidate.labelWidth, candidate.labelHeight);
      if (!placed.some((existing) => rectsOverlap(existing.rect, rect))) {
        placed.push({ key: candidate.key, rect, slot });
        break;
      }
    }
  }
  return placed;
}

// --- Click-to-follow: box or point (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7 D8, wave W3.5) ---------

/**
 * What an operator's click on the overlay resolved to — `player.ts#onOverlayClick`'s own hit-test
 * (against `drawnBoxes`) already found (or didn't find) a box before calling
 * {@link resolveOverlayClickTarget}; this is the *decision* that hit-test result maps to, extracted
 * as a pure function per this codebase's own convention (favor pure-logic vitest over a component
 * spec — there is no existing `player.spec.ts`/TestBed harness for this component, and one built from
 * scratch for a single click handler would be disproportionate; see `click-to-follow.spec.ts`'s own
 * doc comment for the wave's full reasoning).
 *
 * Wire convention (confirmed, not assumed — there is no other convention anywhere else on this wire
 * for a bare point): `TargetLockRequest.pointX`/`pointY` (`core/api/models.ts`) is the exact same
 * normalized `[0,1]` video-frame fraction {@link Detection}'s own `box` already uses — so `'point'`'s
 * `x`/`y` here are that fraction directly, never pixels.
 */
export type OverlayClickTarget =
  | { readonly kind: 'track'; readonly trackId: number }
  | { readonly kind: 'point'; readonly x: number; readonly y: number }
  | { readonly kind: 'none' };

/**
 * D8's three-way split:
 *  - `hit` is a **tracked** detection (`track.id` set) → `'track'`, byte-identical to this app's
 *    pre-W3.5 click-to-follow behavior (`trackFollowed`'s own wire, docs/plans/done/TRACKING-PLAN.md §4.D).
 *  - `hit` is an **untracked** detection → `'point'` at that detection's own box center. No pixel
 *    math needed: the box's normalized coordinates already are what the wire wants ({@link boxCenter},
 *    reused verbatim from the unmatched-projection code above this section).
 *  - **no hit** (`hit === null`, an open-canvas click) → the click's own CSS-pixel position
 *    (`clickX`/`clickY`, already relative to the canvas — same coordinate space `drawnBoxes` itself
 *    is in) normalized against `content`, the frame's *effective*, already crop-follow-transformed
 *    content rect (the same one `drawDetections` paints every box against — `player.ts#onOverlayClick`
 *    recomputes it via `letterboxRect` + `applyCropFollowToContentRect(cropFollowTransform(...))`,
 *    mirroring `redrawOverlay`'s own two-step pipeline exactly, so this inversion is always consistent
 *    with the forward mapping at any zoom). A result outside `[0,1]` on either axis means the click
 *    landed in a letterbox bar, not on the video itself — `'none'`, matching this hit-test's existing
 *    "click on nothing meaningful" posture (no lock request is sent). A result just inside the edge is
 *    clamped defensively for float error ({@link clampUnit}, reused verbatim). `!(value > 0)` (rather
 *    than `value <= 0`) is used for the degenerate-content guard so a `NaN` width/height — e.g. a
 *    zero-dimension video between `phase() === 'playing'` and the first metadata tick — also falls
 *    through to `'none'` instead of propagating a `NaN` point.
 */
export function resolveOverlayClickTarget(
  hit: Detection | null,
  clickX: number,
  clickY: number,
  content: { readonly x: number; readonly y: number; readonly width: number; readonly height: number },
): OverlayClickTarget {
  if (hit) {
    const trackId = hit.track?.id;
    if (trackId !== undefined) {
      return { kind: 'track', trackId };
    }
    const center = boxCenter(hit.box);
    return { kind: 'point', x: center.x, y: center.y };
  }
  if (!(content.width > 0) || !(content.height > 0)) {
    return { kind: 'none' };
  }
  const x = (clickX - content.x) / content.width;
  const y = (clickY - content.y) / content.height;
  if (x < 0 || x > 1 || y < 0 || y > 1) {
    return { kind: 'none' };
  }
  return { kind: 'point', x: clampUnit(x), y: clampUnit(y) };
}
