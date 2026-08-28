import type { ControlFunction, ControlTravel, ManualControlChannelBinding, VehicleKind } from '../api/models';

/**
 * Pure geometry and value math behind the on-screen control surface
 * (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P10-P12) — frame-free and
 * dependency-free so it unit-tests without a browser or `TestBed`, the same split
 * `core/rc/rc-input-logic.ts` and `core/rc/manual-control-logic.ts` already use.
 *
 * **Everything here is driven by the server's own `engaged.channelMap`.** Nothing in this file
 * knows what a multirotor is: the pads, their labels, where each control rests and whether it
 * springs back are all read off the bindings the backend chose for the vehicle it is actually
 * hearing. That is the whole point — a hardcoded two-stick layout would be the same airframe
 * assumption one layer up.
 */

const clamp = (v: number, lo: number, hi: number): number => Math.min(hi, Math.max(lo, v));

/** One pad: up to two bindings, one per screen axis. A rover gets exactly one; an aircraft two. */
export interface ControlPad {
  readonly id: string;
  /** Horizontal binding, or `undefined` for a vertical-only control. */
  readonly x?: ManualControlChannelBinding;
  /** Vertical binding, or `undefined` for a horizontal-only control. */
  readonly y?: ManualControlChannelBinding;
  /** e.g. `"Throttle / Steering"` — built from the bindings' own labels, never invented here. */
  readonly label: string;
}

/**
 * Which two functions share a pad, tried in order. A pad is created only when the profile actually
 * binds *both* of its functions, so a rover — which binds steering and throttle and nothing else —
 * yields exactly one pad and an aircraft yields two.
 *
 * `['STEERING', 'THROTTLE']` sits before `['ROLL', 'PITCH']` deliberately: a ground vehicle's two
 * controls belong on one pad (steer across, drive up/down), which is how anyone who has held a
 * game controller expects to drive a car.
 */
const PAD_LAYOUTS: readonly (readonly [ControlFunction, ControlFunction])[] = [
  ['YAW', 'THROTTLE'],
  ['STEERING', 'THROTTLE'],
  ['ROLL', 'PITCH'],
];

/**
 * Groups the engaged channel map into pads.
 *
 * Any axis binding no layout claims still gets its own single-axis pad rather than being dropped —
 * a profile this UI has not been taught about is rendered incompletely, never silently ignored.
 * Buttons are skipped: no profile binds one (arm/disarm/mode go through the flight-command panel).
 */
export function padsFrom(bindings: readonly ManualControlChannelBinding[]): readonly ControlPad[] {
  const axes = bindings.filter((b) => b.source === 'AXIS');
  const claimed = new Set<ControlFunction>();
  const pads: ControlPad[] = [];

  for (const [xFn, yFn] of PAD_LAYOUTS) {
    const x = axes.find((b) => b.function === xFn);
    const y = axes.find((b) => b.function === yFn);
    if (x && y && !claimed.has(xFn) && !claimed.has(yFn)) {
      claimed.add(xFn);
      claimed.add(yFn);
      pads.push({ id: `${xFn}-${yFn}`, x, y, label: `${y.label} / ${x.label}` });
    }
  }

  for (const binding of axes) {
    if (!claimed.has(binding.function)) {
      claimed.add(binding.function);
      pads.push({ id: binding.function, x: binding, label: binding.label });
    }
  }
  return pads;
}

/**
 * The value a control sits at when nothing is touching it, in the same normalized units the
 * `channels` frame carries. `0` for both travels — the backend's `ControlBinding#toMicros` maps it
 * to `centerMicros` for a centred control and to `minMicros` for a unidirectional one, so "rest"
 * means stop on a rover and idle on a copter without this file needing to know which.
 */
export const REST_VALUE = 0;

/**
 * Whether letting go returns this control to rest.
 *
 * Centred controls spring back — a released steering stick must straighten, and a released rover
 * throttle must stop. A unidirectional throttle **holds** where it was put, exactly as a
 * multirotor's throttle stick does: springing it to idle mid-flight would drop the aircraft.
 */
export function springsBack(binding: ManualControlChannelBinding): boolean {
  return binding.travel === 'CENTERED';
}

/**
 * A control's position as the operator reads it, 0..100 — the operator's own framing: a drone's
 * throttle is 0-100 with rest at 0; a car's is 50 at stop, 50→0 reverse, 50→100 forward.
 *
 * @param binding the binding being displayed
 * @param value   its current normalized value (`[-1,1]` centred, `[0,1]` unidirectional)
 */
export function displayPercent(binding: ManualControlChannelBinding, value: number): number {
  return displayPercentFor(binding.travel, value);
}

/**
 * {@link displayPercent}'s own math, taking `travel` directly rather than a whole binding — the
 * setup wizard's live detection gauge (docs/plans/active/CONTROLLER-UX-PLAN.md §2.3, wave X4) shows
 * a percent, and where rest sits, for a control it is *in the middle of binding*: there is no
 * {@link ManualControlChannelBinding} yet, only the travel the operator is about to choose for it.
 */
export function displayPercentFor(travel: ControlTravel, value: number): number {
  return travel === 'UNIDIRECTIONAL' ? Math.round(clamp(value, 0, 1) * 100) : Math.round((clamp(value, -1, 1) + 1) * 50);
}

/**
 * Where the pad's knob sits horizontally, as a percent of the pad's width. Identical math to
 * {@link displayPercent} — a control's readout and its knob are the same quantity, so they cannot
 * drift apart.
 */
export function knobLeftPercent(binding: ManualControlChannelBinding | undefined, value: number): number {
  return binding ? displayPercent(binding, value) : 50;
}

/** Where the knob sits vertically. Screen Y grows downward, so a full control sits at the top. */
export function knobTopPercent(binding: ManualControlChannelBinding | undefined, value: number): number {
  return binding ? 100 - displayPercent(binding, value) : 50;
}

/**
 * Turns a pointer position into a control value.
 *
 * @param binding  the binding being driven
 * @param fraction where the pointer is along the control's travel, `0` at the low end and `1` at
 *                 the high end (the caller flips screen Y before calling)
 */
export function valueFromFraction(binding: ManualControlChannelBinding, fraction: number): number {
  const f = clamp(fraction, 0, 1);
  return binding.travel === 'UNIDIRECTIONAL' ? f : f * 2 - 1;
}

/**
 * Assembles the `axes` array the `channels` frame carries, placing each value at the
 * `sourceIndex` the server said it reads from.
 *
 * Sized to the highest bound index, and every unbound slot is {@link REST_VALUE} — a hole in the
 * array would otherwise reach the backend as a real `0.0` reading for a control this surface never
 * rendered.
 */
export function axesFrom(
  values: ReadonlyMap<number, number>,
  bindings: readonly ManualControlChannelBinding[],
): readonly number[] {
  const axisBindings = bindings.filter((b) => b.source === 'AXIS');
  if (axisBindings.length === 0) {
    return [];
  }
  const size = Math.max(...axisBindings.map((b) => b.sourceIndex)) + 1;
  const axes = new Array<number>(size).fill(REST_VALUE);
  for (const binding of axisBindings) {
    axes[binding.sourceIndex] = values.get(binding.sourceIndex) ?? REST_VALUE;
  }
  return axes;
}

/**
 * The caveat to show above the surface, or `undefined` when there is nothing to warn about.
 *
 * Only `'UNKNOWN'` earns one, and it is stated as a fact about the vehicle rather than a scolding:
 * the platform did not recognize what it is, so it kept the historical centred map instead of
 * guessing — which means the throttle rests at mid-travel, and on a multirotor that is not idle
 * (docs/plans/active/VEHICLE-CONTROL-PROFILES-CONTEXT.md §2 P8).
 */
export function profileCaveat(kind: VehicleKind): string | undefined {
  return kind === 'UNKNOWN'
    ? 'This vehicle did not report what it is, so a generic centred layout is in use — the throttle rests at mid-travel, not at idle. Check it matches your machine before you drive it.'
    : undefined;
}

/**
 * Where a pointer sits along one axis of a pad, `0` at the low edge and `1` at the high edge.
 *
 * Takes plain numbers rather than a `DOMRect` so it stays testable without a browser; the caller
 * flips the result for screen Y, which grows downward.
 *
 * @param position the pointer's coordinate (`clientX`/`clientY`)
 * @param start    the pad's own edge coordinate (`rect.left`/`rect.top`)
 * @param size     the pad's extent (`rect.width`/`rect.height`)
 */
export function fractionAlong(position: number, start: number, size: number): number {
  return size > 0 ? clamp((position - start) / size, 0, 1) : 0.5;
}

/**
 * How far one arrow-key press moves a control, as a fraction of its full travel.
 *
 * Dragging a finger across a pad is far too coarse to set a hover throttle; the keyboard is the
 * precise path, and 2% of travel is roughly one click of a physical throttle's detent.
 */
export const KEY_STEP = 0.02;

/**
 * Moves a control by `delta` of its full travel, clamped to the range its travel allows — so a
 * unidirectional throttle cannot be nudged below idle, and a centred control cannot pass its stops.
 *
 * @param binding the binding being nudged
 * @param value   its current normalized value
 * @param delta   the step, as a fraction of full travel (positive = toward the high end)
 */
export function nudge(binding: ManualControlChannelBinding, value: number, delta: number): number {
  const span = binding.travel === 'UNIDIRECTIONAL' ? 1 : 2;
  const moved = value + delta * span;
  return binding.travel === 'UNIDIRECTIONAL' ? clamp(moved, 0, 1) : clamp(moved, -1, 1);
}
