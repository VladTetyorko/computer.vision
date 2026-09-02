import { describe, expect, it } from 'vitest';
import { DEFAULT_BATTERY_THRESHOLDS, DEFAULT_RC_THRESHOLDS } from './thresholds-logic';

describe('DEFAULT_BATTERY_THRESHOLDS', () => {
  it('matches the frozen backend default (ASSET-FLOWS-PLAN.md §2 D6: warning 25, critical 10)', () => {
    expect(DEFAULT_BATTERY_THRESHOLDS).toEqual({ warningPercent: 25, criticalPercent: 10 });
  });

  it('critical is strictly below warning', () => {
    expect(DEFAULT_BATTERY_THRESHOLDS.criticalPercent).toBeLessThan(DEFAULT_BATTERY_THRESHOLDS.warningPercent);
  });
});

describe('DEFAULT_RC_THRESHOLDS', () => {
  it('matches the frozen backend default (FLY-CONTROL-UX-PLAN.md §2: neutral-tolerance-percent 5)', () => {
    expect(DEFAULT_RC_THRESHOLDS).toEqual({ neutralTolerancePercent: 5 });
  });

  it('sits inside the backend\'s own validated 1..25 range', () => {
    expect(DEFAULT_RC_THRESHOLDS.neutralTolerancePercent).toBeGreaterThanOrEqual(1);
    expect(DEFAULT_RC_THRESHOLDS.neutralTolerancePercent).toBeLessThanOrEqual(25);
  });
});
