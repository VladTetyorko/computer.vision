import { classifyAxisSamples } from './controller-diagram-logic';
import { movedControl } from './controller-setup-logic';
import type { ControlInputKind, ControlSource } from '../api/models';

/**
 * Autodetect: sweep a whole transmitter by moving it, not by clicking through it
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md, post-merge follow-up).
 *
 * <h2>Why a reducer and not a service</h2>
 * The gesture is a state machine driven by a 60Hz sample stream, and the interesting part — "how
 * long do we watch before deciding, and what do we decide" — is a decision about numbers. Written as
 * a pure step function it is testable by feeding it frames; written as an Angular service with
 * timers it is testable by waiting. Everything Angular (the effect that pumps it, the row it adds)
 * stays in the page.
 *
 * <h2>The loop</h2>
 * Watch for any control to move → sample <em>that</em> control for a window → name it → re-baseline
 * and watch again. It never stops on its own: an operator sweeping a 16-switch radio should flick,
 * flick, flick without touching the mouse, and the only thing that ends the run is saying so.
 */

/** Ticks of the gamepad stream to watch one control before deciding what it is (~1.5s at 60Hz). */
export const SAMPLE_TICKS = 90;

/** One frame of the transmitter. */
export interface Readings {
  readonly axes: readonly number[];
  readonly buttons: readonly number[];
}

export type AutodetectPhase = 'off' | 'watching' | 'sampling';

export interface AutodetectState {
  readonly phase: AutodetectPhase;
  readonly baseline: Readings;
  /** The control currently being watched, while sampling. */
  readonly target?: { readonly source: ControlSource; readonly sourceIndex: number };
  readonly samples: readonly number[];
}

/** A control Autodetect has finished naming. */
export interface LearnedControl {
  readonly source: ControlSource;
  readonly sourceIndex: number;
  readonly kind: ControlInputKind;
}

export interface AutodetectStep {
  readonly state: AutodetectState;
  /** Set on the one tick a control is named; the caller adds the row and shows it. */
  readonly learned?: LearnedControl;
}

export const AUTODETECT_OFF: AutodetectState = {
  phase: 'off',
  baseline: { axes: [], buttons: [] },
  samples: [],
};

/** Starts a run from where the transmitter is sitting right now. */
export function beginAutodetect(readings: Readings): AutodetectState {
  return { phase: 'watching', baseline: snapshot(readings), samples: [] };
}

/**
 * Advances one frame.
 *
 * Two shortcuts, both of which only ever produce the answer the full window would have produced:
 * - **A button is named the moment it is pressed.** No sampling window can tell a momentary button
 *   from a latching toggle, so waiting only makes the operator wait.
 * - **An axis is named the moment it reads between detents.** A single off-detent sample already
 *   forces `AXIS` (`classifyAxisSamples`), so a swept stick resolves in a few frames while a switch
 *   still gets the full window it needs to reveal whether it has a third position.
 */
export function stepAutodetect(state: AutodetectState, readings: Readings): AutodetectStep {
  if (state.phase === 'off') {
    return { state };
  }
  if (state.phase === 'watching') {
    return startWatching(state, readings);
  }
  return continueSampling(state, readings);
}

function startWatching(state: AutodetectState, readings: Readings): AutodetectStep {
  const moved = movedControl(readings.axes, readings.buttons, state.baseline);
  if (!moved) {
    return { state };
  }
  if (moved.source === 'BUTTON') {
    return learn(readings, { ...moved, kind: 'BUTTON' });
  }
  const from = state.baseline.axes[moved.sourceIndex] ?? 0;
  const now = readings.axes[moved.sourceIndex] ?? 0;
  return decide({ ...state, phase: 'sampling', target: moved, samples: [from, now] }, readings);
}

function continueSampling(state: AutodetectState, readings: Readings): AutodetectStep {
  const target = state.target;
  if (!target) {
    return { state: { ...state, phase: 'watching', samples: [] } };
  }
  const samples = [...state.samples, readings.axes[target.sourceIndex] ?? 0];
  return decide({ ...state, samples }, readings);
}

/** Names the target if the window is over, or an off-detent reading has already settled it. */
function decide(state: AutodetectState, readings: Readings): AutodetectStep {
  const target = state.target;
  if (!target) {
    return { state };
  }
  const kind = classifyAxisSamples(state.samples);
  const settled = kind === 'AXIS' && state.samples.length > 1;
  if (settled || state.samples.length >= SAMPLE_TICKS) {
    return learn(readings, { ...target, kind });
  }
  return { state };
}

/** Records what was found and rearms from wherever the transmitter now sits. */
function learn(readings: Readings, learned: LearnedControl): AutodetectStep {
  return { state: beginAutodetect(readings), learned };
}

function snapshot(readings: Readings): Readings {
  return { axes: [...readings.axes], buttons: [...readings.buttons] };
}
