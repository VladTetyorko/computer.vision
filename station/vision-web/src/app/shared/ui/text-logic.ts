/**
 * Small, pure copy-formatting helpers with no Angular/HTTP dependency — shared by any component
 * that needs to turn a raw count into a grammatically correct label. Split out (rather than adding
 * to a component file) so it's unit-testable in isolation, mirroring this app's own `*-logic.ts`
 * convention (e.g. `shared/ui/return-home-button-logic.ts`).
 *
 * `shared/ui/page-bar/page-bar.ts` already exports an identical `pluralize` for its own count-chip
 * use (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2) — this module is not a replacement for that one (out
 * of this task's file scope to touch), it is the canonical home `features/replay/**` now imports
 * from instead, per docs/plans/active/OPERATOR-UX-6-PLAN.md finding E3 ("plurals from one place"). The bug
 * E3 actually names — `after-action-panel.ts` calling `pluralize(count, 'entry')` with no explicit
 * plural, silently defaulting to the regular-plural fallback `'entrys'` — was a missing argument at
 * the call site, not a defect in the function itself; this module exists so a future caller in this
 * feature has one obvious place to import a tested `pluralize` from, not because the earlier one was
 * wrong.
 */

/**
 * `"1 asset"` / `"0 assets"` / `"12 assets"` — the singular only at exactly one; `plural` defaults
 * to `${singular}s` but must be supplied explicitly for anything irregular (`pluralize(2, 'entry',
 * 'entries')`), which is exactly the argument `after-action-panel.ts`'s `entry`/`audit` rows were
 * missing before this fix.
 */
export function pluralize(count: number, singular: string, plural = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : plural}`;
}
