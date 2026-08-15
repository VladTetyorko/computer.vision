import type { ManualControlChannelBinding } from '../../core/api/models';
import type { ManualControlEngageState } from '../../core/rc/manual-control-client';

/**
 * Pure, component-adjacent logic behind `rc-monitor.ts`'s "Take control" section
 * (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md R5) — mirrors `flight-command-panel-logic.ts`'s own split (a
 * routed feature's gating/copy rules live beside their one component, promoted to `core/` only if
 * a second consumer ever needs them).
 */

export interface EngageGateInput {
  readonly hasAsset: boolean;
  readonly canCommand: boolean;
  readonly gamepadSupported: boolean;
  readonly gamepadConnected: boolean;
  readonly engageState: ManualControlEngageState;
}

/**
 * The Take-control button's poka-yoke reason (docs/plans/done/UX-REWORK-PLAN.md §U-a2 rule 1 — "disabled with
 * the reason inline, never enabled-then-error", applied to R5's engage gesture; mirrors
 * `flight-command-panel-logic.ts#canShowCommandPanel`'s own "reuse the same gate the flight panel
 * uses" instruction, extended with the two things unique to RC: the Gamepad API's own support/
 * connection state). `undefined` = enabled. Checked in priority order — the most fundamental
 * blocker first, so a caller with several problems at once sees the one to fix, not a vague
 * catch-all.
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
  if (!input.gamepadSupported) {
    return "This browser doesn't expose gamepad input.";
  }
  if (!input.gamepadConnected) {
    return 'Plug your transmitter in first.';
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
