import { describe, expect, it } from 'vitest';
import {
  axisToPercent,
  barLeftPercent,
  barWidthPercent,
  buttonToPercent,
  computeUpdateRateHz,
  defaultAxisLabel,
  defaultButtonLabel,
  deviceLabel,
  isButtonOn,
} from './rc-input-logic';

describe('rc-input-logic', () => {
  describe('axisToPercent', () => {
    it('maps [-1,1] to signed whole percent and clamps out-of-range', () => {
      expect(axisToPercent(0)).toBe(0);
      expect(axisToPercent(1)).toBe(100);
      expect(axisToPercent(-1)).toBe(-100);
      expect(axisToPercent(0.5)).toBe(50);
      expect(axisToPercent(2)).toBe(100);
      expect(axisToPercent(-9)).toBe(-100);
    });
  });

  describe('buttonToPercent / isButtonOn', () => {
    it('maps [0,1] to 0..100 and clamps', () => {
      expect(buttonToPercent(0)).toBe(0);
      expect(buttonToPercent(1)).toBe(100);
      expect(buttonToPercent(1.5)).toBe(100);
      expect(buttonToPercent(-0.2)).toBe(0);
    });
    it('reads on at/above the threshold', () => {
      expect(isButtonOn(0)).toBe(false);
      expect(isButtonOn(0.49)).toBe(false);
      expect(isButtonOn(0.5)).toBe(true);
      expect(isButtonOn(1)).toBe(true);
      expect(isButtonOn(0.2, 0.1)).toBe(true);
    });
  });

  describe('center-origin bar geometry', () => {
    it('centres at 50% for zero, grows right for positive, left for negative', () => {
      expect(barLeftPercent(0)).toBe(50);
      expect(barWidthPercent(0)).toBe(0);

      // full right: fill from 50 to 100
      expect(barLeftPercent(1)).toBe(50);
      expect(barWidthPercent(1)).toBe(50);

      // full left: fill from 0 to 50
      expect(barLeftPercent(-1)).toBe(0);
      expect(barWidthPercent(-1)).toBe(50);

      // half right
      expect(barLeftPercent(0.5)).toBe(50);
      expect(barWidthPercent(0.5)).toBe(25);
    });
  });

  describe('labels', () => {
    it('are 1-based', () => {
      expect(defaultAxisLabel(0)).toBe('Axis 1');
      expect(defaultButtonLabel(3)).toBe('Sw 4');
    });
  });

  describe('deviceLabel', () => {
    it('is empty for a null device', () => {
      expect(deviceLabel(null)).toBe('');
    });
    it('trims a trailing Vendor/Product suffix', () => {
      expect(
        deviceLabel({ id: 'RadioMaster TX16S Joystick (Vendor: 1209 Product: 4f54)', index: 0, axisCount: 8, buttonCount: 12 }),
      ).toBe('RadioMaster TX16S Joystick');
    });
    it('handles the STANDARD GAMEPAD variant and a bare name', () => {
      expect(deviceLabel({ id: 'Pad (STANDARD GAMEPAD Vendor: 045e Product: 028e)', index: 1, axisCount: 4, buttonCount: 16 })).toBe('Pad');
      expect(deviceLabel({ id: 'Some Controller', index: 0, axisCount: 6, buttonCount: 8 })).toBe('Some Controller');
    });
  });

  describe('computeUpdateRateHz', () => {
    it('returns 0 for fewer than two samples or a non-positive span', () => {
      expect(computeUpdateRateHz([])).toBe(0);
      expect(computeUpdateRateHz([100])).toBe(0);
      expect(computeUpdateRateHz([100, 100])).toBe(0);
    });
    it('computes Hz from evenly spaced timestamps', () => {
      // 5 frames spanning 1000ms => 4 intervals / 1s = ~60Hz when 16.67ms apart
      expect(computeUpdateRateHz([0, 1000])).toBe(1); // 1 interval over 1s
      expect(computeUpdateRateHz([0, 250, 500, 750, 1000])).toBe(4); // 4 intervals over 1s
      const frames = Array.from({ length: 61 }, (_, i) => i * (1000 / 60));
      expect(computeUpdateRateHz(frames)).toBe(60);
    });
  });
});
