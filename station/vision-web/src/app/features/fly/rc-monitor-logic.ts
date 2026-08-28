import type { ActionBinding, ControlAction } from '../../core/api/models';
import type { ManualControlEngageState } from '../../core/rc/manual-control-client';
import type { RcSourceKind } from '../../core/rc/rc-source.service';
import { controlLabel } from '../../core/rc/control-action-logic';

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
 * for this drawer.
 */
export function armedChip(armed: boolean | undefined): ArmedChip {
  if (armed === undefined) {
    return { text: '—', tone: 'neutral' };
  }
  return armed ? { text: 'ARMED', tone: 'ok' } : { text: 'DISARMED', tone: 'neutral' };
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
