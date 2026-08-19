import type {
  AfterActionManifest,
  AfterActionPart,
  AfterActionPartState,
  AfterActionPartStatus,
} from '../api/models';

/**
 * Pure view-model logic behind `features/replay/after-action-panel.ts`
 * (docs/plans/active/AFTER-ACTION-PLAN.md, wave W2) — no Angular imports, fully unit-tested. Turns
 * the frozen §3.1 manifest into exactly what the panel renders: the six parts in their canonical
 * order, each with a plain-language state/tone, and the caveat list recomputed locally rather than
 * trusted verbatim off the wire (see {@link deriveCaveats}'s own doc comment for why).
 *
 * **The whole point of this wave, encoded here rather than left to template styling**: `TRUNCATED`
 * and `FORBIDDEN` must read as calm statements of fact, not red errors. {@link partTone} is
 * deliberately two-valued (`'ok' | 'neutral'`) — `PRESENT` is the only state that earns the positive
 * tone; `ABSENT`/`TRUNCATED`/`FORBIDDEN` all render with the identical neutral tone. The actual
 * distinction between those three lives in {@link partStateLabel}'s words, never in color.
 */

/** The six parts, in the wire contract's own fixed order (§3.1) — this app's canonical render order regardless of what order the wire array itself arrives in. */
export const AFTER_ACTION_PART_ORDER: readonly AfterActionPart[] = [
  'telemetry',
  'detections',
  'marks',
  'recording',
  'passport',
  'audit',
];

const PART_LABELS: Record<AfterActionPart, string> = {
  telemetry: 'Telemetry',
  detections: 'Detections',
  marks: 'Marks',
  recording: 'Recording',
  passport: 'Flight passport',
  audit: 'Audit trail',
};

/** Plain-language state words — never the bare wire enum, and never a severity word for the two states this wave exists to keep calm about (`TRUNCATED`/`FORBIDDEN`). */
const STATE_LABELS: Record<AfterActionPartState, string> = {
  PRESENT: 'Included',
  ABSENT: 'Not available',
  TRUNCATED: 'Thinned',
  FORBIDDEN: 'Not visible to you',
};

export type AfterActionPartTone = 'ok' | 'neutral';

export function partLabel(part: AfterActionPart): string {
  return PART_LABELS[part];
}

export function partStateLabel(state: AfterActionPartState): string {
  return STATE_LABELS[state];
}

/** `PRESENT` is the only "good news" tone; every other state reads identically neutral — see this file's own top doc comment. */
export function partTone(state: AfterActionPartState): AfterActionPartTone {
  return state === 'PRESENT' ? 'ok' : 'neutral';
}

/** `PRESENT`/`TRUNCATED` carry a real count worth showing; `ABSENT`/`FORBIDDEN`'s `count` is always `0` on the wire (§3.1) and would read as a false "0 detections" rather than the true "not available" if rendered. */
export function showPartCount(state: AfterActionPartState): boolean {
  return state === 'PRESENT' || state === 'TRUNCATED';
}

/** One part row, fully presentation-ready — everything the panel's template reads, with no lookups of its own. */
export interface AfterActionPartRow {
  readonly part: AfterActionPart;
  readonly label: string;
  readonly state: AfterActionPartState;
  readonly stateLabel: string;
  readonly tone: AfterActionPartTone;
  readonly count: number;
  readonly showCount: boolean;
  /** Passed through verbatim. `null` only when `state === 'PRESENT'` and there's nothing to qualify (§3.1) — rule 5: render no explanatory text at all in that case, never invented reassuring copy. */
  readonly note: string | null;
}

function toRow(status: AfterActionPartStatus): AfterActionPartRow {
  return {
    part: status.part,
    label: partLabel(status.part),
    state: status.state,
    stateLabel: partStateLabel(status.state),
    tone: partTone(status.state),
    count: status.count,
    showCount: showPartCount(status.state),
    note: status.note,
  };
}

/**
 * Reorders `parts` into the canonical §3.1 order regardless of what order the wire array actually
 * arrives in — cheap defensive insurance, not a trust exercise, against a future backend regression
 * silently reordering the panel. A part missing from the wire array entirely is simply not rendered
 * — never synthesized as a fabricated row this app has no actual evidence for (this should never
 * happen per the contract's "always all six" rule, but a missing entry degrading to "quietly absent
 * from the list" is still more honest than inventing a row for it).
 */
export function buildPartRows(parts: readonly AfterActionPartStatus[]): AfterActionPartRow[] {
  const byPart = new Map(parts.map((status) => [status.part, status] as const));
  return AFTER_ACTION_PART_ORDER.filter((part) => byPart.has(part)).map((part) => toRow(byPart.get(part)!));
}

/**
 * Recomputes §3.1's caveat rule locally — every non-null `note`, verbatim, in canonical part order —
 * rather than trusting `manifest.caveats` off the wire. A backend that under-reports its own summary
 * therefore cannot make this app under-report; each part's `note` also renders on its own row
 * regardless (D3), so a caveat missing from this list is still never silently absent.
 *
 * **State is deliberately not filtered on.** A `PRESENT` part may carry a note, and marks is why:
 * "a mark is not bound to a flight" qualifies every package that has marks. Suppressing it because
 * marks are present would hide the one epistemic caveat that is always true — an approximation is
 * not an absence, and this row exists to keep those apart.
 *
 * This wave found §3.1's worked example contradicting its own prose (it omitted audit's `FORBIDDEN`
 * note and paraphrased telemetry's). The plan was corrected 2026-08-19; the rule above is the
 * authority, not any JSON illustration.
 */
export function deriveCaveats(parts: readonly AfterActionPartStatus[]): string[] {
  const byPart = new Map(parts.map((status) => [status.part, status] as const));
  const caveats: string[] = [];
  for (const part of AFTER_ACTION_PART_ORDER) {
    const note = byPart.get(part)?.note;
    if (note != null) {
      caveats.push(note);
    }
  }
  return caveats;
}

/**
 * The honest, UI-authored sentence for a still-running flight (§3.1: `open: true`/`endedAt: null`
 * together). Deliberately **not** folded into `deriveCaveats` — that rule draws from part `note`s,
 * and "this flight hasn't ended yet" is a fact about the *manifest*, not about any one part, so it
 * would never legitimately appear there. Stated here instead of leaving a referee to infer "still
 * running" from a blank `endedAt` alone.
 */
export function openFlightNotice(open: boolean): string | null {
  return open ? 'This flight is still in progress — the package covers everything recorded up to now.' : null;
}

/** The manifest → view-model transform `AfterActionPanel` renders directly. */
export interface AfterActionView {
  readonly parts: readonly AfterActionPartRow[];
  readonly caveats: readonly string[];
  readonly complete: boolean;
  readonly openNotice: string | null;
}

export function buildAfterActionView(manifest: AfterActionManifest): AfterActionView {
  return {
    parts: buildPartRows(manifest.parts),
    caveats: deriveCaveats(manifest.parts),
    complete: manifest.complete,
    openNotice: openFlightNotice(manifest.open),
  };
}
