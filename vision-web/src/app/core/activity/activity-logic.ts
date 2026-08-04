import type { AuditEntry } from '../api/models';

/**
 * Pure, Angular-free logic behind `/activity`'s day-grouped list (docs/NAV-IA-REDESIGN-PLAN.md §2.4,
 * docs/design/09-activity.md — task 2, "Day-group the rows… the grouping in a pure function under
 * `core/` with its own unit tests — it is date logic, not view code"). New file rather than folded
 * into `core/org/org-logic.ts` — that file's own `formatActivity`/`ActivityView` pairing already has
 * exactly one consumer (`ActivityFacade`) and needed no change for this; this is additional
 * derivation over the same `AuditEntry[]`, composed alongside it in the facade rather than grown into
 * an existing, already-tested export.
 */

/** One calendar-day bucket, newest day first. Entries stay in the backend's own newest-first order within a bucket — never re-sorted here. */
export interface ActivityDayGroup {
  /** `'Today'` / `'Yesterday'` / a full date (e.g. `'August 2, 2026'`) — see {@link dayLabel}. */
  readonly label: string;
  readonly entries: readonly AuditEntry[];
}

/**
 * `'Today'`/`'Yesterday'`/a locale-formatted date for the calendar day `atIso` falls in, relative to
 * `nowMs` — **local time**, not UTC: an audit log is read by a human sitting somewhere, and "today"
 * means their own calendar day, not the server's. `nowMs` is a parameter, not a fresh `Date.now()`
 * read inside — the same determinism convention every time-formatting helper in this app follows
 * (e.g. `core/telemetry/flight-state-logic.ts#derivePreflight`'s own doc comment names it explicitly).
 */
export function dayLabel(atIso: string, nowMs: number): string {
  const at = new Date(atIso);
  const now = new Date(nowMs);
  const atDay = Date.UTC(at.getFullYear(), at.getMonth(), at.getDate());
  const nowDay = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
  const diffDays = Math.round((nowDay - atDay) / 86_400_000);
  if (diffDays === 0) {
    return 'Today';
  }
  if (diffDays === 1) {
    return 'Yesterday';
  }
  return at.toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' });
}

/**
 * Buckets `entries` (assumed already newest-first, `ActivityController`'s own contract) into
 * consecutive same-day groups — a linear scan over the already-ordered list, not a `groupBy` +
 * re-sort, so two entries that share a day never move relative to each other or to a same-day
 * neighbor that happens to sit non-adjacently for some other reason. A day with zero entries never
 * appears — there's nothing to head a heading for.
 */
export function groupActivityByDay(entries: readonly AuditEntry[], nowMs: number): readonly ActivityDayGroup[] {
  const groups: { label: string; entries: AuditEntry[] }[] = [];
  for (const entry of entries) {
    const label = dayLabel(entry.occurredAt, nowMs);
    const current = groups.at(-1);
    if (current && current.label === label) {
      current.entries.push(entry);
    } else {
      groups.push({ label, entries: [entry] });
    }
  }
  return groups;
}

/** The row's left accent border color (docs/design/09-activity.md task 2's own union). */
export type ActivityAccentTone = 'success' | 'info' | 'warn' | 'danger';

/**
 * `docs/design/09-activity.md`: "the verb becomes the row's colour accent, not a chip — Created
 * green / Updated blue / Archived amber / Deleted red." The design doc names four verbs; the actual
 * backend enum (`vision-domain`'s `AuditAction`) has six: `CREATED`/`UPDATED`/`DEACTIVATED`/
 * `ACTIVATED`/`DELETED`/`RESTORED`. Reconciling the two:
 *
 * - `CREATED` → green, `UPDATED` → blue, `DELETED` → red — direct, unambiguous matches (this app's
 *   own `AuditAction.DELETED` is what `org-logic.ts#formatActivity`'s `ACTION_LABELS` literally
 *   renders as **"Deleted"**, matching the mockup's own red "Deleted" row verbatim — even though this
 *   app's *buttons* elsewhere call the same soft-delete action "Archive" (`archiveAssetNow`/
 *   `devices-page-logic.ts`), the *audit trail*'s own word for it is "Deleted", not "Archived").
 * - The mockup's remaining "Archived amber" slot has no literal `AuditAction.ARCHIVED` to bind to —
 *   `DEACTIVATED` (a genuinely distinct, reversible "pull from service" action, rendered "Deactivated")
 *   is the closest fit to what "archived" was very likely gesturing at, so it takes the amber slot.
 * - `ACTIVATED`/`RESTORED` have no named color in the mockup at all. Extended along the two existing
 *   semantic families rather than inventing a fifth tone (docs/UX-REWORK-PLAN.md §U-b item 2's "one
 *   saturated accent" rule read generally: reuse this app's four existing tones, don't add a fifth):
 *   `ACTIVATED` reads as a positive, creation-adjacent state → green, same family as `CREATED`;
 *   `RESTORED` reads as "brought back to how it was" → blue, same family as `UPDATED`.
 *
 * Falls back to `'info'` (the most neutral tone) for any action this app doesn't yet name — never a
 * missing/blank accent, mirroring `formatActivity`'s own "unrecognized value still renders" rule.
 */
const ACCENT_TONE: Readonly<Record<string, ActivityAccentTone>> = {
  CREATED: 'success',
  ACTIVATED: 'success',
  UPDATED: 'info',
  RESTORED: 'info',
  DEACTIVATED: 'warn',
  DELETED: 'danger',
};

export function activityAccentTone(action: string): ActivityAccentTone {
  return ACCENT_TONE[action] ?? 'info';
}
