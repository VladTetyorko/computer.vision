import { describe, expect, it } from 'vitest';
import {
  AUTODETECT_OFF,
  SAMPLE_TICKS,
  beginAutodetect,
  stepAutodetect,
  type AutodetectState,
  type LearnedControl,
} from './control-autodetect';

const REST = { axes: [0, 0, -1, 0], buttons: [0, 0] };

/** Feeds frames until something is learned, so a test reads as "move this, get that". */
function run(start: AutodetectState, frames: readonly { axes: number[]; buttons: number[] }[]) {
  let state = start;
  const learned: LearnedControl[] = [];
  for (const frame of frames) {
    const step = stepAutodetect(state, frame);
    state = step.state;
    if (step.learned) {
      learned.push(step.learned);
    }
  }
  return { state, learned };
}

/** `count` copies of one frame — a control held where the operator left it. */
function hold(frame: { axes: number[]; buttons: number[] }, count: number) {
  return Array.from({ length: count }, () => ({ axes: [...frame.axes], buttons: [...frame.buttons] }));
}

describe('control-autodetect', () => {
  it('does nothing at all while off', () => {
    const step = stepAutodetect(AUTODETECT_OFF, { axes: [1, 1, 1, 1], buttons: [1, 1] });

    expect(step.learned).toBeUndefined();
    expect(step.state.phase).toBe('off');
  });

  it('starts watching from wherever the transmitter is sitting', () => {
    const state = beginAutodetect(REST);

    expect(state.phase).toBe('watching');
    expect(state.baseline.axes).toEqual([0, 0, -1, 0]);
  });

  it('ignores stick jitter', () => {
    const { state, learned } = run(beginAutodetect(REST), hold({ axes: [0.01, -0.02, -1, 0], buttons: [0, 0] }, 10));

    expect(learned).toEqual([]);
    expect(state.phase).toBe('watching');
  });

  it('names a button the moment it is pressed, without a sampling window', () => {
    const { learned } = run(beginAutodetect(REST), [{ axes: [0, 0, -1, 0], buttons: [0, 1] }]);

    expect(learned).toEqual([{ source: 'BUTTON', sourceIndex: 1, kind: 'BUTTON' }]);
  });

  it('names a swept stick an axis within a few frames', () => {
    const { learned } = run(beginAutodetect(REST), [
      { axes: [0.6, 0, -1, 0], buttons: [0, 0] },
      { axes: [0.8, 0, -1, 0], buttons: [0, 0] },
    ]);

    expect(learned).toEqual([{ source: 'AXIS', sourceIndex: 0, kind: 'AXIS' }]);
  });

  it('waits out the window for a switch, then calls it by how many detents it saw', () => {
    const thrown = { axes: [0, 0, 1, 0], buttons: [0, 0] };
    const { learned } = run(beginAutodetect(REST), [thrown, ...hold(thrown, SAMPLE_TICKS)]);

    expect(learned).toEqual([{ source: 'AXIS', sourceIndex: 2, kind: 'SWITCH_2' }]);
  });

  it('sees the third position when the operator visits it inside the window', () => {
    const mid = { axes: [0, 0, 0, 0], buttons: [0, 0] };
    const top = { axes: [0, 0, 1, 0], buttons: [0, 0] };
    const { learned } = run(beginAutodetect(REST), [mid, ...hold(mid, 10), ...hold(top, SAMPLE_TICKS)]);

    expect(learned).toEqual([{ source: 'AXIS', sourceIndex: 2, kind: 'SWITCH_3' }]);
  });

  it('does not decide before the window is up', () => {
    const thrown = { axes: [0, 0, 1, 0], buttons: [0, 0] };
    const { state, learned } = run(beginAutodetect(REST), [thrown, ...hold(thrown, 5)]);

    expect(learned).toEqual([]);
    expect(state.phase).toBe('sampling');
    expect(state.target).toEqual({ source: 'AXIS', sourceIndex: 2 });
  });

  it('keeps going: the next control is detected from where the last one left the sticks', () => {
    const thrown = { axes: [0, 0, 1, 0], buttons: [0, 0] };
    const swept = { axes: [0, 0.7, 1, 0], buttons: [0, 0] };
    const { state, learned } = run(beginAutodetect(REST), [
      thrown,
      ...hold(thrown, SAMPLE_TICKS),
      swept,
      swept,
    ]);

    expect(learned.map((l) => l.sourceIndex)).toEqual([2, 1]);
    expect(state.phase).toBe('watching');
  });

  it('does not re-detect the switch it just named, still sitting where it was thrown', () => {
    const thrown = { axes: [0, 0, 1, 0], buttons: [0, 0] };
    const { learned } = run(beginAutodetect(REST), [thrown, ...hold(thrown, SAMPLE_TICKS * 2)]);

    expect(learned.length).toBe(1);
  });
});
