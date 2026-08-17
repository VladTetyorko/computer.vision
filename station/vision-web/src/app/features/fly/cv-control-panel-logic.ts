import type {
  CvModel,
  CvTracker,
  DetectionResult,
  DetectionState,
  DetectorReason,
  FrameTracking,
  PatchStreamConfigResponse,
  TrackingCapability,
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

/** Tier-0's "Seen now" chip cap (docs/plans/active/CV-UX-RESEARCH.md §4.1/§7 wave U4) — small
 *  enough to read at a glance, unlike {@link observedLabels}' uncapped full history that feeds the
 *  Tune tier's complete checklist. */
export const SEEN_NOW_CHIP_CAP = 8;

/**
 * The most recently observed distinct labels, capped at {@link SEEN_NOW_CHIP_CAP} — the tier-0
 * "Seen now" quick glance (docs/plans/active/CV-UX-RESEARCH.md §4.1), as opposed to
 * {@link observedLabels}'s uncapped full history. `results` is assumed newest-first
 * (`DetectionsStore.results()`'s documented contract, the same assumption
 * {@link latestFrameTracking} makes) — this scans forward from the newest result so a label first
 * spotted a few frames back but still showing up keeps its place, without needing every result in
 * the buffer to hit the cap.
 */
export function recentObservedLabels(
  results: readonly DetectionResult[],
  cap: number = SEEN_NOW_CHIP_CAP,
): readonly string[] {
  const seen = new Set<string>();
  for (const result of results) {
    if (seen.size >= cap) {
      break;
    }
    for (const detection of result.detections) {
      if (seen.size >= cap) {
        break;
      }
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
 * How many distinct classes are actually visible right now, after the class filter — the honest
 * number the tier-0 status line names (docs/plans/active/CV-UX-RESEARCH.md §3's mockup, "3 classes
 * on screen"). Reads only the single most recent detection result (`results[0]`, the same
 * newest-first assumption {@link latestFrameTracking} documents) — what's actually on screen right
 * now, not {@link observedLabels}' running history. `0` for no results yet.
 */
export function classesOnScreenCount(
  latest: DetectionResult | undefined,
  labelFilter: readonly string[],
): number {
  if (!latest) {
    return 0;
  }
  const shown = new Set(
    latest.detections.map((detection) => detection.label).filter((label) => isLabelChecked(labelFilter, label)),
  );
  return shown.size;
}

/**
 * Toggles one chip in a class-filter array — the pure array-math primitive behind both this
 * panel's staged edits (`cv-control-panel.ts#toggleChip`, via {@link stagedLabelSeed}) and, before
 * this task, an immediate PATCH. The math is identical either way:
 *
 * - Starting from `[]` ("all"): unchecking `label` narrows to every *other* known candidate — the
 *   only way to express "all but this one" on the wire is to enumerate the rest.
 * - Starting from a concrete list: toggles `label`'s membership normally.
 * - Unchecking the last remaining explicit label produces `[]` again — the wire contract has no way
 *   to express "show nothing" (empty is defined as "all").
 *
 * **No longer an "immediate-apply" edge case, per the staged-selection rework** (direct user
 * request): this array reverting to `[]` used to matter because every click applied straight to the
 * wire, so unchecking the last filtered class made every class silently reappear on screen
 * mid-session with no warning — worth calling out as an "honest edge case, not hidden" in this
 * function's own doc comment. Staging removes the surprise, not the math: the same `[]` result now
 * only ever lands in `cv-control-panel.ts#pendingLabels`, visible as a pending edit until
 * `cv-control-panel.ts#submitLabelFilter` is actually clicked, and {@link submitLabelFilterButtonText}
 * states outright that submitting an empty selection shows every class again. This function and its
 * existing spec cases are otherwise unchanged — reused as the staging primitive, not superseded.
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

// --- Staged class-filter selection (direct user request: "Seen now" becomes staged selection +
// Submit) --------------------------------------------------------------------------------------
// `labelFilter` used to be edited immediately — one debounced PATCH per chip click. With many
// classes on screen that produced a confusing first-click surprise (the whole reason
// `FIRST_HIDE_HINT` below exists) and a PATCH burst on a fast run of clicks. The panel now stages
// edits in its own `pendingLabels` signal (`cv-control-panel.ts`) — `null` mirrors the applied
// filter (no edit in progress), a concrete array (possibly `[]`) is unapplied work waiting on an
// explicit Submit. Both class-filter surfaces this panel owns — tier 0's capped "Seen now" chips
// and Tune's uncapped full checklist — read and write through this one signal and the same handlers
// (`toggleChip`/`addClass`/`fillPreset`/`clearLabelFilter`), so the two surfaces can never commit
// the one wire field under two different rules. This changes nothing about the wire contract:
// submitting still means "PATCH labelFilter as-is", and an empty submission still means "every
// class" (`PipelineConfig`'s own "empty means all" javadoc) — see `HIDDEN_CLASS_TRUTH`, unchanged,
// for what a *non-empty* submission means server-side (dropped everywhere, not just the screen, no
// CPU saved — the model still scans for everything regardless of this selection).

/** The list any staged edit builds on top of: the staged selection itself once one exists, or the
 *  currently applied filter on the very first edit this panel session. Every staging entry point —
 *  chip toggle ({@link toggleLabelChip}), the preset fill ({@link applyPreset}), free-text add
 *  ({@link addLabel}), and the checked/selected-first-sort reads that decide what the checklist
 *  looks like — goes through this one function first, so "seed once from what's really applied,
 *  then keep building on the stage" can never drift between call sites or between the two surfaces
 *  that share it. */
export function stagedLabelSeed(
  pending: readonly string[] | null,
  effective: readonly string[],
): readonly string[] {
  return pending ?? effective;
}

/** The Submit button's own label — states plainly what it is about to do rather than a bare
 *  "Submit". An empty staged selection (the Clear-then-Submit path) submits back to "every class"
 *  (`[]` is the wire's own "all" value), so the button says that outright instead of reading like a
 *  no-op or, worse, "apply 0 classes" (which would misread as "hide everything" — the one state this
 *  wire contract cannot express, see {@link toggleLabelChip}'s own doc comment). */
export function submitLabelFilterButtonText(pending: readonly string[]): string {
  if (pending.length === 0) {
    return 'Apply — show every class';
  }
  return `Apply ${pending.length} class${pending.length === 1 ? '' : 'es'}`;
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
 * (materially slower on a laptop CPU: open-set lookup + segmentation).
 *
 * **Corrected per docs/plans/active/CV-UX-RESEARCH.md §1.2/§4.3**: the closed-set branch used to
 * claim the class filter "only trims what's shown" — true about the screen, false about
 * everything else. `labelFilter` is applied Java-side in `StreamPipeline#onDetectionResult`
 * *before all fan-out* (`contexts/vision-perception/.../pipeline/StreamPipeline.java`), so a
 * hidden class is dropped from live SSE, alerts, and recording too, not just the picture — while
 * the model still computes it every frame (no CPU saved). This branch now says both true things:
 * inference rate and detection on/off are the only real CPU knobs, *and* hiding a class is a
 * stronger, not weaker, action than "hide the box" — see {@link HIDDEN_CLASS_TRUTH} for the exact
 * sentence repeated verbatim at rest and in Tune so the two surfaces never drift apart.
 */
export function perfHint(openVocabSelected: boolean): string {
  return openVocabSelected
    ? 'This model is open-vocabulary — materially slower on a laptop CPU (open-set lookup + segmentation). Lower the inference rate or turn detection off to reclaim CPU; video keeps streaming at full rate regardless.'
    : `Inference rate and detection on/off are the CPU-budget controls — the model still computes every class every frame regardless of the filter. ${HIDDEN_CLASS_TRUTH}`;
}

// --- Honest, repeated-verbatim copy (docs/plans/active/CV-UX-RESEARCH.md §4.3/§4.4) ------------
// Two distinct sentences, each with its own job — kept as named constants rather than inlined in
// the template so both surfaces that show them (`cv-control-panel.html`'s "Seen now" tier-0 section
// and its Tune classes section) say the exact same words, and so `perfHint`'s closed-set branch
// above can fold {@link HIDDEN_CLASS_TRUTH} in without duplicating the sentence a third time.

/** What actually happens when a class is hidden — the corrected claim from §4.3, shown once at
 *  rest ("Seen now") and once in Tune's full checklist. Deliberately does **not** say "hiding
 *  classes tells the model what to look for" either — §4.3 names that promise false too (that
 *  would be text-prompted YOLOE, an explicit CV-CONTROL-PLAN non-goal); this states only the two
 *  true facts: dropped everywhere, no speed change. */
export const HIDDEN_CLASS_TRUTH =
  "Hidden classes are dropped everywhere — screen, alerts, recording. The model still scans for everything; hiding classes doesn't make it faster.";

/** The one-time hint shown the first time an operator narrows the class selection from the tier-0
 *  "Seen now" chips (§4.4) — names the wire's one sharp edge (`labelFilter` is an allowlist) before
 *  they submit it, not after: since the staged-selection rework (direct user request), narrowing no
 *  longer applies on the click that triggers this hint, so the wording states the real consequence
 *  of *submitting* a non-empty selection rather than describing something that already happened.
 *  Dismissed once, persisted, never shown again — see `cv-control-panel.ts`'s own
 *  `firstHideHintDismissed` field. */
export const FIRST_HIDE_HINT =
  "Selecting classes builds a whitelist for this stream — once you submit it, only the classes you picked will appear, even if a different class is newly detected later. Clear the selection and submit to go back to every class.";

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

// --- Capability ladder (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md, wave J4) ---------------------------
// The operator picks a *ceiling* (plan decision E12) — `0` = auto-probe, `1`-`5` caps how much of the
// pipeline this stream's host is allowed to spend on it (TRACKING-V3-PLAN.md §5). What actually ran
// is a completely different, server-reported fact (`TrackingCapability`, above) — this section never
// mixes the two: `CAPABILITY_LEVEL_OPTIONS` only ever labels the *request* side of the panel, and
// `isCapabilityDowngraded`/`capabilityLevelLabel` below are the only readers of the *outcome* side,
// each reading the server's own fields, never a local comparison against the ceiling picked here.

/** One entry of the capability-ceiling picker (`cv-control-panel.html`'s `<select>`) — `label` is
 *  also reused, unmodified, to name whatever level the server reports actually serving (see
 *  {@link capabilityLevelLabel}), so the picker and the readout always use the exact same words for
 *  the same level. `hint` is the fuller cost/benefit sentence shown under the picker for whichever
 *  level is currently selected (`TRACKING-V3-PLAN.md` §5.1/§5.2 — the ladder's own measured costs,
 *  not a guess: "L1 works on an ARMv6 companion" and "L3 needs ~2 GB RAM" are both load-bearing
 *  claims from that measurement, so the wording here must stay true to it, not just plausible). */
export interface CapabilityLevelOption {
  readonly value: number;
  readonly label: string;
  readonly hint: string;
}

/** The full picker, `0` (Auto) through `5` (Study) — every label/hint pair traces to
 *  `TRACKING-V3-PLAN.md` §5.2's own ladder table; nothing here is invented. */
export const CAPABILITY_LEVEL_OPTIONS: readonly CapabilityLevelOption[] = [
  {
    value: 0,
    label: 'Auto',
    hint: 'Serves the highest level this host can afford — probed automatically, nothing to configure. The default, and the safest choice for a host you have not measured.',
  },
  {
    value: 1,
    label: 'L1 · Relay',
    hint: 'Identity only, no pixels — boxes come from offboard, over the wire. ~13 MB, pure stdlib. Runs on anything, including an ARMv6 companion with no camera library at all.',
  },
  {
    value: 2,
    label: 'L2 · Fill',
    hint: 'Adds local visual fill between detector passes (optical-flow + telemetry motion compensation) and appearance matching. ~68 MB — needs a real OpenCV build (ARMv7/aarch64, not ARMv6).',
  },
  {
    value: 3,
    label: 'L3 · Detect',
    hint: 'Adds the local, duty-cycled YOLO detector — boxes come from this host, not offboard. ~350 MB, realistically ≥2 GB RAM.',
  },
  {
    value: 4,
    label: 'L4 · Identify',
    hint: 'Adds ROI-pooled and optional OpenVINO re-identification appearance matching — the full identity tier. An Intel inference box or a workstation.',
  },
  {
    value: 5,
    label: 'L5 · Study',
    hint: 'Adds capture, training and model promotion. Workstation only — this level never runs on the airframe.',
  },
];

/** The picker option for `level`, or `undefined` for a value outside `0`-`5` (should not happen —
 *  every control in this panel only ever offers {@link CAPABILITY_LEVEL_OPTIONS}' own values — but a
 *  server-reported `levelServed` is external input and gets the same defensive treatment as every
 *  other wire-sourced number in this file). */
export function capabilityLevelOption(level: number): CapabilityLevelOption | undefined {
  return CAPABILITY_LEVEL_OPTIONS.find((option) => option.value === level);
}

/** The short name for `level` (e.g. `"L2 · Fill"`) — shared by the picker's own options and the
 *  served-level readout, so both name the same level the same way. Falls back to a bare `"L{level}"`
 *  for a level this app's ladder doesn't recognize, never a blank or fabricated name. */
export function capabilityLevelLabel(level: number): string {
  return capabilityLevelOption(level)?.label ?? `L${level}`;
}

/** The picker's own hint line for whichever level is currently selected — {@link CAPABILITY_LEVEL_OPTIONS}'
 *  `hint`, or `''` for an unrecognized value (never shown; the picker only ever selects a known
 *  option). */
export function capabilityLevelHint(level: number): string {
  return capabilityLevelOption(level)?.hint ?? '';
}

/** The capability-ceiling picker's own patch body — `0` sends the proto zero-value (auto-probe,
 *  byte-identical to not setting the field, invariant B2), `1`-`5` caps the level this stream's host
 *  may serve (a ceiling, not a demand — decision E12; the host may still serve less if it cannot
 *  afford the ceiling, see {@link isCapabilityDowngraded}). */
export function buildCapabilityLevelPatch(level: number): UpdateStreamConfigRequest {
  return { tracking: { capabilityLevel: level } };
}

/**
 * Whether the most recent frame's capability facts should render as a **downgrade** rather than a
 * quiet reading (invariant B5, the point of this whole wave). Reads only `capability.reason` — the
 * server's own account of whether `levelServed` matched what the running stream actually asked for
 * when this frame was produced — **never** a comparison against this panel's own locally-picked
 * ceiling. Three reasons that is the honest choice, not a shortcut:
 *
 * 1. The wire contract already carries this exact fact: `reason` is `''` **iff** `levelServed`
 *    matched the request (`TRACKING-V3-BAND1-CONTEXT.md` §2) — recomputing it client-side would be
 *    duplicating logic the server already ran, with a real chance of disagreeing with it.
 * 2. This panel's own ceiling picker has **no server readback** (unlike `trackingMode`/
 *    `trackingEngineId`, which re-sync from `TrackStats` every poll) — the local value can be stale
 *    the moment a page loads mid-session against a stream configured earlier, or between an edit and
 *    its PATCH landing. Comparing a possibly-stale local value against the wire would risk exactly
 *    the "quiet, wrong reading" this wave exists to prevent.
 * 3. `reason` also covers a case a local comparison could never see at all: a host that shed a level
 *    *after* auto-probing under `capabilityLevel: 0` (§5.3's "the level is hot… a loaded host can
 *    shed a level") — there was never an explicit ceiling to compare against, but it is still a
 *    downgrade worth surfacing.
 *
 * `undefined` (no `capability` reported at all) is never a downgrade — see
 * `cv-control-panel.html`'s own "no level reported" branch for how that absence is rendered instead
 * (B5: never silently substitute anything in this slot).
 */
export function isCapabilityDowngraded(capability: TrackingCapability | undefined): boolean {
  return capability !== undefined && capability.reason.trim().length > 0;
}

/**
 * The most recent frame's tracking telemetry, or `undefined` if the newest result has none
 * (tracking off for this stream right now, an old server, or no frame received yet). `results` is
 * assumed newest-first (`DetectionsStore.results()`'s own documented contract), so this reads
 * `results[0]` alone — **deliberately not a scan for the nearest result that happens to carry
 * `tracking`**: once tracking is switched off, every new frame arrives with `tracking` absent, and
 * scanning past that absence to an older, still-tracked frame would render a stale capability/lag
 * reading as if it were current. Reading only the newest entry means "tracking just turned off"
 * shows up immediately as "nothing to report", not as one stale-but-real-looking number.
 */
export function latestFrameTracking(results: readonly DetectionResult[]): FrameTracking | undefined {
  return results[0]?.tracking;
}

/** `docs/conclusions/CV-RATE-BUDGET.md` §1 — the *Hold* job (staying locked onto an already-found
 *  target) budgets detection→association lag at under this many milliseconds; a stream running above
 *  it is starting to lose that job even before any box actually goes missing. Lives here, the one
 *  place this number is defined, rather than inline in the template (invariant B4's UI-side
 *  counterpart: no magic number hardcoded where it's used). */
export const DETECTION_LAG_BUDGET_MILLIS = 50;

/** `detectionLagMillis` renders as over-budget only for a genuinely measured, positive value — `0`
 *  means "unknown" on the wire (`Duration.ZERO`, see `FrameTracking#detectionLagMillis`'s own doc
 *  comment), never "instantaneous", so it is never flagged as either good or bad. */
export function isDetectionLagOverBudget(detectionLagMillis: number): boolean {
  return Number.isFinite(detectionLagMillis) && detectionLagMillis > DETECTION_LAG_BUDGET_MILLIS;
}

/** `"42 ms"`, or `—` for `0`/negative/non-finite — the wire's own "unknown" sentinel
 *  (`FrameTracking#detectionLagMillis`'s doc comment), rendered honestly rather than as a fabricated
 *  "0 ms" reading. */
export function formatDetectionLag(detectionLagMillis: number): string {
  if (!Number.isFinite(detectionLagMillis) || detectionLagMillis <= 0) {
    return '—';
  }
  return `${Math.round(detectionLagMillis)} ms`;
}

// --- Detection status line (docs/plans/active/CV-UX-RESEARCH.md §1.2/§3/§9.2, waves U3+U5) -------
// Replaces the bare on/off toggle with one honest sentence naming the actual outcome. Detection
// only ever runs when BOTH the operator's own `detectionEnabled` choice AND the backend's own
// viewer-demand gate are open (docs/plans/active/CV-DEMAND-PLAN.md §2's two-gate model,
// `StreamPipeline#maybeDetect`) — a toggle reading "on" while nothing is watching is not lying
// about the operator's own intent, but showing only the toggle *would* lie about the outcome; this
// section is what turns "on" into "on, but idle" using `detectionState`, already served on
// `GET .../tracks` (`dto.DetectionState`) and mirrored in `core/api/models.ts`.

/** The measured detection rate as shown to the operator (docs/plans/active/CV-UX-RESEARCH.md §9.2)
 *  — `rate.submittedFps` from `GET .../tracks`, the rate the pipeline actually achieved, replacing
 *  the inference-fps slider's now-false "this is the rate" label (the adaptive rate controller only
 *  ever raises above the slider's own floor, never holds it exactly — docs/plans/active/CV-RATE-
 *  CONTROL-PLAN.md §2). `null` for anything not yet a real reading — no completed detection, an old
 *  server, or a non-finite/non-positive wire value — never a fabricated "0 fps". */
export function formatMeasuredRate(submittedFps: number | undefined): string | null {
  if (submittedFps === undefined || !Number.isFinite(submittedFps) || submittedFps <= 0) {
    return null;
  }
  const rounded = Math.round(submittedFps * 10) / 10;
  return `${Number.isInteger(rounded) ? rounded.toFixed(0) : rounded.toFixed(1)} fps`;
}

/** Which of {@link DetectionStatus}'s kinds is currently true — drives only styling (no color per
 *  the frontend-style skill's "status colours mean state, nothing else": there is no dedicated
 *  "good" hue in this app's token set, so `'running'` renders as a plain quiet reading, the same
 *  posture as the flow strip). */
export type DetectionStatusKind = 'off' | 'waiting-to-start' | 'waiting-for-viewer' | 'running' | 'unknown';

export interface DetectionStatus {
  readonly kind: DetectionStatusKind;
  readonly text: string;
}

/**
 * The one honest status line for the Detect hero (docs/plans/active/CV-UX-RESEARCH.md §3's mockup
 * `status:` line, waves U3+U5). Order of checks matters — each is the more specific truth than the
 * next:
 *
 * 1. `!detectionEnabled` — the operator's own choice, always wins; nothing else matters once
 *    detection is off.
 * 2. `!hasStream` — enabled, but there's nothing to enable yet (no Start pressed this session).
 * 3. `detectionState === 'IDLE_NO_VIEWERS'` — both would-be gates read differently: the operator
 *    said yes, the backend's own viewer-demand gate says no one is watching (docs/plans/active/
 *    CV-DEMAND-PLAN.md) — named explicitly as **not a fault** ("no cost while idle"), matching
 *    `DetectionState`'s own javadoc.
 * 4. `detectionState === 'RUNNING'` — both gates open; reports the *measured* rate
 *    ({@link formatMeasuredRate}) and how many classes are on screen right now
 *    ({@link classesOnScreenCount}), never a fabricated number when nothing has been measured yet.
 * 5. `detectionState === 'OFF'` while the operator's own draft says enabled — a genuine sync gap
 *    (the PATCH hasn't landed yet, or a reload raced a running stream) named honestly rather than
 *    echoed back as confirmed "on".
 * 6. Anything else (`detectionState` absent — an old server, or nothing polled yet this session) —
 *    the 'unknown' kind, never a guess.
 */
export function detectionStatus(
  detectionEnabled: boolean,
  hasStream: boolean,
  detectionState: DetectionState | undefined,
  submittedFps: number | undefined,
  classesOnScreen: number,
): DetectionStatus {
  if (!detectionEnabled) {
    return { kind: 'off', text: 'Off — video only, zero detection cost.' };
  }
  if (!hasStream) {
    return { kind: 'waiting-to-start', text: 'On — will start once a stream is running.' };
  }
  if (detectionState === 'IDLE_NO_VIEWERS') {
    return { kind: 'waiting-for-viewer', text: 'On — idle, waiting for a viewer (no cost while idle).' };
  }
  if (detectionState === 'RUNNING') {
    const rate = formatMeasuredRate(submittedFps);
    const rateText = rate ? `Running at ${rate}` : 'Running — rate not yet measured';
    const classesText =
      classesOnScreen > 0 ? ` · ${classesOnScreen} class${classesOnScreen === 1 ? '' : 'es'} on screen` : '';
    return { kind: 'running', text: `${rateText}${classesText}` };
  }
  if (detectionState === 'OFF') {
    return { kind: 'unknown', text: 'On — waiting for the server to confirm.' };
  }
  return { kind: 'unknown', text: 'On — status not reported by this server yet.' };
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
