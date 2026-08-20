import type { DetectionResult } from '../../core/api/models';

/**
 * Pure derivation behind `DetectionsStrip` (docs/plans/active/CV-CLEAN-FEED-PLAN.md D-3, wave W5) —
 * split out so the strip's own "class-level remote control" candidate set (research §3.5) is
 * unit-testable without Angular. Deliberately a **separate** derivation from
 * `core/detections/detections-logic.ts#deriveChips`, not a same-signature extension of it: that
 * function is used by every non-interactive reader of `DetectionsStore.chips` (Live page included)
 * and only ever scans `results`, which — being server-filtered — can *never* contain a denied label
 * (`StreamPipeline`'s single drop site runs pre-fan-out, docs/plans/active/CV-CLEAN-FEED-PLAN.md D-2).
 * A "hidden, click to un-hide" chip therefore has to be reconstructed by unioning in the operator's
 * own `labelDenyFilter` from outside `results` entirely — a concern `deriveChips`'s existing callers
 * neither need nor expect.
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

/**
 * The strip's candidate set, recency-ordered and capped at `cap`: every distinct label in the most
 * recent batch it appears in (paired with how many detections in *that* batch carry it — research
 * §3.5's "person ×3"), unioned with every currently-denied label that can no longer appear in
 * `results` at all (dropped server-side) so an operator can still find and un-hide it. Observed
 * labels fill the cap first (newest-first, matching `results`' own documented order); denied-only
 * labels are appended after, still subject to the same cap.
 *
 * `labelDenyFilter` defaults to `[]` — the honest choice for a caller with no deny-list context of
 * its own (Live page's read-only usage, `shared/player/detections-strip.ts`'s own `streamId`-gated
 * interactivity): nothing renders as hidden rather than guessing.
 */
export function stripChips(
  results: readonly DetectionResult[],
  labelDenyFilter: readonly string[] = [],
  cap: number = STRIP_CHIP_CAP,
): readonly StripChip[] {
  const counts = new Map<string, number>(); // insertion order = recency order (first-seen batch wins)
  outer: for (const result of results) {
    for (const detection of result.detections) {
      if (counts.has(detection.label)) {
        continue;
      }
      const count = result.detections.filter((d) => d.label === detection.label).length;
      counts.set(detection.label, count);
      if (counts.size >= cap) {
        break outer;
      }
    }
  }

  const chips: StripChip[] = [...counts.entries()].map(([label, count]) => ({
    label,
    count,
    hidden: labelDenyFilter.includes(label),
  }));

  for (const label of labelDenyFilter) {
    if (chips.length >= cap) {
      break;
    }
    if (counts.has(label)) {
      continue; // already included above, with its real observed count
    }
    chips.push({ label, count: 0, hidden: true });
  }

  return chips;
}
