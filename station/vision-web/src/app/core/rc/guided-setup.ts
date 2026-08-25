import { movedControl } from './controller-setup-logic';
import type { ControlFunction, ControlProfile, ControlSource, ControlTravel } from '../api/models';

/**
 * The guided "tell me your sticks" run (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C15) — a
 * pure reducer over the 60 Hz gamepad stream, like `control-autodetect.ts` and for the same reason:
 * its interesting decisions are testable by feeding it frames instead of by waiting.
 *
 * <h2>Why a second detection mode</h2>
 * Autodetect answers *what a control is* — an axis, a 3-position switch — and leaves the operator a
 * row per control with the function, channel and travel still to fill in. That is the long half of
 * the job, repeated once per control. This run inverts it: the page names a function it needs
 * ("move the control you use for throttle"), watches for whichever control moves, and binds it with
 * the channel and travel the **built-in for that vehicle kind** already uses. The operator answers
 * questions about their own transmitter instead of about RC channel numbers.
 *
 * <h2>The steps come from the built-in, never from a list in here</h2>
 * A rover needs steering and throttle; a copter needs four sticks. Reading that off the built-in
 * profile (which the backend serves) keeps this file from being a second, quietly diverging opinion
 * about what each vehicle kind flies with.
 */

/** One thing the run asks for, and the binding it will write when the operator answers. */
export interface GuidedStep {
  readonly function: ControlFunction;
  /** The function in the catalogue's words, e.g. `Throttle`. */
  readonly label: string;
  readonly rcChannel: number;
  readonly travel: ControlTravel;
}

/**
 * What the run asks for, in the built-in's own channel order.
 *
 * Only channel bindings become steps: the built-ins deliberately bind no switches (ArduPilot's own
 * advice is that a joystick should not own the mode or aux channels), so there is nothing to ask
 * about, and asking anyway would be inventing controls the operator may not have.
 */
export function guidedSteps(
  builtIn: ControlProfile | undefined,
  functionLabels: ReadonlyMap<ControlFunction, string>,
): readonly GuidedStep[] {
  return [...(builtIn?.channelMap ?? [])]
    .sort((a, b) => a.rcChannel - b.rcChannel)
    .map((binding) => ({
      function: binding.function,
      label: functionLabels.get(binding.function) ?? binding.function,
      rcChannel: binding.rcChannel,
      travel: binding.centerMicros === binding.minMicros ? 'UNIDIRECTIONAL' : 'CENTERED',
    }));
}

interface Readings {
  readonly axes: readonly number[];
  readonly buttons: readonly number[];
}

/**
 * `watch` is waiting for the operator to move something; `settle` is waiting for them to let go.
 *
 * The settle phase is not politeness — a self-centring stick springs back the instant it is
 * released, and a run that re-armed immediately would read that spring-back as the answer to the
 * *next* question and bind the same stick to two functions in a row.
 */
export type GuidedPhase = 'off' | 'watch' | 'settle';

export interface GuidedState {
  readonly phase: GuidedPhase;
  readonly steps: readonly GuidedStep[];
  readonly index: number;
  /** What the inputs read when this step started watching — what movement is measured against. */
  readonly baseline: Readings;
  /** The previous frame, so `settle` can tell "still moving" from "let go". */
  readonly last: Readings;
  /** Consecutive quiet frames seen so far in `settle`. */
  readonly quiet: number;
}

/** What one control just told the run, on the frame it decided. */
export interface GuidedLearned {
  readonly source: ControlSource;
  readonly sourceIndex: number;
  readonly step: GuidedStep;
}

export interface GuidedResult {
  readonly state: GuidedState;
  readonly learned?: GuidedLearned;
}

/** Frames of stillness that count as "let go". ~0.15 s at 60 Hz — long enough for a stick to stop bouncing. */
export const SETTLE_TICKS = 9;

/**
 * How still is still. Well under `movedControl`'s own 0.5 threshold, so a control that is merely
 * jittering at rest counts as quiet while one still travelling does not.
 */
const QUIET_THRESHOLD = 0.08;

const NO_READINGS: Readings = { axes: [], buttons: [] };

export const GUIDED_OFF: GuidedState = {
  phase: 'off',
  steps: [],
  index: 0,
  baseline: NO_READINGS,
  last: NO_READINGS,
  quiet: 0,
};

function snapshot(readings: Readings): Readings {
  return { axes: [...readings.axes], buttons: [...readings.buttons] };
}

/** Starts a run, or returns {@link GUIDED_OFF} when there is nothing to ask about. */
export function beginGuided(steps: readonly GuidedStep[], readings: Readings): GuidedState {
  if (steps.length === 0) {
    return GUIDED_OFF;
  }
  const now = snapshot(readings);
  return { phase: 'watch', steps, index: 0, baseline: now, last: now, quiet: 0 };
}

/** The question on screen right now, or `undefined` once every step has been answered. */
export function currentStep(state: GuidedState): GuidedStep | undefined {
  return state.phase === 'off' ? undefined : state.steps[state.index];
}

/** Whether the run has answered every step — the state that earns a "done" message rather than a prompt. */
export function isGuidedDone(state: GuidedState): boolean {
  return state.phase !== 'off' && state.index >= state.steps.length;
}

function nextStep(state: GuidedState, readings: Readings): GuidedState {
  const now = snapshot(readings);
  return { ...state, phase: 'settle', index: state.index + 1, baseline: now, last: now, quiet: 0 };
}

function isQuiet(readings: Readings, last: Readings): boolean {
  const axesQuiet = readings.axes.every((v, i) => Math.abs(v - (last.axes[i] ?? 0)) < QUIET_THRESHOLD);
  return axesQuiet && readings.buttons.every((v, i) => Math.abs(v - (last.buttons[i] ?? 0)) < QUIET_THRESHOLD);
}

/**
 * One frame of the run.
 *
 * @return the next state, plus the control this frame bound — `learned` is set on exactly the frame
 *         a step is answered, so a caller can write the binding without diffing states
 */
export function advanceGuided(state: GuidedState, readings: Readings): GuidedResult {
  if (state.phase === 'off' || isGuidedDone(state)) {
    return { state };
  }
  if (state.phase === 'settle') {
    const quiet = isQuiet(readings, state.last) ? state.quiet + 1 : 0;
    const settled = quiet >= SETTLE_TICKS;
    const now = snapshot(readings);
    return {
      state: {
        ...state,
        phase: settled ? 'watch' : 'settle',
        quiet: settled ? 0 : quiet,
        baseline: now,
        last: now,
      },
    };
  }
  const moved = movedControl(readings.axes, readings.buttons, state.baseline);
  if (!moved) {
    return { state: { ...state, last: snapshot(readings) } };
  }
  const step = state.steps[state.index];
  return { state: nextStep(state, readings), learned: { ...moved, step } };
}

/**
 * Moves past the step on screen without binding anything — the answer to "my transmitter has no
 * such control", which on a rover is a real answer and not a mistake.
 */
export function skipGuided(state: GuidedState, readings: Readings): GuidedState {
  return state.phase === 'off' || isGuidedDone(state) ? state : nextStep(state, readings);
}
