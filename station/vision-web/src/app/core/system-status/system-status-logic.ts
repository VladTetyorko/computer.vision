import type { LiveConnectionState } from '../live/live-fallback-logic';
import type { OverallHealth, SubsystemHealth, SubsystemStatus } from '../api/models';

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

// --- `/manage/system` banner verdict (OPERATOR-UX-5-PLAN.md finding U3, §2 U3) -----------------
//
// **Deliberately separate from `shellStatusSeverity`/`shellStatusLabel` above, not a replacement.**
// The sidebar rollup dot is unchanged by this wave and still treats any DOWN/DEGRADED subsystem —
// via `healthSeverity(overall)`, `overall` being the *backend's* worst-subsystem rollup — as worth
// flagging on every page; that is its own, coarser "something needs a look" signal and stays as-is
// (out of this wave's file scope: `shared/ui/app-sidebar/**`). `verdictFor` below answers a
// narrower, more literal question this page's own banner asked wrong (U3's own finding: "Banner
// `System is down.` while mediamtx is OK, SSE degraded, MAVLink unknown, CV down" — one optional
// subsystem reporting DOWN is not the whole station being down.

/** `verdictFor`'s own two-axis "can this app actually do its job right now" input — the same
 *  `FleetStore.reachable()`/`LiveStore.connectionState()` signals `SystemStatusFacade` already
 *  reads for its existing `backendSeverity`/`connectionSeverity` cards, bundled so the function
 *  itself stays a plain, easily-tested `(subsystems, transport) => verdict`. */
export interface SystemTransport {
  readonly backendReachable: boolean | null;
  readonly liveConnection: LiveConnectionState;
}

export interface SystemVerdict {
  readonly severity: ShellSeverity;
  readonly message: string;
}

/** Only `DOWN`/`DEGRADED` ever contribute — `OK`/`DISABLED`/`UNKNOWN` are excluded entirely, not
 *  merely out-ranked, so an idle/unconfigured/healthy subsystem can never read as a fault (mirrors
 *  `SubsystemHealth`'s own doc comment: `UNKNOWN` covers ordinary "nothing to report yet" cases —
 *  e.g. `mavlink-link` with no vehicle claimed — as honestly as `DISABLED` does). `DOWN` outranks
 *  `DEGRADED`; a tie keeps whichever the array lists first (deterministic, not meaningful on its
 *  own — the backend's own subsystem ordering is not a severity ranking).
 */
const SUBSYSTEM_FAULT_RANK: Readonly<Record<'DOWN' | 'DEGRADED', number>> = { DOWN: 1, DEGRADED: 0 };

function worstFaultySubsystem(subsystems: readonly SubsystemStatus[]): SubsystemStatus | undefined {
  let worst: SubsystemStatus | undefined;
  for (const subsystem of subsystems) {
    if (subsystem.health !== 'DOWN' && subsystem.health !== 'DEGRADED') {
      continue;
    }
    if (worst === undefined || SUBSYSTEM_FAULT_RANK[subsystem.health] > SUBSYSTEM_FAULT_RANK[worst.health as 'DOWN' | 'DEGRADED']) {
      worst = subsystem;
    }
  }
  return worst;
}

/**
 * `/manage/system`'s own banner verdict (OPERATOR-UX-5-PLAN.md finding U3, §2 U3): `'danger'`
 * ("System is down") is reserved for the two ways this app itself can't do its job — the backend
 * REST API is unreachable, or the live SSE transport is unreachable — **never** for one subsystem
 * (a crashed `cv-service`, `mediamtx` off) reporting `DOWN` on its own. Once both of those are
 * healthy, the verdict names the worst *reportable* subsystem instead, capped at `'warn'`
 * (`Degraded — CV inference down` / `Degraded — Live updates degraded`) — a station with one broken
 * optional subsystem is degraded, not down. `subsystems.length === 0` reads `'neutral'` (never a
 * guessed "OK" with nothing to back it — this app's usual "don't claim ok before we know" rule,
 * `reachableSeverity`'s own doc comment above); a genuinely clean bill of health reads `'ok'`.
 */
export function verdictFor(subsystems: readonly SubsystemStatus[], transport: SystemTransport): SystemVerdict {
  if (transport.backendReachable === false) {
    return { severity: 'danger', message: 'System is down — the backend is unreachable.' };
  }
  if (transport.liveConnection === 'closed') {
    return { severity: 'danger', message: 'System is down — live updates are unreachable.' };
  }
  const worst = worstFaultySubsystem(subsystems);
  if (worst !== undefined) {
    return { severity: 'warn', message: `Degraded — ${worst.label} ${worst.health === 'DOWN' ? 'down' : 'degraded'}` };
  }
  if (subsystems.length === 0) {
    return { severity: 'neutral', message: 'No subsystems reported.' };
  }
  return { severity: 'ok', message: 'System is OK.' };
}
