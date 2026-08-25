import type { ControlFunction, ControlInputKind, ControlSource } from '../api/models';

/**
 * Pure layout + classification logic behind the controller diagram and its Autodetect gesture
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md, post-merge follow-up).
 *
 * Angular-free on purpose, like every other `*-logic.ts` in `core/`: what a transmitter's axes
 * *mean* visually, and what a control *is*, are both decidable from numbers alone, and both are much
 * easier to be sure about in a unit test than in a browser with a radio plugged in.
 */

/** How far from a detent a reading may sit and still count as resting on it. */
const DETENT_TOLERANCE = 0.12;

/** The three places a switched axis rests: down, centre, up. */
const DETENTS = [-1, 0, 1] as const;

/**
 * Which stick carries which control (manual.edgetx.org, rc-airplane-world.com/rc-transmitter-modes).
 * Mode 2 is the common default; 3 mirrors 2 and 4 mirrors 1, for left-handed operators.
 */
export type StickMode = 1 | 2 | 3 | 4;

export const DEFAULT_STICK_MODE: StickMode = 2;

export type StickSide = 'LEFT' | 'RIGHT';

/** What one stick's two directions do. */
export interface StickAssignment {
  readonly horizontal: ControlFunction;
  readonly vertical: ControlFunction;
}

export interface ModeLayout {
  readonly left: StickAssignment;
  readonly right: StickAssignment;
}

/** The picker's own entries, so the page invents no labels. */
export const STICK_MODES: readonly { readonly mode: StickMode; readonly label: string }[] = [
  { mode: 1, label: 'Mode 1 — throttle right' },
  { mode: 2, label: 'Mode 2 — throttle left' },
  { mode: 3, label: 'Mode 3 — throttle right, mirrored' },
  { mode: 4, label: 'Mode 4 — throttle left, mirrored' },
];

/**
 * The four standard stick modes, in this app's function vocabulary (aileron = roll, elevator =
 * pitch, rudder = yaw).
 *
 * <h2>Mode is a drawing decision, not a control one</h2>
 * What the vehicle does is decided entirely by axis → function → channel in the layout itself. Mode
 * only says which of the two pads a given function is drawn on, which is why it is a per-operator
 * display preference and not a field on the saved profile: changing it moves a picture, never a
 * stick.
 */
export function stickModeLayout(mode: StickMode): ModeLayout {
  switch (mode) {
    case 1:
      return { left: { horizontal: 'YAW', vertical: 'PITCH' }, right: { horizontal: 'ROLL', vertical: 'THROTTLE' } };
    case 3:
      return { left: { horizontal: 'ROLL', vertical: 'PITCH' }, right: { horizontal: 'YAW', vertical: 'THROTTLE' } };
    case 4:
      return { left: { horizontal: 'ROLL', vertical: 'THROTTLE' }, right: { horizontal: 'YAW', vertical: 'PITCH' } };
    default:
      return { left: { horizontal: 'YAW', vertical: 'THROTTLE' }, right: { horizontal: 'ROLL', vertical: 'PITCH' } };
  }
}

/** One axis placed in a pad slot. */
export interface PadAxis {
  readonly axis: number;
  readonly function: ControlFunction;
}

export interface StickPad {
  readonly side: StickSide;
  readonly label: string;
  readonly horizontal?: PadAxis;
  readonly vertical?: PadAxis;
}

export interface DiagramLayout {
  /** The pads that have at least one axis on them — a pad nothing is bound to is not drawn. */
  readonly sticks: readonly StickPad[];
  /** Every axis no pad claimed, drawn as a labelled bar (pots, sliders, switches on axes). */
  readonly bars: readonly number[];
}

/**
 * Places every axis, either on a stick pad or on a bar.
 *
 * <h2>Pads come from the layout, never from the axis numbers</h2>
 * The first version of this paired axes 0/1 and 2/3 into pads, on the gamepad convention that
 * consecutive axes are a stick. A transmitter does not work that way: EdgeTX in USB-joystick mode
 * reports <em>channels</em> in channel order — CH1→X, CH2→Y, CH3→Z, CH4→RX — so with the usual AETR
 * order axes 0..3 are aileron, elevator, throttle, rudder. Under that convention the "sticks" 0/1
 * and 2/3 are roll+pitch and throttle+yaw, which happen to be the two real sticks in mode 2 — but
 * with throttle landing in a <em>horizontal</em> slot and the two pads drawn the wrong way round.
 * Hence: look up which axis is bound to each function, and let {@link stickModeLayout} say where
 * that function is held.
 *
 * An axis bound to `STEERING` fills the `ROLL` slot — a ground vehicle's steering is the same stick
 * direction, and the alternative is a rover whose only stick axis is drawn as a bar.
 *
 * With nothing bound to any stick function (an empty draft), every axis is a bar. That is the
 * honest answer: without a layout there is no way to know which axis is a stick, and a guessed pad
 * is exactly the bug this function was rewritten to fix.
 */
export function diagramLayout(
  axisCount: number,
  functions: ReadonlyMap<number, ControlFunction>,
  mode: StickMode,
): DiagramLayout {
  const assignment = stickModeLayout(mode);
  const claimed = new Set<number>();
  const left = pad('LEFT', 'Left stick', assignment.left, functions, axisCount, claimed);
  const right = pad('RIGHT', 'Right stick', assignment.right, functions, axisCount, claimed);

  const bars: number[] = [];
  for (let axis = 0; axis < axisCount; axis += 1) {
    if (!claimed.has(axis)) {
      bars.push(axis);
    }
  }
  return { sticks: [left, right].filter((p): p is StickPad => p !== undefined), bars };
}

function pad(
  side: StickSide,
  label: string,
  assignment: StickAssignment,
  functions: ReadonlyMap<number, ControlFunction>,
  axisCount: number,
  claimed: Set<number>,
): StickPad | undefined {
  const horizontal = slot(assignment.horizontal, functions, axisCount, claimed);
  const vertical = slot(assignment.vertical, functions, axisCount, claimed);
  return horizontal || vertical ? { side, label, horizontal, vertical } : undefined;
}

function slot(
  wanted: ControlFunction,
  functions: ReadonlyMap<number, ControlFunction>,
  axisCount: number,
  claimed: Set<number>,
): PadAxis | undefined {
  for (let axis = 0; axis < axisCount; axis += 1) {
    const bound = functions.get(axis);
    if (bound === undefined || claimed.has(axis) || !fills(bound, wanted)) {
      continue;
    }
    claimed.add(axis);
    return { axis, function: bound };
  }
  return undefined;
}

/** Whether a bound function belongs in a slot the mode calls `wanted`. */
function fills(bound: ControlFunction, wanted: ControlFunction): boolean {
  return bound === wanted || (wanted === 'ROLL' && bound === 'STEERING');
}

/** `[-1,1]` → `[0,100]` left-to-right, where a bar fill or a pad dot sits horizontally. */
export function axisToOffsetPercent(value: number): number {
  const clamped = Math.min(1, Math.max(-1, value));
  return ((clamped + 1) / 2) * 100;
}

/**
 * Where a vertical reading sits, measured from the top.
 *
 * <h2>Which end of the travel is "up" is a property of the hardware</h2>
 * A gamepad reports its Y axes negative-up. A transmitter in USB-joystick mode reports a channel,
 * and a channel rises as the stick rises — so throttle at the bottom reads −1 and, drawn on the
 * gamepad convention, appears at the <em>top</em>. Both conventions are in use and neither is
 * discoverable from the browser, so this takes the answer as an argument and the page keeps it as a
 * preference. It defaults to the transmitter convention, since a transmitter is what this page is
 * for. Horizontal needs no such flag: both conventions agree that positive is right.
 */
export function verticalOffsetPercent(value: number, positiveIsUp: boolean): number {
  const offset = axisToOffsetPercent(value);
  return positiveIsUp ? 100 - offset : offset;
}

/** Which detent a reading is resting on, or `undefined` when it is between them. */
function detentAt(value: number): number | undefined {
  return DETENTS.find((detent) => Math.abs(value - detent) <= DETENT_TOLERANCE);
}

/**
 * What kind of control produced these readings.
 *
 * A stick sweeps: it is seen at values no detent explains, so it is continuous. A switch only ever
 * reports its detents, so the number of *distinct* detents it was seen at is what it has: three is a
 * 3-position switch, two is a 2-position one.
 *
 * Two deliberate refusals to guess:
 * - **One detent is not a switch.** A control seen resting at a single place says nothing about how
 *   many places it has, so it comes back `AXIS` — the kind that drives a channel, which is both the
 *   more common answer and the one whose mistake is visible immediately rather than at arm time.
 * - **Two detents including the centre** (a 3-position switch only moved half its travel) comes back
 *   `SWITCH_2`, because that is genuinely all that was observed. The operator changes it in one
 *   dropdown; inventing the third position for them would be inventing a reading.
 */
export function classifyAxisSamples(samples: readonly number[]): ControlInputKind {
  if (samples.length === 0) {
    return 'AXIS';
  }
  const detents = new Set<number>();
  for (const sample of samples) {
    const detent = detentAt(sample);
    if (detent === undefined) {
      return 'AXIS';
    }
    detents.add(detent);
  }
  if (detents.size >= 3) {
    return 'SWITCH_3';
  }
  return detents.size === 2 ? 'SWITCH_2' : 'AXIS';
}

/**
 * What kind a control of this source is, given what it was seen doing.
 *
 * A control on the buttons array is reported as `BUTTON`, always: within one observation window a
 * momentary button and a latching toggle are indistinguishable — both read 0 then 1 — and calling a
 * button a 2-position switch on a coin flip is worse than calling it what the browser calls it.
 */
export function classifyControl(source: ControlSource, samples: readonly number[]): ControlInputKind {
  return source === 'BUTTON' ? 'BUTTON' : classifyAxisSamples(samples);
}
