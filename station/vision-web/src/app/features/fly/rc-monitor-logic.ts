import type { ManualControlChannelBinding } from '../../core/api/models';
import type { ManualControlEngageState } from '../../core/rc/manual-control-client';
import type { RcSourceKind } from '../../core/rc/rc-source.service';

/**
 * Pure, component-adjacent logic behind `rc-monitor.ts`'s "Take control" section
 * (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md R5) — mirrors `flight-command-panel-logic.ts`'s own split (a
 * routed feature's gating/copy rules live beside their one component, promoted to `core/` only if
 * a second consumer ever needs them).
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

/** One `engaged.channelMap` row's display line, e.g. `"Roll → CH1"`. */
export function channelBindingLabel(binding: ManualControlChannelBinding): string {
  return `${binding.label} → CH${binding.rcChannel}`;
}
