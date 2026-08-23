import type { LiveConnectionState } from '../live/live-store';
import type { OverallHealth, SubsystemHealth } from '../api/models';

/**
 * Pure, Angular-free logic behind `core/system-status/system-status-store.ts`,
 * `shared/ui/app-sidebar/**`'s shell rollup dot, and `features/system-status/**`'s own facade
 * (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.1-§5.2, wave S3).
 *
 * **Why a shared severity vocabulary, not a `SubsystemHealth`-shaped one**: `<vision-notice>`'s own
 * `variant` input (`shared/ui/notice.ts`) is exactly `'neutral' | 'warn' | 'danger' | 'ok'` — this
 * module's {@link ShellSeverity} is deliberately the identical four-value union (structurally, not by
 * import, to keep this file dependency-free) so a page can bind `[variant]="healthSeverity(overall)"`
 * straight onto the shared primitive with no third mapping step, and so the shell rollup dot
 * (`.dot.ok`/`.dot.warn`/`.dot.danger`/bare `.dot`) and the page's own subsystem chips read the exact
 * same four-way vocabulary as every other status surface in this app.
 */
export type ShellSeverity = 'ok' | 'warn' | 'danger' | 'neutral';

/**
 * `SubsystemHealth`'s own severity. `'DISABLED'` is `'neutral'` per the backend's own explicit rule
 * (`models.ts#SubsystemHealth`'s own doc comment: "must render as neutral/off, never as a fault").
 * `'UNKNOWN'` is *also* `'neutral'`, a deliberate choice this wave makes rather than one the plan
 * pins down: it covers both "a provider threw" and ordinary "nothing to report yet" (the S2 outcome
 * notes' own example — `mavlink-link` reads `UNKNOWN` for "no vehicle claimed", not a fault at all),
 * and this app has no way to tell the two apart from the wire shape alone. Colouring `UNKNOWN` as a
 * warning would make an ordinary, common state ("no drone connected right now") read as a standing
 * problem every time an operator opens this page — the honest middle ground is the same "we don't
 * fully know, but we're not claiming a fault" treatment `DISABLED` already gets.
 */
const HEALTH_SEVERITY: Readonly<Record<SubsystemHealth, ShellSeverity>> = {
  OK: 'ok',
  DEGRADED: 'warn',
  DOWN: 'danger',
  DISABLED: 'neutral',
  UNKNOWN: 'neutral',
};

/** Sentence-case display text for a health value — the subsystem chip's own label, and the overall verdict's headline word. */
const HEALTH_LABEL: Readonly<Record<SubsystemHealth, string>> = {
  OK: 'OK',
  DEGRADED: 'Degraded',
  DOWN: 'Down',
  DISABLED: 'Disabled',
  UNKNOWN: 'Unknown',
};

/** `OverallHealth` is a strict subset of `SubsystemHealth` (no `'DISABLED'`), so both tables above already cover it. */
export function healthSeverity(health: SubsystemHealth): ShellSeverity {
  return HEALTH_SEVERITY[health];
}

export function healthLabel(health: SubsystemHealth): string {
  return HEALTH_LABEL[health];
}

/** `LiveStore.connectionState()`'s own severity — the identical mapping `app-sidebar.ts`'s
 *  pre-existing `liveTransportSeverity` computed uses inline for its own, separate dot; centralized
 *  here so the shell rollup (§5.2) and the page's own live-transport card (§5.1) never drift from it
 *  or from each other. `'connecting'` is `'neutral'`, not `'ok'` — a brief, normal transient, not a
 *  confirmed-good state (mirrors `reachableSeverity`'s identical "don't claim ok before we know"
 *  posture below). */
export function connectionSeverity(state: LiveConnectionState): ShellSeverity {
  switch (state) {
    case 'open':
      return 'ok';
    case 'closed':
      return 'warn';
    case 'connecting':
      return 'neutral';
  }
}

/** `FleetStore.reachable()`'s own severity — `null` (the pre-first-request state) is `'neutral'`,
 *  never `'ok'`: this app never claims a backend is reachable before the first request actually
 *  confirms it (mirrors `FleetStore.reachable`'s own doc comment: "`null` until the first request
 *  settles, so the header shows no verdict prematurely"). */
export function reachableSeverity(reachable: boolean | null): ShellSeverity {
  if (reachable === null) {
    return 'neutral';
  }
  return reachable ? 'ok' : 'danger';
}

const SEVERITY_RANK: Readonly<Record<ShellSeverity, number>> = { ok: 0, neutral: 1, warn: 2, danger: 3 };

/** The most alarming of `values`, `'ok'` when `values` is empty — danger beats warn beats neutral beats ok. */
export function worstSeverity(values: readonly ShellSeverity[]): ShellSeverity {
  return values.reduce<ShellSeverity>(
    (worst, value) => (SEVERITY_RANK[value] > SEVERITY_RANK[worst] ? value : worst),
    'ok',
  );
}

/**
 * The shell rollup dot's own severity (docs/plans/done/SYSTEM-STATUS-PLAN.md §5.2) — the direct fix for §1.2's
 * finding that the sidebar dot answered only "did the last device poll succeed" and stayed green
 * through a live-transport failure or a degraded backend subsystem. Worst-of three independent axes:
 * backend REST reachability, the SSE live-transport connection, and the platform's own self-reported
 * `overall` health. `overall === undefined` (the status store hasn't completed its first fetch yet, or
 * its most recent poll failed with nothing to show — see `SystemStatusStore`'s own doc comment) is
 * treated as `'neutral'`, exactly like `reachable === null` above: **never** claim "ok" from a signal
 * this app hasn't actually read a value for yet.
 */
export function shellStatusSeverity(
  reachable: boolean | null,
  connectionState: LiveConnectionState,
  overall: OverallHealth | undefined,
): ShellSeverity {
  return worstSeverity([
    reachableSeverity(reachable),
    connectionSeverity(connectionState),
    overall === undefined ? 'neutral' : healthSeverity(overall),
  ]);
}

/** The rollup dot's `title`/`aria-label` — one honest sentence per severity, never "Connection issue" (UX-DESIGN §7.1). */
export function shellStatusLabel(severity: ShellSeverity): string {
  switch (severity) {
    case 'ok':
      return 'System status: all clear';
    case 'warn':
      return 'System status: degraded';
    case 'danger':
      return 'System status: down';
    case 'neutral':
      return 'System status: checking…';
  }
}
