import type {
  CvModel,
  CvTracker,
  DetectionRate,
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

/**
 * The "Looking for" card/summary's own cost word (docs/plans/active/CV-UX-RESEARCH.md §5) —
 * `'slower'` for an open-vocabulary model, `'fast'` otherwise. Named so the Vision drawer's
 * tier-0 summary row (`cv-control-panel.ts`, docs/plans/active/CV-PANEL-SPLIT-PLAN.md P1 §1.1
 * item 2) and the setup modal's intent cards (`cv-setup-modal.ts`) can never disagree about the
 * word for the same model — both call this instead of restating the ternary twice.
 */
export function modelCostWord(openVocab: boolean): 'fast' | 'slower' {
  return openVocab ? 'slower' : 'fast';
}

/**
 * One plain sentence of *what* a roster entry finds — the intent card's own "what it finds" line
 * (docs/plans/active/CV-PANEL-SPLIT-PLAN.md P2 §1 item 2, CV-UX-RESEARCH.md §5: "one sentence of
 * what it finds plus the honest cost word"). `GET /api/cv/models` (wire-frozen, `dto.CvModelResponse`)
 * carries no description field of its own — `displayName` is a UI label, not a sentence, and the
 * wire's `kind` (`'general'`/`'specialized'`/`'open-vocab'`, the exact three values
 * `CvWiring#cvModelRoster` serves) is the only other roster fact available to key off, so this maps
 * that enum-shaped field to a client-side sentence rather than inventing a per-model-id lookup that
 * would silently go stale against a roster change. **Not** the removed `kind` taxonomy chip
 * (CV-UX-RESEARCH.md §5 — "taxonomy jargon that answers no operator question"): the raw word
 * `'open-vocab'` is never rendered; only this sentence is. Returns `null` for a `kind` this app
 * doesn't recognize — an unknown roster entry gets no fabricated description, not a generic one
 * (the card still renders its `displayName` + cost word regardless).
 */
export function intentCardSentence(kind: string): string | null {
  switch (kind) {
    case 'general':
      return 'Finds people, cars, trucks and other everyday vehicles.';
    case 'specialized':
      return 'Finds military vehicle types — tanks, APCs and similar.';
    case 'open-vocab':
      return 'Finds anything nameable, including buildings — a much wider net.';
    default:
      return null;
  }
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

/** How many of the most-recently-observed labels {@link recentObservedLabels} names, in recency
 *  order — originally the pre-P2 "Seen now" mini-checklist's own display cap
 *  (docs/plans/active/CV-UX-RESEARCH.md §4.1/§7 wave U4). docs/plans/active/CV-PANEL-SPLIT-PLAN.md
 *  P2 §5 folded that mini-checklist into the one full Classes checklist, so this constant now bounds
 *  how many labels {@link sortRecentFirst} promotes to the front, not a separately-rendered list. */
export const SEEN_NOW_CHIP_CAP = 8;

/**
 * The most recently observed distinct labels, capped at {@link SEEN_NOW_CHIP_CAP}, in recency
 * order — as opposed to {@link observedLabels}'s uncapped, unordered-by-recency full history. Feeds
 * {@link sortRecentFirst}'s promotion set for the merged Classes checklist (docs/plans/active/
 * CV-PANEL-SPLIT-PLAN.md P2 §5) — this is the pre-P2 "Seen now" mini-checklist's one function,
 * repurposed for sort order instead of a second rendered list; see that plan section for why.
 * `results` is assumed newest-first (`DetectionsStore.results()`'s documented contract, the same
 * assumption {@link latestFrameTracking} makes) — this scans forward from the newest result so a
 * label first spotted a few frames back but still showing up keeps its place, without needing every
 * result in the buffer to hit the cap.
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
 * The full chip checklist: every currently-selected (`labelFilter`) or currently-hidden
 * (`labelDenyFilter`) label — so toggling one off, or hiding one, never makes it disappear from the
 * list — unioned with every recently-observed label (so the operator can prune what is actually
 * showing up), sorted for a stable, scannable order.
 *
 * The `labelDenyFilter` half matters for the same reason the strip's own candidate set needs it
 * (`shared/player/detections-strip-logic.ts#stripChips`): a denied label is dropped server-side
 * pre-fan-out (docs/plans/active/CV-CLEAN-FEED-PLAN.md D-2), so it can never reappear in `observed`
 * once hidden — without unioning `labelDenyFilter` in directly, a hidden chip would vanish from this
 * checklist the moment its last visible detection aged out, with no way back to un-hide it.
 */
export function chipCandidates(
  labelFilter: readonly string[],
  labelDenyFilter: readonly string[],
  observed: readonly string[],
): readonly string[] {
  const set = new Set([...labelFilter, ...labelDenyFilter, ...observed]);
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

// --- Staged class-filter selection (bulk-edit paths only, wave W5) ------------------------------
// `labelFilter` (the allowlist) is edited only through the bulk paths below — the preset fill
// ({@link applyPreset}), free-text add ({@link addLabel}), and Clear-all — never by a per-chip
// click anymore (docs/plans/active/CV-CLEAN-FEED-PLAN.md D-3: "allowlist stays only as a model-intent
// seed, preset/seeding paths unchanged"). The panel stages these edits in its own `pendingLabels`
// signal (`cv-control-panel.ts`) — `null` mirrors the applied filter (no edit in progress), a
// concrete array (possibly `[]`) is unapplied work waiting on an explicit Submit — and only
// {@link submitLabelFilterButtonText}'s own `submitLabelFilter()` caller actually writes the wire,
// once. This changes nothing about the wire contract: submitting still means "PATCH labelFilter
// as-is", and an empty submission still means "every class" (`PipelineConfig`'s own "empty means
// all" javadoc).
//
// A per-chip click (both tier 0's "Seen now" and Tune's full checklist) is a *different*, unstaged
// action as of wave W5: it writes `labelDenyFilter` immediately, via `toggleLabelDeny`
// (`core/detections/detections-logic.ts`) — see `cv-control-panel.ts#toggleChip`. The checklist's own
// "checked" state (`isLabelChecked` below, combined with the deny-list at the call site) reflects the
// honest combined truth of both gates, even though only one of them is ever writable from a chip
// click.

/** The list any staged edit builds on top of: the staged selection itself once one exists, or the
 *  currently applied filter on the very first edit this panel session. Every staging entry point —
 *  the preset fill ({@link applyPreset}), free-text add ({@link addLabel}), and the checked/
 *  selected-first-sort reads that decide what the checklist looks like — goes through this one
 *  function first, so "seed once from what's really applied, then keep building on the stage" can
 *  never drift between call sites. */
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
 * Reorders `candidates` so the most-recently-observed labels sort first, in their own recency
 * order, followed by the rest in whatever order `candidates` already had (`chipCandidates`'
 * alphabetical order, ordinarily) — the single merged Classes checklist's own sort
 * (docs/plans/active/CV-PANEL-SPLIT-PLAN.md P2 §5: "the full checklist … with observed-recently
 * labels sorted first"). `recentLabels` is meant to be {@link recentObservedLabels}'s own output —
 * this is what survives of the pre-P2 "Seen now" mini-checklist once it's folded into the one full
 * list instead of existing as a second, duplicate apparatus: recency was that section's one real
 * value (an operator pruning "what's actually showing up right now"), so it becomes a sort order
 * here rather than a second rendered list.
 *
 * A deliberate no-op (identical order) when `recentLabels` is empty (nothing observed yet) — there
 * is nothing to promote, so `candidates`' own order (alphabetical) is already the more scannable
 * one for "browse everything".
 */
export function sortRecentFirst(
  candidates: readonly string[],
  recentLabels: readonly string[],
): readonly string[] {
  if (recentLabels.length === 0) {
    return candidates;
  }
  const candidateSet = new Set(candidates);
  const recent = recentLabels.filter((label) => candidateSet.has(label));
  const recentSet = new Set(recent);
  const rest = candidates.filter((candidate) => !recentSet.has(candidate));
  return [...recent, ...rest];
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
 * The hot-knob patch body — confidence/fps/labelFilter/labelDenyFilter/detectionEnabled, **never**
 * `model` (a model change is always its own separate call, {@link buildModelChangePatch}, so a
 * slider drag or a chip toggle can never accidentally trigger a re-arm). Sent on every debounced
 * hot-knob edit while a stream is running — includes `labelDenyFilter` since wave W5 (docs/plans/
 * active/CV-CLEAN-FEED-PLAN.md D-2) so a per-chip hide/unhide click reaches the wire the same way
 * every other hot knob already does.
 */
export function buildHotKnobPatch(settings: PipelineSettings): UpdateStreamConfigRequest {
  return {
    confidenceThreshold: settings.confidenceThreshold,
    inferenceFps: settings.inferenceFps,
    labelFilter: settings.labelFilter,
    labelDenyFilter: settings.labelDenyFilter,
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
 * **Never claims the class filter steers the model or saves CPU** (docs/plans/active/
 * CV-UX-RESEARCH.md §1.2/§4.3): the closed-set branch names the two knobs that actually govern CPU
 * (inference rate, detection on/off) without also restating `HIDDEN_CLASS_TRUTH`
 * (`core/detections/detections-logic.ts`) — that sentence has exactly one home per surface (the
 * Classes section), and duplicating it here would violate that (docs/plans/active/
 * CV-PANEL-SPLIT-PLAN.md P2 §2). One line each, deliberately short (docs/plans/active/
 * CV-PANEL-SPLIT-PLAN.md P2 §1 item 2 — the open-vocab branch used to run two sentences; a single
 * intent card has no room for a paragraph).
 */
export function perfHint(openVocabSelected: boolean): string {
  return openVocabSelected
    ? 'Open-vocabulary — materially slower on CPU; lower the rate or turn detection off to reclaim it.'
    : 'Inference rate and detection on/off are the CPU-budget controls — the model still computes every class every frame regardless of the filter.';
}

// --- Honest, repeated-verbatim copy (docs/plans/active/CV-UX-RESEARCH.md §4.3, CV-CLEAN-FEED-PLAN.md
// D-3 wave W5) -------------------------------------------------------------------------------------
// HIDDEN_CLASS_TRUTH lives in `core/detections/detections-logic.ts`, not here — both the setup
// modal's own Classes section and the detections strip's one-click hide (`shared/player/
// detections-strip.ts`, in `shared/`, which cannot import from `features/fly/`) need the identical
// sentence, so it lives in the one neutral home both can reach. `perfHint` above deliberately does
// NOT import or fold it in (docs/plans/active/CV-PANEL-SPLIT-PLAN.md P2 §2/§4) — the modal's Classes
// section is the sentence's one home per surface; a second copy under the fps slider would be the
// exact duplication that instruction forbids.
//
// The old `FIRST_HIDE_HINT` (a one-time warning that clicking a chip built an allowlist) is gone
// along with the allowlist-complement toggle it explained: a chip click now writes the deny-list
// (`toggleLabelDeny`, `core/detections/detections-logic.ts`), which has no "first click surprises
// you" edge case at all — an empty deny-list unambiguously means "deny nothing", so there is nothing
// to warn about before the first click the way there was for `labelFilter`'s "empty means all"
// allowlist.

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

// --- Detection status line (docs/plans/active/CV-PANEL-SPLIT-PLAN.md P2 §1, CV-UX-RESEARCH.md
// §1.2/§3/§9.2) -------------------------------------------------------------------------------
// Replaces the bare on/off toggle with one honest sentence naming the actual outcome. Detection
// only ever runs when BOTH the operator's own `detectionEnabled` choice AND the backend's own
// viewer-demand gate are open (docs/plans/active/CV-DEMAND-PLAN.md §2's two-gate model,
// `StreamPipeline#maybeDetect`) — a toggle reading "on" while nothing is watching is not lying
// about the operator's own intent, but showing only the toggle *would* lie about the outcome; this
// section is what turns "on" into "on, but idle" using `detectionState`, already served on
// `GET .../tracks` (`dto.DetectionState`) and mirrored in `core/api/models.ts`.

/**
 * The measured detection rate as shown to the operator (docs/plans/active/CV-PANEL-SPLIT-PLAN.md P2
 * §1 item 1's exact mockup wording, `"9.9/s measured"`) — replacing the inference-fps slider's now-
 * false "this is the rate" label (the adaptive rate controller only ever raises above the slider's
 * own floor, never holds it exactly — docs/plans/active/CV-RATE-CONTROL-PLAN.md §2). Takes a
 * **known-positive, already-measured** `submittedFps` (`DetectionRateResponse#submittedFps` off
 * `GET .../tracks`) — {@link detectionStatus} is the one caller, and it only reaches this function
 * once it has already told "never measured" and "measured zero" apart (see that function's own doc
 * comment); this formatter has no "what if there's no reading yet" branch to get honest or
 * dishonest, by construction.
 */
export function formatMeasuredRate(submittedFps: number): string {
  const rounded = Math.round(submittedFps * 10) / 10;
  const digits = Number.isInteger(rounded) ? rounded.toFixed(0) : rounded.toFixed(1);
  return `${digits}/s measured`;
}

/** Which of {@link DetectionStatus}'s kinds is currently true — drives only styling (no color per
 *  the frontend-style skill's "status colours mean state, nothing else": there is no dedicated
 *  "good" hue in this app's token set, so `'running'` renders as a plain quiet reading, the same
 *  posture as the flow strip). `'stalled'` is new in P2 — see {@link detectionStatus}'s own doc
 *  comment for what distinguishes it from `'running'`. */
export type DetectionStatusKind =
  | 'off'
  | 'waiting-to-start'
  | 'waiting-for-viewer'
  | 'running'
  | 'stalled'
  | 'unknown';

export interface DetectionStatus {
  readonly kind: DetectionStatusKind;
  readonly text: string;
}

/**
 * The one honest status line for the Detect hero (docs/plans/active/CV-PANEL-SPLIT-PLAN.md P2 §1
 * item 1). Order of checks matters — each is the more specific truth than the next:
 *
 * 1. `!detectionEnabled` — the operator's own choice, always wins; nothing else matters once
 *    detection is off. Exact wording is the plan's own: `"Off — zero CPU. Video unaffected."`
 * 2. `!hasStream` — enabled, but there's nothing to enable yet (no Start pressed this session).
 * 3. `detectionState === 'IDLE_NO_VIEWERS'` — both would-be gates read differently: the operator
 *    said yes, the backend's own viewer-demand gate says no one is watching (docs/plans/active/
 *    CV-DEMAND-PLAN.md) — named explicitly as **not a fault** ("no cost while idle"), matching
 *    `DetectionState`'s own javadoc.
 * 4. `detectionState === 'RUNNING'` — both gates open, but "running" says nothing about whether the
 *    detector has actually produced anything lately (`DetectionState`'s own javadoc: it "reports
 *    gating, never health" — a stalled cv-service still reads `RUNNING`). Three sub-cases, read off
 *    `rate` (`DetectionRateResponse`, `GET .../tracks`):
 *      a. `rate === undefined` — no sample has ever completed this session (absent "before the
 *         first sample", `StreamTracksResponse`'s own javadoc) — `"On — rate not yet measured."`,
 *         never a fabricated number.
 *      b. `rate.submittedFps <= 0` — a sample **has** completed before, but the trailing
 *         `rate.windowSeconds`-second window submitted nothing to the detector at all (`due()` is a
 *         real deadline count in `DetectionRate.java`, so this is not "not measured yet", it is
 *         "measured zero") — named plainly: `"On — no detector passes in the last Ns."` This is the
 *         honest field to read for a stalled detector per docs/plans/active/CV-PANEL-SPLIT-PLAN.md
 *         P2 §1 item 1, distinct from case (a) precisely because `rate` is present.
 *      c. Otherwise — the plan's own mockup line: {@link formatMeasuredRate} plus how many classes
 *         are on screen right now ({@link classesOnScreenCount}), joined with `" · "`; the classes
 *         clause is omitted entirely when 0 (never `"0 classes on screen"`).
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
  rate: DetectionRate | undefined,
  classesOnScreen: number,
): DetectionStatus {
  if (!detectionEnabled) {
    return { kind: 'off', text: 'Off — zero CPU. Video unaffected.' };
  }
  if (!hasStream) {
    return { kind: 'waiting-to-start', text: 'On — will start once a stream is running.' };
  }
  if (detectionState === 'IDLE_NO_VIEWERS') {
    return { kind: 'waiting-for-viewer', text: 'On — idle, waiting for a viewer (no cost while idle).' };
  }
  if (detectionState === 'RUNNING') {
    if (rate === undefined) {
      return { kind: 'running', text: 'On — rate not yet measured.' };
    }
    if (rate.submittedFps <= 0) {
      return { kind: 'stalled', text: `On — no detector passes in the last ${rate.windowSeconds}s.` };
    }
    const classesText =
      classesOnScreen > 0 ? ` · ${classesOnScreen} class${classesOnScreen === 1 ? '' : 'es'} on screen` : '';
    return { kind: 'running', text: `${formatMeasuredRate(rate.submittedFps)}${classesText}` };
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

/** How long a hot-knob edit (confidence/fps/labelFilter/labelDenyFilter) waits for further edits
 *  before actually sending the PATCH — coalesces a fast slider drag or a burst of chip clicks into
 *  one request instead of one per input event. Lives here (not a local `const` in `cv-setup-
 *  modal.ts`, its one caller as of docs/plans/active/CV-PANEL-SPLIT-PLAN.md P1) purely so it can't
 *  drift from `debounce`'s own doc comment below, which names the exact behavior this constant
 *  tunes. */
export const HOT_KNOB_DEBOUNCE_MS = 400;

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
