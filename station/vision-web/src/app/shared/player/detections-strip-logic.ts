import type { Detection, DetectionResult } from '../../core/api/models';
import { electStickyLabels } from './detection-overlay-logic';

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
 * **Sliding-window aggregation + sticky labels (docs/plans/done/TRACK-IDENTITY-PLAN.md §L3 item 2)**:
 * `stripChips` used to scan only the single newest batch — the strip's own "person ×3" chips blinked in
 * and out at whatever cadence the CV pipeline batches at, and (before item 1) churned between multiple
 * distinct chips for the same physical object every time its raw label flipped. Both are fixed the same
 * way: aggregate over the last {@link STRIP_WINDOW_SECONDS} of `results` instead of `results[0]` alone
 * (`count` becomes *max concurrent* within the window, not a sum — the same object re-detected every
 * batch must not multiply its own count), and group by {@link electStickyLabels}'s election (imported
 * from `detection-overlay-logic.ts` rather than reimplemented — one election, shared by the canvas
 * overlay and this strip, so a chip's label and the box it corresponds to can never quietly disagree)
 * instead of each detection's raw per-batch label.
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

/** The label this detection should be grouped under — the {@link electStickyLabels} entry for a
 *  tracked detection (mirrors `detection-overlay-logic.ts#applyStickyLabels`'s own fallback rule: no
 *  election entry yet degrades to the raw label, never a fabricated guess), the raw label unchanged
 *  for an untracked one (no identity to elect over). */
function displayLabel(detection: Detection, stickyLabels: ReadonlyMap<number, string>): string {
  const trackId = detection.track?.id;
  return trackId === undefined ? detection.label : (stickyLabels.get(trackId) ?? detection.label);
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
 * Election ({@link electStickyLabels}) runs over the **full** `results` given, not just the windowed
 * slice — a track's vote window (item 1's own `STICKY_LABEL_VOTE_WINDOW`, 10 observations) can span
 * more history than this strip's own 5s aggregation window, and the two are deliberately independent
 * concerns (which label to call this track vs. how far back to count it as "currently present").
 *
 * `labelDenyFilter` defaults to `[]` — the honest choice for a caller with no deny-list context of
 * its own (Live page's read-only usage, `shared/player/detections-strip.ts`'s own `streamId`-gated
 * interactivity): nothing renders as hidden rather than guessing.
 */
export function stripChips(
  results: readonly DetectionResult[],
  labelDenyFilter: readonly string[] = [],
  cap: number = STRIP_CHIP_CAP,
  windowSeconds: number = STRIP_WINDOW_SECONDS,
): readonly StripChip[] {
  const stickyLabels = electStickyLabels(results);
  const windowed = windowedResults(results, windowSeconds);

  const maxCountByLabel = new Map<string, number>();
  const recencyOrder: string[] = []; // first batch (newest-first) a label is seen in, per this function's own doc comment

  for (const result of windowed) {
    const countsThisBatch = new Map<string, number>();
    for (const detection of result.detections) {
      const label = displayLabel(detection, stickyLabels);
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
