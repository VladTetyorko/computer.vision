/// <reference types="vite/client" />
import { describe, expect, it } from 'vitest';

/**
 * Architecture guard (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — a pure source-scanning test, no `TestBed`,
 * mirroring the `app.routes.spec.ts` precedent of asserting a structural invariant cheaply. It fails
 * CI the moment a routed feature page drifts off the `Component → Facade → Store → Service` layering,
 * so the consistency the facade/`UiStore` sweep bought can't silently rot back.
 *
 * Reads the feature sources via Vite's `import.meta.glob(..., '?raw')` (inlined at build time) rather
 * than `node:fs` — this suite runs in the Angular unit-test builder's browser-like bundle, where the
 * Node fs/path/process APIs are unavailable.
 *
 * Invariants enforced, per routed page component:
 *   1. It injects only its facade (+ framework utilities) — never `VisionApi` or a `*Store` directly.
 *   2. It declares no mutually-exclusive-overlay flag as a bare `signal()` — those belong in a
 *      `UiStore` group (open/menu/confirm/editing state).
 *   3. A matching `<feature>-facade.ts` exists.
 *
 * Non-routed presentational child components (`flight-command-panel`, `telemetry-osd`, `wall-tile`,
 * `pilots-card`) are intentionally out of scope — the facade rule is per *routed* feature (the plan's
 * own carve-out), so they may still DI-share a host-provided store. `warehouse` is a static tile list
 * with no store/derived state and (per the plan's "no ceremony") no facade — excluded here too.
 */

// path segment (under features/) of each routed page component, minus the `.ts`.
const ROUTED_PAGES = [
  // `/fly` (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.5 F12) split the old combined `fly/fly` into two
  // routed pages — the picker (`drone-picker`) and the cockpit (`cockpit`), each addressable on
  // its own now (`/fly` vs `/fly/:assetId`) — each with its own facade below.
  'fly/drone-picker',
  'fly/cockpit',
  'command/command',
  'asset-detail/asset-detail',
  // `/assets` (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule 3/§3.3, wave W4) — the old, deleted
  // `AssetsPage` folded into a tabbed `InventoryPage`; `devices/devices` stays listed even though its
  // own route is now a redirect (`features/devices/devices.routes.ts`) — `DevicesPage` is still
  // mounted as the Links tab's content, still page-shaped, and still worth guarding.
  'inventory/inventory',
  'devices/devices',
  'live/live',
  'wall/wall',
  'replay/replay',
  'replay/replay-library',
  'onboarding/onboarding',
  'org-settings/org-settings',
  'activity/activity',
  'settings/account-settings',
  'auth/login/login',
  // First-boot bootstrap page (docs/plans/active/AUTH-ROLES-PLAN.md wave W3) — same shape/posture as
  // `login/login` above, guarded here from day one for the same reason.
  'setup/setup',
  'labeling/datasets',
  'labeling/dataset-detail',
  'labeling/sample-editor',
  'models/models',
  'training-jobs/training-job',
  'system-status/system-status',
  // Fleet readiness board + per-asset readiness report (docs/plans/active/DRONE-ONBOARDING-PLAN.md
  // wave O6) — both went through the same facade sweep as everything else here from the start, so
  // they're guarded from day one rather than grandfathered in later.
  'preflight/preflight',
  'readiness/readiness',
  // Visual geolocation v2 region manager (docs/plans/done/VISUAL-GEO-V2-PLAN.md §3.8, wave H6).
  'geo/region-manager',
  // Controller setup (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md C11) — the page injects
  // `RcInputService` directly (a browser-input service, not a store), the layouts themselves come
  // through its facade.
  'controller/controller-setup',
  // Profiles (docs/plans/active/CV-SETTINGS-PLAN.md §4, wave W6) — replaces `settings/detection-settings`
  // outright (deleted this wave; `/settings/detection` now redirects here).
  'vision-profiles/vision-profiles',
  // Improv Wi-Fi provisioning over Web Serial (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md
  // §4/§8, wave Z5) — pure frontend, no `VisionApi` call anywhere in the flow; the page injects only
  // its facade, which itself wraps `WebSerialGateway` (a hardware gateway, not a `*Store`).
  'provisioning/provisioning',
  // The crew seat (docs/plans/active/CREW-CONTROL-PLAN.md §3.4, wave W3) — guarded from day one like
  // every other routed page above.
  'crew/crew',
];

// All feature .ts sources, inlined as raw strings at build time. Keys look like
// '../../features/fly/fly.ts', '../../features/auth/login/login-facade.ts'.
const SOURCES = import.meta.glob('../../features/**/*.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

/** Strip block + line comments so a doc comment that merely *mentions* `inject(SomeStore)` (e.g.
 * `fly.ts`'s own "…its own `inject(TelemetryStore)`…") is never mistaken for a real injection. */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
}

function pageSource(seg: string): string {
  const key = Object.keys(SOURCES).find((k) => k.endsWith(`/features/${seg}.ts`) || k.endsWith(`/${seg}.ts`));
  if (!key) throw new Error(`could not find source for routed page ${seg}.ts`);
  return stripComments(SOURCES[key]);
}

describe('UI architecture guard (routed page components)', () => {
  it.each(ROUTED_PAGES)('%s: injects no VisionApi / *Store directly (goes through its facade)', (seg) => {
    const code = pageSource(seg);
    const injects = [...code.matchAll(/inject\(\s*([A-Za-z0-9_]+)\s*\)/g)].map((m) => m[1]);
    const forbidden = injects.filter((name) => name === 'VisionApi' || (name.endsWith('Store') && name !== 'UiStore'));
    expect(forbidden, `${seg}.ts must not inject ${forbidden.join(', ')} directly — move it into the facade`).toEqual([]);
  });

  it.each(ROUTED_PAGES)('%s: declares no overlay flag as a bare signal() (use a UiStore group)', (seg) => {
    const code = pageSource(seg);
    const overlaySignals = [
      ...code.matchAll(/([A-Za-z0-9_]*(?:[Oo]pen|[Mm]enu|[Cc]onfirm|[Ee]diting)[A-Za-z0-9_]*)\s*=\s*signal\(/g),
    ].map((m) => m[1]);
    expect(overlaySignals, `${seg}.ts holds overlay flag(s) as signals: ${overlaySignals.join(', ')} — route them through a UiStore group`).toEqual([]);
  });

  it.each(ROUTED_PAGES)('%s: has a matching <feature>-facade.ts', (seg) => {
    const has = Object.keys(SOURCES).some((k) => k.endsWith(`/${seg}-facade.ts`));
    expect(has, `expected ${seg}-facade.ts to exist`).toBe(true);
  });
});
