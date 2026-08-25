import { describe, expect, it } from 'vitest';
import {
  axisToOffsetPercent,
  classifyAxisSamples,
  classifyControl,
  diagramLayout,
  stickModeLayout,
  verticalOffsetPercent,
} from './controller-diagram-logic';
import type { ControlFunction } from '../api/models';

/**
 * The axis order a transmitter actually reports: EdgeTX sends channels in channel order (CH1→X,
 * CH2→Y, CH3→Z, CH4→RX), so with the usual AETR channel order axes 0..3 are aileron, elevator,
 * throttle, rudder — *not* two stick pairs.
 */
const AETR = new Map<number, ControlFunction>([
  [0, 'ROLL'],
  [1, 'PITCH'],
  [2, 'THROTTLE'],
  [3, 'YAW'],
]);

describe('controller-diagram-logic', () => {
  describe('stickModeLayout', () => {
    it('puts throttle on the left in mode 2, with the other three where mode 2 holds them', () => {
      expect(stickModeLayout(2)).toEqual({
        left: { horizontal: 'YAW', vertical: 'THROTTLE' },
        right: { horizontal: 'ROLL', vertical: 'PITCH' },
      });
    });

    it('puts throttle on the right in mode 1', () => {
      expect(stickModeLayout(1)).toEqual({
        left: { horizontal: 'YAW', vertical: 'PITCH' },
        right: { horizontal: 'ROLL', vertical: 'THROTTLE' },
      });
    });

    it('mirrors mode 2 as mode 3 and mode 1 as mode 4', () => {
      expect(stickModeLayout(3).left).toEqual({ horizontal: 'ROLL', vertical: 'PITCH' });
      expect(stickModeLayout(3).right).toEqual({ horizontal: 'YAW', vertical: 'THROTTLE' });
      expect(stickModeLayout(4).left).toEqual({ horizontal: 'ROLL', vertical: 'THROTTLE' });
      expect(stickModeLayout(4).right).toEqual({ horizontal: 'YAW', vertical: 'PITCH' });
    });
  });

  describe('diagramLayout', () => {
    it('places a transmitter reporting AETR channel order onto the right sticks for mode 2', () => {
      const layout = diagramLayout(4, AETR, 2);

      expect(layout.sticks[0]).toEqual({
        side: 'LEFT',
        label: 'Left stick',
        horizontal: { axis: 3, function: 'YAW' },
        vertical: { axis: 2, function: 'THROTTLE' },
      });
      expect(layout.sticks[1]).toEqual({
        side: 'RIGHT',
        label: 'Right stick',
        horizontal: { axis: 0, function: 'ROLL' },
        vertical: { axis: 1, function: 'PITCH' },
      });
      expect(layout.bars).toEqual([]);
    });

    it('moves throttle to the right stick on a mode-1 radio, same layout', () => {
      const layout = diagramLayout(4, AETR, 1);

      expect(layout.sticks[0].vertical).toEqual({ axis: 1, function: 'PITCH' });
      expect(layout.sticks[1].vertical).toEqual({ axis: 2, function: 'THROTTLE' });
    });

    it('never places an axis by its number — the same axes with different functions move', () => {
      const reordered = new Map<number, ControlFunction>([
        [0, 'THROTTLE'],
        [1, 'YAW'],
        [2, 'ROLL'],
        [3, 'PITCH'],
      ]);
      const layout = diagramLayout(4, reordered, 2);

      expect(layout.sticks[0].vertical).toEqual({ axis: 0, function: 'THROTTLE' });
      expect(layout.sticks[0].horizontal).toEqual({ axis: 1, function: 'YAW' });
    });

    it('draws every unclaimed axis as a bar, in order', () => {
      const layout = diagramLayout(7, AETR, 2);

      expect(layout.bars).toEqual([4, 5, 6]);
    });

    it('holds a ground vehicle steering where roll would be', () => {
      const rover = new Map<number, ControlFunction>([
        [0, 'STEERING'],
        [2, 'THROTTLE'],
      ]);
      const layout = diagramLayout(4, rover, 2);

      expect(layout.sticks[0].vertical).toEqual({ axis: 2, function: 'THROTTLE' });
      expect(layout.sticks[1].horizontal).toEqual({ axis: 0, function: 'STEERING' });
      expect(layout.bars).toEqual([1, 3]);
    });

    it('draws a pad with one direction bound and says nothing about the other', () => {
      const layout = diagramLayout(2, new Map<number, ControlFunction>([[1, 'THROTTLE']]), 2);

      expect(layout.sticks.length).toBe(1);
      expect(layout.sticks[0].vertical).toEqual({ axis: 1, function: 'THROTTLE' });
      expect(layout.sticks[0].horizontal).toBeUndefined();
      expect(layout.bars).toEqual([0]);
    });

    it('guesses no pads at all when nothing is bound to a stick function', () => {
      const layout = diagramLayout(4, new Map<number, ControlFunction>([[0, 'AUX_1']]), 2);

      expect(layout.sticks).toEqual([]);
      expect(layout.bars).toEqual([0, 1, 2, 3]);
    });

    it('draws nothing for a transmitter with no axes', () => {
      expect(diagramLayout(0, AETR, 2)).toEqual({ sticks: [], bars: [] });
    });
  });

  describe('axisToOffsetPercent', () => {
    it('puts centre in the middle and the ends at the ends', () => {
      expect(axisToOffsetPercent(-1)).toBe(0);
      expect(axisToOffsetPercent(0)).toBe(50);
      expect(axisToOffsetPercent(1)).toBe(100);
    });

    it('clamps a reading that overshoots rather than drawing off the pad', () => {
      expect(axisToOffsetPercent(-4)).toBe(0);
      expect(axisToOffsetPercent(4)).toBe(100);
    });
  });

  describe('verticalOffsetPercent', () => {
    it('draws a transmitter with the stick forward at the top', () => {
      expect(verticalOffsetPercent(1, true)).toBe(0);
      expect(verticalOffsetPercent(-1, true)).toBe(100);
    });

    it('draws a gamepad the other way, which is why this is a flag and not a constant', () => {
      expect(verticalOffsetPercent(1, false)).toBe(100);
      expect(verticalOffsetPercent(-1, false)).toBe(0);
    });

    it('agrees about centre either way', () => {
      expect(verticalOffsetPercent(0, true)).toBe(50);
      expect(verticalOffsetPercent(0, false)).toBe(50);
    });
  });

  describe('classifyAxisSamples', () => {
    it('calls a swept stick an axis', () => {
      expect(classifyAxisSamples([-1, -0.6, -0.2, 0.3, 0.8, 1])).toBe('AXIS');
    });

    it('calls three detents a 3-position switch', () => {
      expect(classifyAxisSamples([-1, 0, 1])).toBe('SWITCH_3');
    });

    it('calls two extremes a 2-position switch', () => {
      expect(classifyAxisSamples([-1, 1, -1])).toBe('SWITCH_2');
    });

    it('tolerates a switch that does not land on exactly -1/0/1', () => {
      expect(classifyAxisSamples([-0.996, 0.04, 0.98])).toBe('SWITCH_3');
    });

    it('refuses to call one resting place a switch', () => {
      expect(classifyAxisSamples([1, 1, 1])).toBe('AXIS');
    });

    it('reports what it saw when a 3-position switch was only moved half its travel', () => {
      expect(classifyAxisSamples([-1, 0])).toBe('SWITCH_2');
    });

    it('is an axis with nothing to go on', () => {
      expect(classifyAxisSamples([])).toBe('AXIS');
    });

    it('is an axis the moment one reading sits between detents', () => {
      expect(classifyAxisSamples([-1, 0, 0.45, 1])).toBe('AXIS');
    });
  });

  describe('classifyControl', () => {
    it('calls anything on the buttons array a button, whatever it was seen doing', () => {
      expect(classifyControl('BUTTON', [0, 1, 0, 1])).toBe('BUTTON');
    });

    it('classifies an axis control from its readings', () => {
      expect(classifyControl('AXIS', [-1, 0, 1])).toBe('SWITCH_3');
    });
  });
});
