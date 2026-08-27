/// <reference types="vite/client" />
import { describe, expect, it } from 'vitest';
import { derivePreflight } from './flight-state-logic';

/**
 * Guards the exit criterion docs/plans/active/DRONE-ONBOARDING-PLAN.md wave O6 was explicitly built
 * against: **"the existing cockpit checklist renders identically when the [readiness] API is
 * unavailable — the fallback path must be tested, not assumed. This is the guardrail: the default
 * config has onboarding off (`vision.onboarding.probe.enabled=false`, D17), so the fallback is the
 * common case."**
 *
 * Two independent proofs, mirroring `core/ui/architecture.spec.ts`/`core/ui/overlay-consistency.spec.ts`'s
 * own source-scanning technique (`import.meta.glob(..., '?raw')`, no `TestBed`; this suite runs in
 * the Angular unit-test builder's browser-like bundle, where Node `fs`/`path` are unavailable):
 *
 * 1. **Structural** — `derivePreflight`'s own signature (`TelemetrySample | undefined, VehicleKind |
 *    undefined, hasVideo, streaming, nowMs` — `vehicleKind` added by FLEET-RADIO-PLAN.md's R4c wave,
 *    itself sourced from `FlightCapability`, never from readiness) and `<vision-preflight-checklist>`
 *    (`shared/ui/preflight-checklist.ts`) never named a readiness type/service to begin with, so
 *    there is nothing for wave O6 to have broken —
 *    but a signature change is exactly the kind of drift a future edit could introduce silently.
 *    Scans `flight-state-logic.ts`, `preflight-checklist.ts` and `features/fly/cockpit-facade.ts`
 *    (the checklist's one live consumer, via its `preflightItems` computed) for any reference to the
 *    new readiness API surface (`core/readiness/readiness-logic.ts`, `ReadinessReport`,
 *    `assetReadiness`/`fleetReadiness`/`probeAsset`/`probeVehicleCandidate`/`remediateAsset`) — this
 *    wave added none, and this test fails the moment one is.
 * 2. **Behavioral** — `derivePreflight` itself, called exactly as `flight-state-logic.spec.ts`'s own
 *    pre-existing suite already does (untouched by this wave), still returns the same 5-row shape
 *    from telemetry alone. There is no `readiness`-flavoured parameter to even feed a rejected/absent
 *    API response through, which *is* the fallback: the checklist's data path physically cannot
 *    observe whether `GET /api/assets/{id}/readiness` exists, is disabled, or errors.
 */

const SOURCES = import.meta.glob('../../**/*.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

function sourceOf(suffix: string): string {
  const key = Object.keys(SOURCES).find((k) => k.endsWith(suffix));
  if (!key) throw new Error(`could not find source ending in ${suffix}`);
  return SOURCES[key];
}

const READINESS_TOKENS = [
  'readiness-logic',
  'ReadinessReport',
  'ReadinessFacade',
  'assetReadiness',
  'fleetReadiness',
  'probeAsset',
  'probeVehicleCandidate',
  'remediateAsset',
];

describe('cockpit preflight checklist stays independent of the readiness API (drone-onboarding wave O6)', () => {
  it.each(['flight-state-logic.ts', 'preflight-checklist.ts', 'cockpit-facade.ts'])(
    '%s references no readiness-API symbol',
    (suffix) => {
      const code = sourceOf(suffix);
      const found = READINESS_TOKENS.filter((token) => code.includes(token));
      expect(found, `${suffix} must not reference the readiness API (found: ${found.join(', ')}) — the cockpit checklist's fallback must hold with no coupling at all, not a caught error`).toEqual([]);
    },
  );

  it('derivePreflight still renders its full 5-row checklist from telemetry alone, with no readiness input to even withhold', () => {
    const rows = derivePreflight(undefined, undefined, true, true, Date.now());

    expect(rows).toHaveLength(5);
    expect(rows.map((row) => row.label)).toEqual(['Video feed', 'Telemetry link', 'GPS fix', 'Battery', 'Armable']);
  });
});
