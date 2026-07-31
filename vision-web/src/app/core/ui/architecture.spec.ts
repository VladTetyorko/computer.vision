/// <reference types="vite/client" />
import { describe, expect, it } from 'vitest';

/**
 * Architecture guard (docs/UI-ARCHITECTURE-PLAN.md) — a pure source-scanning test, no `TestBed`,
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
  'fly/fly',
  'command/command',
  'asset-detail/asset-detail',
  'assets/assets',
  'devices/devices',
  'live/live',
  'wall/wall',
  'replay/replay',
  'onboarding/onboarding',
  'org-settings/org-settings',
  'activity/activity',
  'settings/settings',
  'auth/login/login',
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
