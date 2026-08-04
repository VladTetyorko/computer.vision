/// <reference types="vite/client" />
import { describe, expect, it } from 'vitest';

/**
 * Overlay-consistency guard (docs/UI-STATE-PLAN.md §2.3, §3) — a pure source-scanning test, the same
 * technique `core/ui/architecture.spec.ts` already established (`import.meta.glob(..., '?raw')`, no
 * `TestBed`, plain string analysis; this suite runs in the Angular unit-test builder's browser-like
 * bundle, where the Node `fs`/`path` APIs are unavailable).
 *
 * **What it enforces.** docs/UI-STATE-PLAN.md §2.3's own dividing line between an allowed `<details>`
 * and a forbidden one is behavioral, not syntactic — *"can something else need to close this?"*:
 *   - **Inline disclosure that pushes content** (the sidebar's `Advanced`/`Upcoming`, `/debug`'s raw
 *     JSON, a detection card's `Advanced` section) — native `<details>` is fine, its state is genuinely
 *     local and harmless.
 *   - **Floating overlay that covers other content** (the identity menu, the notification dropdown —
 *     §1's own reproduced D4: *"their `open` state lives in the DOM, not in a signal — Angular cannot
 *     see it, no store can coordinate it, and nothing resets it"*) — must be signal-backed instead.
 * That question can't be answered by grepping for the word "dropdown" or "menu" — nothing in either
 * known-bad template says either word in a way a known-good one doesn't too.
 *
 * **The heuristic actually encoded**: a `<details>` counts as signal-backed (allowed) only if its
 * opening tag carries an `[open]="…"` Angular property binding — i.e. its open/closed state is
 * mirrored into a real signal something else could read or force closed, exactly what
 * `app-sidebar.html`'s two disclosures already do (`[open]="sidebar.advancedOpen()"` paired with
 * `(toggle)="onAdvancedToggle($event)"` to sync it back). A bare `<details>` with no `[open]` binding
 * relies purely on the browser's own click-to-toggle DOM state — D4's own diagnosis, word for word —
 * so it is flagged regardless of anything else about it (class name, contents, `#template` ref).
 *
 * **Scope: the always-mounted shell only.** Only files actually reachable from `app.html`'s permanent
 * render tree are scanned — the root shell itself, the sidebar (+ the identity chip / notification
 * bell it composes), the toast host, and the undo toast (docs/UI-STATE-PLAN.md §2.2 D5: *"the shell is
 * always mounted, so nothing cleans up"* — page-scoped overlays are destroyed with their host page and
 * self-heal on navigation for free, which is exactly what makes them out of D4's blast radius). This is
 * deliberately **not** every `<details>` under `shared/ui/**` — `kebab-menu.ts`'s per-row overflow menu
 * and every page-local confirm/dialog are reusable but page-scoped, not shell-scoped; sweeping them in
 * here would flag components this plan's own §4 scope table explicitly leaves alone.
 *
 * **Honestly, what this cannot catch** (documented per this guard's own brief, not swept under the rug):
 *  1. `[open]="true"` (or any always-true expression) satisfies the regex below without being wired to
 *     anything real — this checks for the *shape* of a controlled binding, not that the bound signal is
 *     actually coordinated by a store.
 *  2. It cannot see CSS. A `[open]`-bound `<details>` some future stylesheet turns into a
 *     `position: fixed` overlay would still pass — §2.3's own line is visual/behavioral, and a pure-text
 *     scan carries no layout information.
 *  3. It only scans the files listed in the two `import.meta.glob` calls below — a new always-mounted
 *     shell component added to `app.html` without being added here too is invisible to it, the same
 *     acknowledged limitation `architecture.spec.ts#ROUTED_PAGES` already carries (a hardcoded list, not
 *     a live import-graph walk). The "resolves exactly N shell files" sanity check below at least fails
 *     loudly if one of *today's* paths goes stale, rather than silently scanning zero files.
 *  4. A multi-line opening tag whose own binding expression contains a literal `>` (e.g.
 *     `[open]="a() > b()"`) would confuse the simple `[^>]*` tag-boundary regex below — none of the
 *     files this guard currently reads do that.
 *
 * **The union-type half of docs/UI-STATE-PLAN.md §3** ("any new `GlobalOverlayId` must be registered in
 * the store's own union type") needs no test here — the plan's own text is explicit that this is
 * already compiler-enforced (a string literal not in the union fails `tsc`), which is the entire point
 * of a union over a free string; a Vitest spec re-checking what `tsc` already guarantees would be
 * redundant ceremony, not a real guard.
 */

// Every `.html`-templated file reachable from `app.html`'s always-mounted render tree — see this
// file's own "Scope" paragraph above for why this list is short and deliberate, not `shared/ui/**`.
const HTML_SOURCES = import.meta.glob(
  [
    '../../app.html',
    '../../shared/ui/app-sidebar/app-sidebar.html',
    '../../shared/ui/identity-chip.html',
    '../../shared/ui/notification-bell.html',
    '../../shared/ui/events-rail.html',
  ],
  { query: '?raw', import: 'default', eager: true },
) as Record<string, string>;

// `toast-host.ts`/`undo-toast.ts` are the shell's own two inline-`template:` components (no separate
// `.html` sibling to glob) — `app.ts` is included too, purely for symmetry/future-proofing (it has no
// template of its own beyond `app.html`, already covered above).
const TS_SOURCES = import.meta.glob(['../../app.ts', '../../shared/ui/toast-host.ts', '../../shared/ui/undo-toast.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>;

/** Strips `<!-- -->`, `/* *\/` and `//` comments — a doc comment that merely *mentions* `<details>`
 *  (this very file's own class doc, for one) must never be mistaken for a real occurrence. Extends
 *  `architecture.spec.ts#stripComments`'s identical JS-comment stripping with HTML comments, since this
 *  guard reads `.html` sources too. */
function stripComments(src: string): string {
  return src
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\/\/[^\n]*/g, '');
}

/** Every `<details …>` opening tag in `source` that has no `[open]` binding — see this file's own
 *  class doc comment for why that binding's presence is the line this guard actually draws. */
function unboundDetailsTags(source: string): readonly string[] {
  const tags = stripComments(source).match(/<details\b[^>]*>/g) ?? [];
  return tags.filter((tag) => !/\[open\]\s*=/.test(tag));
}

describe('overlay-consistency guard (always-mounted shell, docs/UI-STATE-PLAN.md §2.3/§3)', () => {
  it('resolves exactly the shell files this guard is scoped to (a stale path scans nothing, silently)', () => {
    expect(Object.keys(HTML_SOURCES).length).toBe(5);
    expect(Object.keys(TS_SOURCES).length).toBe(3);
  });

  const allSources: Record<string, string> = { ...HTML_SOURCES, ...TS_SOURCES };

  it.each(Object.keys(allSources))('%s: every <details> is signal-backed ([open]-bound), never a bare DOM-state floating overlay', (path) => {
    const offenders = unboundDetailsTags(allSources[path]);
    expect(
      offenders,
      `${path} has a <details> with no [open] binding — the always-mounted shell can't leave an ` +
        `overlay's state in the DOM where nothing can coordinate or close it (docs/UI-STATE-PLAN.md ` +
        `§2.3/D4): ${offenders.join(', ')}`,
    ).toEqual([]);
  });
});
