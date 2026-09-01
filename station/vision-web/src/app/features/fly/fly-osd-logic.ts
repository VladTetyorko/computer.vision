import { freshness, humanAge } from '../../core/telemetry/telemetry-logic';

/**
 * Pure derivations behind `fly-osd.ts`'s "stale is not live" behavior
 * (docs/plans/active/OPERATOR-UX-3-PLAN.md finding H1) — split out so the group-label/armed-text
 * swap is unit-testable without a `TelemetryStore`/`TestBed`, mirroring `rc-monitor-logic.ts`'s own
 * "a routed feature's own logic lives beside its one component" precedent (promoted to `core/` only
 * if a second consumer ever needs it — `freshness`/`humanAge` themselves already are core, being
 * this file's one shared dependency alongside `rc-monitor-logic.ts`'s `armedChip`).
 */

/**
 * Whether a sample this old should stop the OSD from reading as a live instrument — the exact same
 * `freshness()` tier the Link group's own age chip is graded on (`'stale'`, i.e. past
 * `TELEMETRY_AGE_RED_SECONDS`), reused here rather than re-deriving a second "how stale is too
 * stale" threshold. `undefined` (no sample yet) is never stale — {@link freshness}'s own `'none'`
 * tier — the OSD's "No telemetry" branch handles that case entirely separately.
 */
export function isStaleReading(ageSeconds: number | undefined): boolean {
  return freshness(ageSeconds) === 'stale';
}

/**
 * The Power/Nav group's own label — `'LAST KNOWN · 4d 2h'` once the sample backing every metric in
 * that group is stale (CLAUDE.md rule 9: stale data must not impersonate live data — H1's own
 * finding was a 4-day-old sample reading as a full-colour, confidently-labeled instrument), the
 * group's ordinary name (`'Power'`/`'Nav'`) otherwise. Both groups read the identical sample age, so
 * they render byte-identical stale text — deliberate: the point past "Power"/"Nav" is which group
 * this is, it's that neither can be trusted as live right now, and repeating that fact in the
 * structural register of every affected group beats naming only one of them.
 */
export function osdGroupLabel(defaultLabel: string, ageSeconds: number | undefined): string {
  if (!isStaleReading(ageSeconds)) {
    return defaultLabel;
  }
  return `LAST KNOWN · ${humanAge(ageSeconds as number)}`;
}

export type ArmedOsdText = 'ARMED' | 'ARMED?' | 'DISARMED';

/**
 * The Power group's armed chip text — `'ARMED?'`, never a confident `'ARMED'`, once the reading
 * backing it is stale (H1's own finding). Disarmed stays plain `'DISARMED'` at any age: reading a
 * grounded drone as armed is the dangerous direction to be wrong in, not the reverse. Callers only
 * ever invoke this once `armed` has actually resolved to `true`/`false` (`fly-osd.html`'s own
 * `@if (armed() !== undefined)` guards the chip's existence entirely).
 */
export function armedOsdText(armed: boolean, ageSeconds: number | undefined): ArmedOsdText {
  if (!armed) {
    return 'DISARMED';
  }
  return isStaleReading(ageSeconds) ? 'ARMED?' : 'ARMED';
}
