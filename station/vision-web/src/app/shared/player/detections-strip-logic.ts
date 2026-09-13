import type { Detection, DetectionResult, WorldObject } from '../../core/api/models';

/**
 * Pure derivation behind `DetectionsStrip` (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-3, wave W5) —
 * split out so the strip's own "class-level remote control" candidate set (research §3.5) is
 * unit-testable without Angular. Deliberately a **separate** derivation from
 * `core/detections/detections-logic.ts#deriveChips`, not a same-signature extension of it: that
 * function is used by every non-interactive reader of `DetectionsStore.chips` (Live page included)
 * and only ever scans `results`, which — being server-filtered — can *never* contain a denied label
 * (`StreamPipeline`'s single drop site runs pre-fan-out, docs/plans/done/CV-CLEAN-FEED-PLAN.md D-2).
 * A "hidden, click to un-hide" chip therefore has to be reconstructed by unioning in the operator's
 * own `labelDenyFilter` from outside `results` entirely — a concern `deriveChips`'s existing callers
 * neither need nor expect.
 *
 * **Sliding-window aggregation (docs/plans/done/TRACK-IDENTITY-PLAN.md §L3 item 2)**: `stripChips`
 * used to scan only the single newest batch — the strip's own "person ×3" chips blinked in and out at
 * whatever cadence the CV pipeline batches at. Fixed by aggregating over the last
 * {@link STRIP_WINDOW_SECONDS} of `results` instead of `results[0]` alone (`count` becomes *max
 * concurrent* within the window, not a sum — the same object re-detected every batch must not multiply
 * its own count).
 *
 * **Wire label grouping, wave W3.2** (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6): a tracked
 * detection groups under its {@link WorldObject}'s own `state.identity.label` (via
 * `detection-overlay-logic.ts#worldObjectsByTrackId` — the identical lookup the canvas overlay itself
 * now reads, so a chip's label and the box it corresponds to can never quietly disagree), replacing
 * the client-side election this file used to import for the same purpose. This is a **per-current-
 * track** lookup, not a historical replay: a track's label within the aggregation window is whatever
 * the wire says *right now* for that track id, not what it was when each historical batch in the
 * window was actually captured — there is no per-batch historical world-object snapshot retained to
 * do better than that. A track with no world object yet (or on a caller that never threads one in)
 * falls back to its raw per-batch label, unchanged.
 */

/** One chip the strip renders: an aggregated label with its current-batch count and whether it is
 *  presently denied (and therefore no longer produces detections at all, `count` frozen at `0`). */
export interface StripChip {
  readonly label: string;
  readonly count: number;
  readonly hidden: boolean;
}

/** How many chips the strip shows at most — mirrors `core/detections/detections-logic.ts#MAX_DETECTION_CHIPS`
 *  (the same "~8" the research doc's own "capped ~8" calls for), kept as its own named constant
 *  since this is a distinct derivation, not a shared one. */
export const STRIP_CHIP_CAP = 8;

/** How far back the strip aggregates, in seconds off the newest batch's own `capturedAt` (not wall-
 *  clock `Date.now()` — keeps this pure and deterministic in tests) — docs/plans/active/
 *  TRACK-IDENTITY-PLAN.md §L3 item 2's own default. Long enough that one CV outage/scheduling hiccup
 *  doesn't drop a chip, short enough that a class that has genuinely left frame still clears within a
 *  few seconds rather than lingering. */
export const STRIP_WINDOW_SECONDS = 5;

/** `results` filtered to the last `windowSeconds` off the newest entry's own `capturedAt` — `results`
 *  is already newest-first (`VisionApi.streamDetections`'s own contract), so `results[0]` is "now" from
 *  the detection feed's own perspective. */
function windowedResults(results: readonly DetectionResult[], windowSeconds: number): readonly DetectionResult[] {
  if (results.length === 0) {
    return results;
  }
  const cutoffMs = Date.parse(results[0].capturedAt) - windowSeconds * 1000;
  return results.filter((result) => Date.parse(result.capturedAt) >= cutoffMs);
}

/** The label this detection should be grouped under — the matching {@link WorldObject}'s own
 *  `state.identity.label` for a tracked detection (mirrors
 *  `detection-overlay-logic.ts#resolveDisplayDetections`'s own fallback rule: no world object, or one
 *  with no elected identity yet, degrades to the raw label, never a fabricated guess), the raw label
 *  unchanged for an untracked one (no world object to look up at all). */
function displayLabel(detection: Detection, worldObjectsById: ReadonlyMap<number, WorldObject>): string {
  const trackId = detection.track?.id;
  if (trackId === undefined) {
    return detection.label;
  }
  return worldObjectsById.get(trackId)?.state.identity?.label ?? detection.label;
}

/**
 * The strip's candidate set, recency-ordered and capped at `cap`: every distinct display label seen
 * within the last `windowSeconds` of `results` (paired with the *max concurrent* count for that label
 * across the window — research §3.5's "person ×3", now stable across a multi-batch window instead of
 * one instant, docs/plans/done/TRACK-IDENTITY-PLAN.md §L3 item 2), unioned with every currently-
 * denied label that can no longer appear in `results` at all (dropped server-side) so an operator can
 * still find and un-hide it. Observed labels fill the cap first (newest-first, matching `results`' own
 * documented order — the order its label was *first* encountered scanning the window newest-to-oldest);
 * denied-only labels are appended after, still subject to the same cap.
 *
 * `worldObjectsById` defaults to an empty map — the honest choice for a caller with no per-asset
 * `DetectionsStore.worldObjects()` to thread in (or one whose asset hasn't produced any yet): every
 * detection's raw label is used, unchanged, exactly as this file behaved before wave W3.2.
 *
 * `labelDenyFilter` defaults to `[]` — the honest choice for a caller with no deny-list context of
 * its own (Live page's read-only usage, `shared/player/detections-strip.ts`'s own `streamId`-gated
 * interactivity): nothing renders as hidden rather than guessing.
 */
export function stripChips(
  results: readonly DetectionResult[],
  worldObjectsById: ReadonlyMap<number, WorldObject> = new Map(),
  labelDenyFilter: readonly string[] = [],
  cap: number = STRIP_CHIP_CAP,
  windowSeconds: number = STRIP_WINDOW_SECONDS,
): readonly StripChip[] {
  const windowed = windowedResults(results, windowSeconds);

  const maxCountByLabel = new Map<string, number>();
  const recencyOrder: string[] = []; // first batch (newest-first) a label is seen in, per this function's own doc comment

  for (const result of windowed) {
    const countsThisBatch = new Map<string, number>();
    for (const detection of result.detections) {
      const label = displayLabel(detection, worldObjectsById);
      countsThisBatch.set(label, (countsThisBatch.get(label) ?? 0) + 1);
    }
    for (const [label, count] of countsThisBatch) {
      if (!maxCountByLabel.has(label)) {
        recencyOrder.push(label);
      }
      maxCountByLabel.set(label, Math.max(maxCountByLabel.get(label) ?? 0, count));
    }
  }

  const chips: StripChip[] = [];
  for (const label of recencyOrder) {
    if (chips.length >= cap) {
      break;
    }
    chips.push({ label, count: maxCountByLabel.get(label) ?? 0, hidden: labelDenyFilter.includes(label) });
  }

  for (const label of labelDenyFilter) {
    if (chips.length >= cap) {
      break;
    }
    if (maxCountByLabel.has(label)) {
      continue; // already included above, with its real observed count
    }
    chips.push({ label, count: 0, hidden: true });
  }

  return chips;
}
