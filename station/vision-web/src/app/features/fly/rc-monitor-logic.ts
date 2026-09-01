import type { ActionBinding, ControlAction, ManualControlChannelBinding, ReadinessReport } from '../../core/api/models';
import type { ManualControlEngageState } from '../../core/rc/manual-control-client';
import type { RcSourceKind } from '../../core/rc/rc-source.service';
import { controlLabel } from '../../core/rc/control-action-logic';
import { padsFrom } from '../../core/rc/control-surface-logic';
import { featureStatusTone } from '../../core/readiness/readiness-logic';
import { freshness, humanAge } from '../../core/telemetry/telemetry-logic';

/**
 * Pure, component-adjacent logic behind `rc-monitor.ts` (docs/plans/active/CONTROLLER-UX-PLAN.md §2.2,
 * wave X2) — mirrors `flight-command-panel-logic.ts`'s own split (a routed feature's gating/copy
 * rules live beside their one component, promoted to `core/` only if a second consumer ever needs
 * them).
 */

export interface EngageGateInput {
  readonly hasAsset: boolean;
  readonly canCommand: boolean;
  readonly sourceKind: RcSourceKind;
  readonly gamepadConnected: boolean;
  readonly engageState: ManualControlEngageState;
}

/**
 * The Take-control button's poka-yoke reason (docs/plans/done/UX-REWORK-PLAN.md §U-a2 rule 1 — "disabled with
 * the reason inline, never enabled-then-error", applied to R5's engage gesture; mirrors
 * `flight-command-panel-logic.ts#canShowCommandPanel`'s own "reuse the same gate the flight panel
 * uses" instruction, extended with the one thing unique to RC: whether the *selected input source*
 * can actually produce a stick value). `undefined` = enabled. Checked in priority order — the most
 * fundamental blocker first, so a caller with several problems at once sees the one to fix, not a
 * vague catch-all.
 *
 * A missing gamepad no longer blocks control outright
 * (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P10): it blocks only while the gamepad
 * source is the selected one, and the message says so, because the on-screen surface is right
 * there. Neither does a browser without the Gamepad API — that fact belongs to the monitor above,
 * which is what actually needs it.
 */
export function engageDisabledReason(input: EngageGateInput): string | undefined {
  if (input.engageState === 'engaging') {
    return 'Engaging…';
  }
  if (!input.hasAsset) {
    return 'Pick a drone first.';
  }
  if (!input.canCommand) {
    return "This drone isn't commandable right now.";
  }
  if (input.sourceKind === 'gamepad' && !input.gamepadConnected) {
    return 'Plug your transmitter in, or switch to the on-screen controls.';
  }
  return undefined;
}

/** `client.latencyMs()`'s display text — "—" while no `ack` has arrived yet, the same "never a
 * fabricated value" degrade rule every other enrichment readout in this app follows (CLAUDE.md). */
export function latencyLabel(latencyMs: number | undefined): string {
  return latencyMs === undefined ? '—' : `${Math.round(latencyMs)} ms`;
}

// --- State strip (docs/plans/active/CONTROLLER-UX-PLAN.md §2.2 U5 — chips, never prose) -----------------

/** The state strip's armed/disarmed chip. */
export interface ArmedChip {
  readonly text: string;
  readonly tone: 'ok' | 'neutral';
}

/**
 * `undefined` (no telemetry yet) reads as a faint dash, never a guessed state — the same poka-yoke
 * rule `flight-state-logic.ts`'s own top doc comment states for every other absent flight reading.
 * `armed`/`disarmed` deliberately mirror `fly-osd.css`'s own `.osd-metric.armed`/`.disarmed`
 * convention (armed is the notable, success-toned state; disarmed is dimmed/routine — a grounded
 * vehicle is the normal, safe one) rather than inventing a second armed/disarmed colour language
 * for this drawer — **while the reading is live/aging**. Once `ageSeconds` reads
 * `freshness(…) === 'stale'` (docs/plans/active/OPERATOR-UX-3-PLAN.md finding H1 — the same tier
 * `fly-osd-logic.ts#isStaleReading` grades the OSD's own armed chip on, one shared threshold, not a
 * second invented one), the chip drops the success tone entirely — `tone: 'neutral'` even for
 * `armed === true` — and its text becomes `'Armed 4d ago'`/`'Disarmed 4d ago'`: a fact about the
 * past, not a claim about right now. `ageSeconds` is only ever `undefined` before a first sample
 * exists, in which case `armed` itself is already `undefined` too (both come from the same
 * `TelemetrySample`) — the stale branch is unreachable without a real age to report.
 */
export function armedChip(armed: boolean | undefined, ageSeconds: number | undefined): ArmedChip {
  if (armed === undefined) {
    return { text: '—', tone: 'neutral' };
  }
  if (ageSeconds !== undefined && freshness(ageSeconds) === 'stale') {
    return { text: `${armed ? 'Armed' : 'Disarmed'} ${humanAge(ageSeconds)} ago`, tone: 'neutral' };
  }
  return armed ? { text: 'ARMED', tone: 'ok' } : { text: 'DISARMED', tone: 'neutral' };
}

/** Whether the sample age backing this drawer's chips counts as stale (H1) — the same call
 * {@link armedChip} makes internally, exported so `rc-monitor.ts` can fade the mode chip alongside
 * the armed chip's own tone drop, rather than each re-deriving "is this stale" separately. */
export function sampleIsStale(ageSeconds: number | undefined): boolean {
  return ageSeconds !== undefined && freshness(ageSeconds) === 'stale';
}

// --- "Also on <switch>" hints (docs/plans/active/CONTROLLER-UX-PLAN.md §2.2 decision U3) ------------------

/** `ARM` and the switch-only `TOGGLE_ARM` both fire the same physical arm sequence, so either one
 * qualifies a switch for the Arm/Disarm row's hint. */
const ARM_ACTIONS: ReadonlySet<ControlAction> = new Set<ControlAction>(['ARM', 'TOGGLE_ARM']);
const MODE_ACTIONS: ReadonlySet<ControlAction> = new Set<ControlAction>(['SET_MODE']);

/**
 * The first bound control whose action map fires one of `actions`, read the way the operator would
 * off their own transmitter (`controlLabel`) — `flight-command-panel.ts`'s `modeAlsoOn`/`armAlsoOn`
 * inputs. `undefined` when no switch fires it — a profile with no such binding (or no active
 * profile at all, `rc-monitor.ts` passing `[]`) renders no hint, never a fabricated pairing.
 *
 * `arrowSuffix` appends "↑" when the firing position is `HIGH` — only meaningful for the arm hint
 * (decision U3's own wording: "with '↑' when arm is on HIGH"); a mode switch's position doesn't fit
 * the same one-character convention without naming which mode sits on it, which this short hint
 * deliberately doesn't attempt.
 */
function alsoOnHint(
  actionMap: readonly ActionBinding[],
  actions: ReadonlySet<ControlAction>,
  arrowSuffix: boolean,
): string | undefined {
  for (const binding of actionMap) {
    const hit = binding.positions.find((position) => actions.has(position.action));
    if (hit) {
      const label = controlLabel(binding.source, binding.sourceIndex);
      return arrowSuffix && hit.position === 'HIGH' ? `${label} ↑` : label;
    }
  }
  return undefined;
}

/** `flight-command-panel.ts`'s `modeAlsoOn` input — the switch whose action map fires `SET_MODE`. */
export function modeAlsoOnHint(actionMap: readonly ActionBinding[]): string | undefined {
  return alsoOnHint(actionMap, MODE_ACTIONS, false);
}

/** `flight-command-panel.ts`'s `armAlsoOn` input — the switch whose action map fires `ARM`/
 * `TOGGLE_ARM`, arrow-suffixed when arm sits on that switch's `HIGH` position. */
export function armAlsoOnHint(actionMap: readonly ActionBinding[]): string | undefined {
  return alsoOnHint(actionMap, ARM_ACTIONS, true);
}

// --- Keyboard key legend (docs/plans/active/CONTROLLER-UX-PLAN.md §5 wave K) -----------------------

/**
 * The keyboard drawer footer's compact legend, one quiet line per pad the layout actually has
 * (`control-surface-logic.ts#padsFrom`'s own "a rover gets one, an aircraft two" split) — built from
 * the bound controls' own `label`s, never invented here, mirroring `padsFrom`'s own doc comment. A
 * one-pad layout (a rover: steering shares the throttle's pad) gets one line for `W`/`S`/`A`/`D`; a
 * two-pad layout (an aircraft) gets a second for the arrow keys once they're freed up from the
 * one-pad throttle redundancy (`keyboard-rc-input.service.ts#resolveKey`).
 */
export function keyLegendLines(channelMap: readonly ManualControlChannelBinding[]): readonly string[] {
  const axes = channelMap.filter((b) => b.source === 'AXIS');
  const label = (fn: ManualControlChannelBinding['function']): string | undefined =>
    axes.find((b) => b.function === fn)?.label.toLowerCase();
  const onePad = padsFrom(channelMap).length <= 1;

  const line = (parts: readonly (readonly [string, string | undefined])[]): string | undefined => {
    const joined = parts
      .filter((part): part is readonly [string, string] => part[1] !== undefined)
      .map(([keys, name]) => `${keys} ${name}`);
    return joined.length > 0 ? joined.join(' · ') : undefined;
  };

  const lines: string[] = [];
  const primary = line([
    ['W/S', label('THROTTLE')],
    ['A/D', label('YAW') ?? label('STEERING')],
  ]);
  if (primary) {
    lines.push(primary);
  }
  if (!onePad) {
    const secondary = line([
      ['↑↓', label('PITCH')],
      ['←→', label('ROLL')],
    ]);
    if (secondary) {
      lines.push(secondary);
    }
  }
  return lines;
}

// --- Readiness rows under the footer (docs/plans/active/CONTROLLER-UX-PLAN.md §5 wave R) ------------

/** The `rc-relay` feature key (FLEET-RADIO-PLAN.md R6) — the one readiness row that governs whether
 * this vehicle's link actually satisfies what the RC relay needs (a GCS-sysid value and its
 * `RC_OPTIONS` forbidden-bits mask, folded server-side into one row). */
const RC_READINESS_FEATURE = 'rc-relay';

/** One readiness row as this drawer renders it — tone from the same `FeatureStatus` vocabulary
 * every other readiness surface in this app uses (`core/readiness/readiness-logic.ts#featureStatusTone`). */
export interface ReadinessRowView {
  readonly label: string;
  readonly tone: 'ok' | 'warn' | 'danger' | 'muted';
  readonly detail?: string;
}

/**
 * The RC-relay readiness row, when it isn't `READY` — advisory only (a `NO_GO` never blocks flight,
 * `features/readiness/readiness.html`'s own framing), so this never appears as a gate, only as
 * context under an otherwise-enabled Take-control button (FLEET-RADIO R6: "one sentence hides which
 * row is red"). `[]` for a `READY` row, a report that never evaluated this feature, or no report at
 * all (still loading, or the read failed) — never a fabricated warning.
 */
export function rcReadinessRows(report: ReadinessReport | undefined): readonly ReadinessRowView[] {
  const row = report?.features.find((f) => f.feature === RC_READINESS_FEATURE);
  if (!row || row.status === 'READY') {
    return [];
  }
  return [{ label: row.label, tone: featureStatusTone(row.status), detail: row.detail || undefined }];
}

/** What to render under the Take-control button — `engageDisabledReason`'s existing single-line text
 * when one of those more fundamental gates is what's actually stopping the operator, else — once
 * every one of those is clear — the RC-relay readiness row(s) themselves, so the operator sees which
 * row is red instead of one flattened sentence. `{}` renders nothing, same as today's
 * `disabledReason() === undefined`. */
export interface EngageBlockView {
  readonly rows?: readonly ReadinessRowView[];
  readonly reason?: string;
}

export function engageBlock(gate: EngageGateInput, readiness: ReadinessReport | undefined): EngageBlockView {
  const reason = engageDisabledReason(gate);
  if (reason !== undefined) {
    return { reason };
  }
  const rows = rcReadinessRows(readiness);
  return rows.length > 0 ? { rows } : {};
}

// --- Session link (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P4) -------------------
// Not to be confused with `engageDisabledReason`/`engageBlock` above, which gate the *Take-control*
// button (`ManualControlClient`, an RC input concern) — this is `AssetSessionController`'s own,
// unrelated `engage`/`disengage` verb pair, opening or closing an `AssetUsage` with no RC input
// involved at all. Named `SessionAffordance`/`resolveSessionAffordance` deliberately, not
// `engage*`, to keep the two apart in this file where both live side by side.

/** What this drawer's session-link block shows: nothing, an "Engage link" button, or an
 * "End session" one — never both at once. */
export type SessionAffordance = 'none' | 'engage' | 'end';

/**
 * Before this wave, an asset with only a `TELEMETRY` device (a rover with no camera, or an
 * aircraft being readied before its camera comes up) had **no working path** to becoming
 * commandable at all — the Fly cockpit's arm/mode/RC commands silently depended on a video stream
 * having been started first, purely as a side effect (`UsageTracker#deviceStreamStarted` opening
 * the `AssetUsage` the command panel's `canCommand` gate needs). `engage` now opens that same kind
 * of usage directly (the parallel perception-side change this wave depends on — see
 * `AssetSessionController`'s own javadoc), so this drawer needs a place to call it.
 *
 * `'none'` whenever a video stream is already `live` — starting the stream already opens (or
 * reuses) the usage the command panel needs, so a second competing verb here would only add
 * clutter for a video-capable asset, and `AssetSessionController#disengage`'s own contract makes an
 * "End session" button actively misleading while live: it would only *demote* the usage's origin
 * back to `STREAM` (a running stream must always have somewhere to record telemetry against), not
 * end anything an operator watching this drawer would recognise as "ending". `'none'` also whenever
 * the asset has no `TELEMETRY` device at all — nothing this button could open would ever make the
 * command panel commandable.
 *
 * Otherwise: `'end'` once `operatorEngaged` is true, else `'engage'`. `operatorEngaged` is not a
 * local "I clicked it" flag — there is no `GET .../session` read endpoint
 * (`VisionApi#engageAssetSession`'s own doc comment), so the caller derives it honestly from the
 * asset's own polled `recentUsages`: an open usage (`selectOpenUsage`) whose `origin === 'OPERATOR'`
 * (`CockpitFacade#operatorEngaged`). CLAUDE.md's "degrade honestly" rule, applied to a state chip
 * exactly like every other one on this drawer.
 */
export function resolveSessionAffordance(
  hasTelemetryDevice: boolean,
  live: boolean,
  operatorEngaged: boolean,
): SessionAffordance {
  if (!hasTelemetryDevice || live) {
    return 'none';
  }
  return operatorEngaged ? 'end' : 'engage';
}
