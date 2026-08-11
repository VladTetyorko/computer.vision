import type {
  CvModel,
  CvTracker,
  DetectionResult,
  DetectorReason,
  PatchStreamConfigResponse,
  TrackingMode,
  TrackStats,
  UpdateStreamConfigRequest,
} from '../../core/api/models';
import type { PipelineSettings } from '../../core/settings/settings-store';

/**
 * Pure, Angular-free logic behind `cv-control-panel.ts` (docs/plans/done/CV-CONTROL-PLAN.md Wave E) — the Fly
 * cockpit's live CV control panel: model-roster lookups, class-filter chip edits, PATCH-body
 * construction for the two families of change (hot knobs vs. a model swap), and the honest
 * re-arm/perf hint copy. Split out so every rule is unit-testable without Angular/HTTP/timers,
 * mirroring `flight-command-panel-logic.ts`'s identical split for this cockpit's other panel.
 *
 * **No class strings are hardcoded here beyond the one, clearly-opt-in preset** — see
 * `PEOPLE_VEHICLES_BUILDINGS_PRESET`'s own doc comment for why that one constant is a deliberate,
 * named exception, not a rule reversal. Every other class-filter derivation reads real data (the
 * roster's own `defaultLabelFilter`, or labels already seen in the live detection stream).
 */

// --- Model roster lookups ------------------------------------------------------------------

/** The roster entry for `modelId`, or `undefined` for an id the roster doesn't (yet) know about —
 * a stale/unreconciled persisted choice, or the roster fetch hasn't resolved yet. Every reader here
 * degrades to "no extra facts about this model" rather than guessing. */
export function findModel(models: readonly CvModel[], modelId: string): CvModel | undefined {
  return models.find((model) => model.id === modelId);
}

/**
 * The class filter to seed when the operator switches to `model` (docs/plans/done/CV-CONTROL-PLAN.md Wave E,
 * amended after cv-service Wave A's real-vocabulary measurement): an **open-vocabulary** model
 * always seeds `[]` ("show every class"), regardless of what the roster's own `defaultLabelFilter`
 * says — prompt-free YOLOE's real vocabulary (~4585 classes) emits many synonym/scene labels for
 * one real-world thing (`"building"`/`"skyscraper"`/`"office building"`/`"downtown"`/a named
 * landmark, all for what a person would call "a building"), so a fixed preset filter would silently
 * *drop* most real detections rather than usefully narrow them — it would look broken. A
 * **closed-set** model's own `defaultLabelFilter` is honored as-is: it's small, enumerable, and
 * already accurate for that model (docs/plans/done/CV-CONTROL-PLAN.md §4 item 4). `undefined` (roster hasn't
 * resolved this id yet) seeds `[]` too — never guess a restrictive filter for a model with no known
 * facts.
 */
export function seedLabelFilterForModel(model: CvModel | undefined): readonly string[] {
  if (model === undefined || model.openVocab) {
    return [];
  }
  return model.defaultLabelFilter;
}

// --- Class-filter chip candidates (docs/plans/done/CV-CONTROL-PLAN.md Wave E, coordinator amendment) -----
// "Prune from what the model is really seeing, not a guessed a-priori list": the chip checklist is
// built from labels actually observed in the live detection stream, unioned with whatever is
// already in the filter (so a chosen-but-not-currently-visible label never disappears).

/** Distinct detection labels seen across `results`, in first-seen order — the observed half of the
 * chip candidate set (see `chipCandidates`). No cap: this panel is a full drawer, not the Live
 * page's small strip (`core/detections/detections-logic.ts#deriveChips`, capped at 8, is a
 * different UI with a different budget). */
export function observedLabels(results: readonly DetectionResult[]): readonly string[] {
  const seen = new Set<string>();
  for (const result of results) {
    for (const detection of result.detections) {
      seen.add(detection.label);
    }
  }
  return [...seen];
}

/**
 * The full chip checklist: every currently-selected label (so toggling one off never makes it
 * disappear from the list) unioned with every recently-observed label (so the operator can prune
 * what is actually showing up), sorted for a stable, scannable order.
 */
export function chipCandidates(
  labelFilter: readonly string[],
  observed: readonly string[],
): readonly string[] {
  const set = new Set([...labelFilter, ...observed]);
  return [...set].sort((a, b) => a.localeCompare(b));
}

/** Whether `label` reads as "checked" in the chip checklist — an empty `labelFilter` means "show
 * every class", so every candidate reads as checked in that state (there is no way to represent
 * "explicitly none" on the wire — see `toggleLabelChip`'s own doc comment). */
export function isLabelChecked(labelFilter: readonly string[], label: string): boolean {
  return labelFilter.length === 0 || labelFilter.includes(label);
}

/**
 * Toggles one chip in the checklist.
 *
 * - Starting from `[]` ("all"): unchecking `label` narrows to every *other* known candidate — the
 *   only way to express "all but this one" on the wire is to enumerate the rest.
 * - Starting from a concrete list: toggles `label`'s membership normally.
 *
 * **Honest edge case, not hidden**: unchecking the last remaining explicit label produces `[]`
 * again — the wire contract has no way to express "show nothing" (empty is defined as "all"), so
 * the panel's own copy says as much rather than pretending a fourth state exists.
 */
export function toggleLabelChip(
  labelFilter: readonly string[],
  label: string,
  allCandidates: readonly string[],
): readonly string[] {
  if (labelFilter.length === 0) {
    return allCandidates.filter((candidate) => candidate !== label);
  }
  return labelFilter.includes(label)
    ? labelFilter.filter((entry) => entry !== label)
    : [...labelFilter, label];
}

// --- Free-text add (for a class not yet observed at all) -------------------------------------

/** Adds `label` (trimmed) to the filter if it isn't blank/already present — a no-op returns the
 * identical array reference so callers can skip a redundant patch. Naturally narrows `[]` ("all")
 * down to `[label]` alone, since that's exactly what appending to an empty array produces — adding
 * an explicit class is inherently a narrowing action. */
export function addLabel(current: readonly string[], label: string): readonly string[] {
  const trimmed = label.trim();
  if (trimmed.length === 0 || current.includes(trimmed)) {
    return current;
  }
  return [...current, trimmed];
}

// --- Search box (filters the chip checklist; doubles as the free-text "add" gate) -------------

/** Case-insensitive substring filter over the chip checklist for the panel's own search box —
 * returns `candidates` unchanged (same reference) for a blank query, so an empty search never
 * triggers a redundant re-render of the checklist. */
export function filterLabelsByQuery(candidates: readonly string[], query: string): readonly string[] {
  const trimmed = query.trim().toLowerCase();
  if (trimmed.length === 0) {
    return candidates;
  }
  return candidates.filter((candidate) => candidate.toLowerCase().includes(trimmed));
}

/** Whether `query` (trimmed, case-insensitive) exactly matches one of `candidates` — the search box
 * only offers "Add" once nothing already in the checklist matches; a blank query never matches, so
 * the empty checklist state doesn't also offer to "add" nothing. */
export function hasExactLabelMatch(candidates: readonly string[], query: string): boolean {
  const trimmed = query.trim().toLowerCase();
  return trimmed.length > 0 && candidates.some((candidate) => candidate.toLowerCase() === trimmed);
}

/**
 * Reorders `candidates` so entries already in `labelFilter` sort first (each half keeping its own
 * incoming order) — surfaces "what's already selected, ready to remove" at the top of the checklist
 * the moment the operator opens the panel, without a second, duplicated list. A UX fix: the
 * checklist used to interleave selected/unselected alphabetically, so finding what to remove from a
 * short, narrowed filter meant scanning past every unselected class first.
 *
 * A deliberate no-op (identical order) while `labelFilter` is `[]` ("all") — every candidate reads
 * as selected then (`isLabelChecked`), so there is nothing to promote and alphabetical stays the
 * more scannable order for "browse everything, uncheck a few".
 */
export function sortSelectedFirst(
  candidates: readonly string[],
  labelFilter: readonly string[],
): readonly string[] {
  if (labelFilter.length === 0) {
    return candidates;
  }
  const selected = candidates.filter((candidate) => labelFilter.includes(candidate));
  const unselected = candidates.filter((candidate) => !labelFilter.includes(candidate));
  return [...selected, ...unselected];
}

// --- Opt-in convenience preset (docs/plans/done/CV-CONTROL-PLAN.md Wave E, coordinator amendment) --------

/**
 * A convenience one-click chip-fill — **never an enforced/silent default** (see
 * `seedLabelFilterForModel`, which deliberately does NOT apply this). Prompt-free YOLOE's real
 * vocabulary emits many synonym/scene labels for "a building"
 * (`building`/`skyscraper`/`office building`/`house`/`apartment`/`roof`/`tower`, alongside outright
 * scene labels like `downtown` or a named landmark this preset does not attempt to cover) — this is
 * a best-effort, fully editable starting set the operator opts into with one click and can extend
 * or prune afterward with the observed-label chips, not a filter ever applied on their behalf.
 */
export const PEOPLE_VEHICLES_BUILDINGS_PRESET: readonly string[] = [
  'person',
  'car',
  'truck',
  'bus',
  'motorcycle',
  'bicycle',
  'van',
  'building',
  'skyscraper',
  'office building',
  'house',
  'apartment',
  'roof',
  'tower',
];

/**
 * Applies the preset: narrows `[]` ("all") down to exactly the preset (the only way "fill these
 * in" reads sensibly when nothing is filtered yet), or adds the preset's labels into an existing
 * concrete filter without disturbing what's already selected.
 */
export function applyPreset(
  current: readonly string[],
  preset: readonly string[] = PEOPLE_VEHICLES_BUILDINGS_PRESET,
): readonly string[] {
  if (current.length === 0) {
    return [...preset];
  }
  return [...new Set([...current, ...preset])];
}

// --- PATCH body construction (docs/plans/done/CV-CONTROL-PLAN.md §2-3's frozen contract) -----------------

/**
 * The hot-knob patch body — confidence/fps/labelFilter/detectionEnabled, **never** `model` (a model
 * change is always its own separate call, {@link buildModelChangePatch}, so a slider drag or a chip
 * toggle can never accidentally trigger a re-arm). Sent on every debounced hot-knob edit while a
 * stream is running.
 */
export function buildHotKnobPatch(settings: PipelineSettings): UpdateStreamConfigRequest {
  return {
    confidenceThreshold: settings.confidenceThreshold,
    inferenceFps: settings.inferenceFps,
    labelFilter: settings.labelFilter,
    detectionEnabled: settings.detectionEnabled,
  };
}

/** The model-change patch body — `model` alone; the server reports back whether it actually
 * differed from the running model via `modelReArmed` (see {@link reArmHint}). */
export function buildModelChangePatch(modelId: string): UpdateStreamConfigRequest {
  return { model: modelId };
}

// --- Honest hint copy (docs/plans/done/CV-CONTROL-PLAN.md §E/§F) ------------------------------------------

/** Shown briefly after a model-change PATCH whose response says `modelReArmed` — never claims the
 * video was interrupted, because it wasn't (docs/plans/done/CV-CONTROL-PLAN.md §A: only the detection branch
 * briefly re-opens its session). `null` when the server says nothing needed re-arming (the model
 * didn't actually change, or the response is from a hot-knob-only patch). */
export function reArmHint(response: PatchStreamConfigResponse): string | null {
  return response.modelReArmed ? 'Re-arming detection with the new model — video keeps playing.' : null;
}

/**
 * The one-line perf-budget hint shown near the fps/model controls (docs/plans/done/CV-CONTROL-PLAN.md §F) —
 * always present, worded more urgently once the open-vocabulary model is actually selected
 * (materially slower on a laptop CPU: open-set lookup + segmentation). Class filtering never
 * appears in this hint: per §E, the model still infers every class every frame regardless of the
 * filter (a Java-side drop after the fact) — only the inference-rate slider and the detection
 * on/off toggle actually change CPU cost.
 */
export function perfHint(openVocabSelected: boolean): string {
  return openVocabSelected
    ? 'This model is open-vocabulary — materially slower on a laptop CPU (open-set lookup + segmentation). Lower the inference rate or turn detection off to reclaim CPU; video keeps streaming at full rate regardless.'
    : "Inference rate and detection on/off are the CPU-budget controls — the class filter only trims what's shown, not what the model computes.";
}

// --- Tracking engine (docs/plans/done/TRACKING-PLAN.md §4's frozen wire contract, wave T7) -----------------
// The Tracking section's own patch builders, roster filter, and flow-strip formatter — pure so the
// mode-gating, the follow-lock honesty rule, and the flow-strip math (docs/extracts/TRACKING-ORCHESTRATION.md
// §7) are all testable without Angular/HTTP/timers, mirroring every other builder in this file.
// **The backend for this contract had not shipped when this wave landed** — every function here is
// coded against docs/plans/done/TRACKING-PLAN.md §4 with nothing live to exercise it against.

/** `TrackingConfigRequest.verifyEveryMillis`'s server-side default (docs/plans/done/TRACKING-PLAN.md §4.A) —
 *  seeds the verify-cadence slider before any confirmed value has ever come back from the wire (the
 *  `stats` object carries no such figure — see `TrackStats`'s own doc comment — so, unlike
 *  mode/engine, this slider has no ground-truth readback in this app). */
export const DEFAULT_VERIFY_EVERY_MILLIS = 2_000;

/** `TrackingConfigRequest.followFps`'s server-side default (docs/extracts/TRACKING-ORCHESTRATION.md §4.3,
 *  decision D12) — same "no readback" caveat as {@link DEFAULT_VERIFY_EVERY_MILLIS} above. */
export const DEFAULT_FOLLOW_FPS = 15;

/** A mode-only patch — the Off/Associate/Follow segmented control's own PATCH body. Never coalesced
 *  with a hot knob or a model change (see `UpdateStreamConfigRequest#tracking`'s own doc comment). */
export function buildTrackingModePatch(mode: TrackingMode): UpdateStreamConfigRequest {
  return { tracking: { mode } };
}

/** An engine-only patch — the engine picker's own PATCH body. */
export function buildTrackingEnginePatch(engineId: string): UpdateStreamConfigRequest {
  return { tracking: { engineId } };
}

/** The verify-cadence slider's own patch body (`FOLLOW`-only knob, milliseconds between detector
 *  re-verify passes — docs/plans/done/TRACKING-PLAN.md §3.1). */
export function buildVerifyCadencePatch(verifyEveryMillis: number): UpdateStreamConfigRequest {
  return { tracking: { verifyEveryMillis } };
}

/** The follow-fps slider's own patch body (`FOLLOW`-only knob — the Java-side sampler rate feeding
 *  the tracker, docs/extracts/TRACKING-ORCHESTRATION.md §4.3: "`followFps` has no Python knob on purpose"). */
export function buildFollowFpsPatch(followFps: number): UpdateStreamConfigRequest {
  return { tracking: { followFps } };
}

/**
 * Click-to-follow's own patch body (docs/plans/done/TRACKING-PLAN.md §4.D) — always sets `mode: 'FOLLOW'`
 * alongside the lock in the same call, matching the plan's own worked example
 * (`{tracking:{mode:"FOLLOW", lock:{trackId}}}`) so clicking a box while `ASSOCIATE`/`OFF` is active
 * both switches the mode and locks in one PATCH, not two.
 *
 * **This patch alone never shows the "Following #N" chip.** The chip renders only once a later poll
 * of `GET .../tracks` echoes back this same `trackId` as `lockedTrackId` —
 * docs/extracts/TRACKING-ORCHESTRATION.md §3.3's honesty rule: "the UI reflects confirmed state from the
 * wire, never local intent." A lock cv-service couldn't honor (the target already `LOST`) simply
 * never shows a chip, rather than a lying one.
 */
export function buildFollowLockPatch(trackId: number): UpdateStreamConfigRequest {
  return { tracking: { mode: 'FOLLOW', lock: { trackId } } };
}

/** The "release" chip's own patch body — drops the current lock, falling back to the mode's own
 *  policy (docs/plans/done/TRACKING-PLAN.md §4.A's `TargetLock#release`). Leaves `mode` untouched. */
export function buildReleaseLockPatch(): UpdateStreamConfigRequest {
  return { tracking: { lock: { release: true } } };
}

/**
 * The engine picker's own candidate list — every roster entry whose `modes` includes `mode`
 * (docs/plans/done/TRACKING-PLAN.md §4.F: `bytetrack` advertises `["ASSOCIATE"]`, `lk`/`ncc` advertise
 * `["FOLLOW"]`). `OFF` has no engine to pick, by construction — always `[]`, so the caller never has
 * to special-case "there is no tracker running" separately from "the roster is empty".
 */
export function engineOptionsForMode(trackers: readonly CvTracker[], mode: TrackingMode): readonly CvTracker[] {
  if (mode === 'OFF') {
    return [];
  }
  return trackers.filter((tracker) => tracker.modes.includes(mode));
}

/** `DETECT ?/s` / `TRACK ?/s` — one decimal only when the value isn't a whole number, so a common
 *  round figure (`15/s`) doesn't read as `15.0/s`. */
function formatRatePerSecond(count: number, windowSeconds: number): string {
  if (windowSeconds <= 0 || !Number.isFinite(count) || count < 0) {
    return '0/s';
  }
  const perSecond = count / windowSeconds;
  const rounded = Math.round(perSecond * 10) / 10;
  return `${Number.isInteger(rounded) ? rounded.toFixed(0) : rounded.toFixed(1)}/s`;
}

/** `"1 in N"` — the duty ratio expressed the way an operator reads it ("roughly one frame in
 *  thirty gets a real detector pass"), rather than the raw fraction the wire carries. `—` for a
 *  window with no detector passes recorded yet (division by zero would otherwise read "1 in ∞"). */
function formatDutyRatio(dutyRatio: number): string {
  if (!Number.isFinite(dutyRatio) || dutyRatio <= 0) {
    return '—';
  }
  return `1 in ${Math.max(1, Math.round(1 / dutyRatio))}`;
}

/**
 * The flow strip's own text (docs/plans/done/TRACKING-PLAN.md §10 touchable outcome #2, docs/TRACKING-
 * ORCHESTRATION.md §7's "visible flow" tier) — e.g. `"DETECT 0.5/s ▸ TRACK 15/s · 1 in 29 · lk 0.4 ms
 * · cadence"`. Turns the plan's own core claim ("the detector stopped running and the tracker took
 * over") into something read off the screen instead of `htop` on a remote inference box.
 *
 * **Callers must check `stats` for presence before calling this** — there is no "no stats" case
 * represented here at all; an absent `stats` means the strip doesn't render, full stop (`TrackStats`'s
 * own doc comment). `engineId` and `lastDetectorReason` are shown exactly as the wire reports them —
 * the engine **actually serving**, per R11 (docs/plans/done/TRACKING-PLAN.md §9), not whatever the operator last
 * requested in the picker.
 */
export function formatFlowStrip(stats: TrackStats): string {
  const detectRate = formatRatePerSecond(stats.detectorPasses, stats.windowSeconds);
  const trackRate = formatRatePerSecond(stats.trackerFrames, stats.windowSeconds);
  const duty = formatDutyRatio(stats.dutyRatio);
  const engine = stats.engineId.length > 0 ? stats.engineId : '—';
  const p50 = Number.isFinite(stats.trackerMillisP50) ? `${stats.trackerMillisP50.toFixed(1)} ms` : '— ms';
  const reason = formatDetectorReason(stats.lastDetectorReason);
  return `DETECT ${detectRate} ▸ TRACK ${trackRate} · ${duty} · ${engine} ${p50} · ${reason}`;
}

/** `"CADENCE"` → `"cadence"` — every `DetectorReason` member reads as a plain lowercase word once
 *  formatted, per docs/plans/done/TRACKING-PLAN.md §4.A's own naming (`ALWAYS`/`CADENCE`/`TRACKER_FAILED`/
 *  `NO_LOCK`/`BOX_INVALID`/`COASTED_OUT`); the one multi-word member gets a space, not an underscore. */
function formatDetectorReason(reason: DetectorReason): string {
  return reason.toLowerCase().replace(/_/g, ' ');
}

// --- Debounce (hot-knob coalescing) -------------------------------------------------------------

/**
 * A trailing-edge debounce: `run()` schedules `fn`, cancelling any still-pending call from a
 * previous `run()` — the shape every hot-knob control shares to coalesce a fast slider drag (or a
 * burst of chip toggles) into one PATCH instead of one per input event. `cancel()` drops a pending
 * call outright, used both on component teardown and whenever a discrete action (a model change)
 * should never be coalesced with a still-pending hot-knob patch.
 */
export function debounce<Args extends unknown[]>(
  fn: (...args: Args) => void,
  delayMs: number,
): { readonly run: (...args: Args) => void; readonly cancel: () => void } {
  let handle: ReturnType<typeof setTimeout> | undefined;
  return {
    run(...args: Args): void {
      if (handle !== undefined) {
        clearTimeout(handle);
      }
      handle = setTimeout(() => {
        handle = undefined;
        fn(...args);
      }, delayMs);
    },
    cancel(): void {
      if (handle !== undefined) {
        clearTimeout(handle);
        handle = undefined;
      }
    },
  };
}
