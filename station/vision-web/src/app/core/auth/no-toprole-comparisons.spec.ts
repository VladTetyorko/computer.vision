/// <reference types="vite/client" />
import { describe, expect, it } from 'vitest';

/**
 * Regression guard for docs/plans/active/AUTH-ROLES-PLAN.md §3.2 (wave W2) — a pure source-scanning test,
 * no `TestBed`, mirroring `core/ui/architecture.spec.ts`'s own `import.meta.glob(..., '?raw')` technique
 * (this suite runs in the Angular unit-test builder's browser-like bundle, where `node:fs` is
 * unavailable). W2 moved every web "may I…" gate off a raw `MeResponse#topRole` comparison onto
 * `AuthStore.capabilities()`/`hasCapability` or `AuthStore.scopeKind()`/`canAdminister` — `topRole` alone
 * was never a reliable stand-in once `VIEWER` existed, and it carries no information about a
 * MANAGER's `GROUPS` scope vs an ADMIN's `UNBOUNDED` one. `core/auth/auth-logic.ts` is the one place
 * allowed to reason about `topRole` directly (`topRoleLabel`, the identity chip's badge) — everywhere
 * else, a reintroduced `topRole === '…'`/`topRole !== '…'` is exactly the regression W2 fixed, so this
 * fails CI the moment one creeps back in.
 *
 * Comments are stripped before matching (`stripComments`, identical to `architecture.spec.ts`'s own)
 * so the many doc comments across this codebase that merely *narrate* the W2 migration (e.g.
 * `core/org/org-logic.ts`'s "moved off `topRole === 'ADMIN'`…") are never mistaken for a live comparison.
 */

// Every .ts source under src/app, inlined as raw strings at build time. Keys look like
// '../../features/fly/fly-logic.ts', '../../core/org/org-logic.ts', '../auth-logic.ts'.
const SOURCES = import.meta.glob('../../**/*.ts', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

/** Strip block + line comments — see this file's own class doc for why. */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
}

describe('no topRole comparison guard', () => {
  it('has zero live `topRole === …` / `topRole !== …` comparisons outside core/auth/', () => {
    const offenders = Object.entries(SOURCES)
      .filter(([path]) => !path.includes('/core/auth/'))
      .filter(([, src]) => /topRole\s*(===|!==)/.test(stripComments(src)))
      .map(([path]) => path);

    expect(offenders, `topRole comparison found outside core/auth/ in: ${offenders.join(', ')}`).toEqual([]);
  });
});
