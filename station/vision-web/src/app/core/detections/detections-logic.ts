import type { DetectionResult } from '../api/models';

/**
 * Pure derivations behind `DetectionsStore` (docs/plans/done/MVP1-PLAN.md §C8 bullet 4), split out so the
 * chip/status logic can be unit-tested without touching HTTP or timers — mirrors
 * `core/telemetry/telemetry-logic.ts`'s split of pure derivation from the injectable that drives it.
 */

/** One derived chip for the Live page's detections strip: a distinct label with a confidence. */
export interface DetectionChip {
  readonly label: string;
  readonly confidence: number;
}

/** How many distinct-label chips the strip shows at most. */
export const MAX_DETECTION_CHIPS = 8;

/**
 * The last `maxChips` distinct labels seen across `results` (newest first, as `VisionApi`
 * returns them), each paired with the most recent confidence recorded for that label.
 *
 * A label that keeps reappearing (the common case — the same person tracked across several
 * frames) only ever produces one chip, from whichever result carried it most recently, rather
 * than one chip per frame.
 */
export function deriveChips(
  results: readonly DetectionResult[],
  maxChips = MAX_DETECTION_CHIPS,
): readonly DetectionChip[] {
  const chips: DetectionChip[] = [];
  const seen = new Set<string>();
  for (const result of results) {
    for (const detection of result.detections) {
      if (seen.has(detection.label)) {
        continue;
      }
      seen.add(detection.label);
      chips.push({ label: detection.label, confidence: detection.confidence });
      if (chips.length >= maxChips) {
        return chips;
      }
    }
  }
  return chips;
}

/** CV status the Live page's dot renders — always derived from result recency, never a new backend state. */
export type CvStatus = 'on' | 'off';

/** A latest result older than this many seconds no longer counts as "CV is seeing something". */
export const CV_STATUS_FRESH_SECONDS = 5;

/**
 * The one age rule both {@link cvStatus} (the status dot) and {@link freshResults} (what actually gets
 * drawn) apply to a result's `capturedAt` — pulled out so the two can't independently drift apart and
 * disagree about what "fresh" means.
 */
function isFresh(capturedAt: string, nowMs: number, freshSeconds: number): boolean {
  const ageSeconds = Math.max(0, (nowMs - Date.parse(capturedAt)) / 1000);
  return ageSeconds <= freshSeconds;
}

/**
 * `'on'` (green) when the most recent result's `capturedAt` is within `CV_STATUS_FRESH_SECONDS`;
 * `'off'` (grey) otherwise — including when no result has ever arrived, and when the endpoint has
 * been consistently empty/erroring (both look identical from here: neither has a fresh
 * `capturedAt` to point to, which is exactly the point — this derives from data already in hand
 * rather than inventing a distinct "error" state).
 */
export function cvStatus(latestCapturedAt: string | undefined, nowMs: number): CvStatus {
  if (latestCapturedAt === undefined) {
    return 'off';
  }
  return isFresh(latestCapturedAt, nowMs, CV_STATUS_FRESH_SECONDS) ? 'on' : 'off';
}

/**
 * Drops any result whose `capturedAt` has aged out of the same window {@link cvStatus} uses for the
 * dot — so a detection that stopped arriving (detection switched off, no viewers, a CV outage,
 * cv-service crashed) stops being drawn/counted instead of lingering at its last-known value forever.
 *
 * Applies per-result, not just to the latest: `DetectionsStore.results()` retains a short
 * newest-first history (up to `DETECTIONS_LIMIT`), and every consumer of that history — the box
 * overlay, the chip strip, any count — must agree with the status dot about what's still "seeing
 * something", not just the single newest entry. Order is preserved (still newest-first).
 *
 * Takes `freshSeconds` as a parameter defaulting to `CV_STATUS_FRESH_SECONDS` rather than a second,
 * independent constant, so the results-list window and the status-dot window can't be tuned apart by
 * accident (CLAUDE.md rule 1 — no unrelated magic numbers).
 */
export function freshResults(
  results: readonly DetectionResult[],
  nowMs: number,
  freshSeconds = CV_STATUS_FRESH_SECONDS,
): readonly DetectionResult[] {
  return results.filter((result) => isFresh(result.capturedAt, nowMs, freshSeconds));
}

// --- Class deny-list (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-2/D-3, wave W5) -------------------------------
//
// Lives here — not in `features/fly/cv-control-panel-logic.ts` where the deny-toggle used to be a
// (now-deleted) allowlist-complement hack — because both the CV control panel's own class-chip
// checklist (`features/fly/cv-control-panel.ts`) *and* the detections strip's one-click hide
// (`shared/player/detections-strip.ts`, `shared/`, which cannot import from `features/fly/`) need the
// identical array math. One function means the two surfaces can never quietly disagree about what
// "hidden" means.

/**
 * The one honesty disclosure shown wherever an operator can hide a class — stated once, not
 * re-litigated per surface. `PipelineConfig#denyFilter` is enforced once, pre-fan-out
 * (`docs/plans/done/CV-CLEAN-FEED-PLAN.md` D-2/wave W1): screen, alerts and recording all lose a
 * denied label identically, and the model itself never stops scanning for it — hiding is a display
 * choice, not a performance one.
 */
export const HIDDEN_CLASS_TRUTH =
  'Hidden classes are dropped everywhere — screen, alerts, recording. The model still scans for everything.';

/** Whether `label` is currently in the operator's own deny-list — the strip/panel class-chip
 *  checklist's own "hidden" state. */
export function isLabelDenied(labelDenyFilter: readonly string[], label: string): boolean {
  return labelDenyFilter.includes(label);
}

/**
 * Toggles one label's deny-list membership — simple, symmetric add/remove. Unlike `labelFilter`'s
 * own "empty means allow everything" allowlist trap (which used to force a first-hide click to
 * enumerate every *other* observed label as the real allowlist), an empty deny-list has an
 * unambiguous meaning — "deny nothing" — so there is no complement to enumerate and no first-click
 * special case at all.
 */
export function toggleLabelDeny(labelDenyFilter: readonly string[], label: string): readonly string[] {
  return isLabelDenied(labelDenyFilter, label)
    ? labelDenyFilter.filter((entry) => entry !== label)
    : [...labelDenyFilter, label];
}
