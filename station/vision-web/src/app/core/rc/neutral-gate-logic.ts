import type { ManualControlChannelBinding } from '../api/models';
import type { ManualControlEngageState } from './manual-control-client';
import { displayPercentFor } from './control-surface-logic';

/**
 * The neutral-stick arm gate (docs/plans/active/FLY-CONTROL-UX-PLAN.md §2) — pure, Angular-free so
 * it unit-tests without a browser, mirroring `control-surface-logic.ts`'s own split. Kept as its own
 * file rather than folded into that one: this needs `ManualControlEngageState`
 * (`manual-control-client.ts`), a session concept `control-surface-logic.ts` deliberately knows
 * nothing about (that file's own doc comment: "frame-free … Nothing in this file knows what a
 * multirotor is").
 *
 * **Gate law (§2, frozen):** applies only while a take-control session is engaging/engaged — that is
 * when `RcSource` axes are actually live and bound to something. No session, no gate: today's
 * behavior is unchanged, and the flight controller's own pre-arm interlock ("Throttle too high")
 * still stands regardless of anything this file computes. Per bound axis, using the profile's own
 * travel semantics: `CENTERED` → `|displayPercent − 50| ≤ tolerancePercent`; `UNIDIRECTIONAL` →
 * `displayPercent ≤ tolerancePercent`. An axis with no binding at all is ignored, never treated as
 * "at rest" or "off" — there is nothing to be neutral about.
 */

/** One axis reading outside its own neutral tolerance. */
export interface NeutralOffender {
  readonly binding: ManualControlChannelBinding;
  /** The live normalized value (`[-1,1]` centred, `[0,1]` unidirectional) the offender was found at. */
  readonly value: number;
}

/**
 * The worst axis currently off neutral, or `undefined` when every bound axis reads within
 * `tolerancePercent` of its own rest point (or there are no axis bindings at all).
 *
 * "Worst" is ranked by how far off *its own* travel the axis sits, as a fraction of that travel's
 * full swing away from rest (a centred control's half-range is 50 display points; a unidirectional
 * throttle's full range is 100) — not by raw `displayPercent` distance, which would unfairly favor
 * flagging a unidirectional throttle over a centred stick sitting the same *proportional* amount off
 * rest, since a throttle's rest-to-full span is twice a centred control's rest-to-either-end span.
 *
 * @param channelMap the engaged frame's own bindings (only `source === 'AXIS'` entries are read —
 *   switches/buttons have no notion of "neutral")
 * @param axes the live `axesFrom`-shaped array, indexed by each binding's own `sourceIndex`
 * @param tolerancePercent `ThresholdsStore.rc().neutralTolerancePercent` — how many display-percent
 *   points of slop counts as "still at rest"
 */
export function worstNeutralOffender(
  channelMap: readonly ManualControlChannelBinding[],
  axes: readonly number[],
  tolerancePercent: number,
): NeutralOffender | undefined {
  let worst: NeutralOffender | undefined;
  let worstFraction = -1;

  for (const binding of channelMap) {
    if (binding.source !== 'AXIS') {
      continue;
    }
    const value = axes[binding.sourceIndex] ?? 0;
    const displayPercent = displayPercentFor(binding.travel, value);
    const restPoint = binding.travel === 'UNIDIRECTIONAL' ? 0 : 50;
    const span = binding.travel === 'UNIDIRECTIONAL' ? 100 : 50;
    const deviation = Math.abs(displayPercent - restPoint);
    if (deviation <= tolerancePercent) {
      continue; // within tolerance — this axis is not an offender.
    }
    const fraction = deviation / span;
    if (fraction > worstFraction) {
      worstFraction = fraction;
      worst = { binding, value };
    }
  }
  return worst;
}

/**
 * The sticks reason string for one offender, per §2's own frozen wording — states the axis's own
 * label and live value, never a generic "center your sticks" that leaves the operator guessing
 * which one. `UNIDIRECTIONAL` travel is domain-guaranteed to only ever be a copter/plane throttle
 * (`ControlTravel`'s own doc comment in `core/api/models.ts`), so "throttle to zero to arm" is safe
 * to state literally rather than derive from the binding's own label.
 */
export function neutralOffenderReason(offender: NeutralOffender): string {
  const percent = displayPercentFor(offender.binding.travel, offender.value);
  return offender.binding.travel === 'UNIDIRECTIONAL'
    ? `${offender.binding.label} ${percent}% — throttle to zero to arm`
    : `${offender.binding.label} ${percent}% — center sticks to arm`;
}

/**
 * The Arm gate's "sticks not neutral" half — `undefined` whenever there is nothing to say: no live
 * session (`engageState` is `'idle'`/`'denied'`/`'released'`), or every bound axis is already at
 * rest. Composed with the grounding reason (grounding wins) by
 * `flight-command-panel-logic.ts#armDisableReason`, never here — this file stays ignorant of
 * grounding, exactly as `control-surface-logic.ts` stays ignorant of vehicle kind.
 */
export function neutralGateReason(
  engageState: ManualControlEngageState,
  channelMap: readonly ManualControlChannelBinding[],
  axes: readonly number[],
  tolerancePercent: number,
): string | undefined {
  if (engageState !== 'engaging' && engageState !== 'engaged') {
    return undefined;
  }
  const offender = worstNeutralOffender(channelMap, axes, tolerancePercent);
  return offender ? neutralOffenderReason(offender) : undefined;
}
