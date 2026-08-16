import { describe, expect, it } from 'vitest';
import type { AssetAttention } from '../api/models';
import { attentionAgeLabel, attentionReasons, batteryAttentionSeverity } from './attention-logic';

/**
 * Moved from `features/command/command-logic.spec.ts` (docs/plans/done/UI-REDESIGN-PLAN.md Wave 4) alongside
 * the rules themselves — see `attention-logic.ts`'s own doc comment. `features/command/command-logic.spec.ts`
 * keeps its own `buildEntityRows`/`commandGridColumns` cases (Command-specific), importing these
 * same functions via `command-logic.ts`'s re-export.
 */
function asset(partial: Partial<AssetAttention> = {}): AssetAttention {
  return {
    assetId: 'a-0',
    displayName: 'Asset',
    categoryId: 'drone',
    categoryName: 'Drone',
    lifecycle: 'ACTIVE',
    streaming: false,
    openEventCount: 0,
    ...partial,
  };
}

describe('batteryAttentionSeverity', () => {
  it('is unknown with no reading at all', () => {
    expect(batteryAttentionSeverity(undefined)).toBe('unknown');
  });

  it('is ok at and above 20%', () => {
    expect(batteryAttentionSeverity(20)).toBe('ok');
    expect(batteryAttentionSeverity(45)).toBe('ok');
  });

  it('is warning just under 20%, down to and including 10%', () => {
    expect(batteryAttentionSeverity(19.9)).toBe('warning');
    expect(batteryAttentionSeverity(10)).toBe('warning');
  });

  it('is critical strictly under 10%', () => {
    expect(batteryAttentionSeverity(9.9)).toBe('critical');
    expect(batteryAttentionSeverity(0)).toBe('critical');
  });
});

describe('attentionReasons', () => {
  it('is empty for an asset with nothing wrong', () => {
    expect(attentionReasons(asset({ batteryPercent: 80 }))).toEqual([]);
  });

  it('flags battery-low just under 20%, not at exactly 20%', () => {
    expect(attentionReasons(asset({ batteryPercent: 20 }))).toEqual([]);
    const reasons = attentionReasons(asset({ batteryPercent: 19.9 }));
    expect(reasons).toHaveLength(1);
    expect(reasons[0].kind).toBe('battery-low');
    expect(reasons[0].severity).toBe('warning');
  });

  it('flags battery-critical strictly under 10%, not at exactly 10%', () => {
    const atTen = attentionReasons(asset({ batteryPercent: 10 }));
    expect(atTen[0].kind).toBe('battery-low');
    const underTen = attentionReasons(asset({ batteryPercent: 9.9 }));
    expect(underTen[0].kind).toBe('battery-critical');
    expect(underTen[0].severity).toBe('critical');
  });

  it('rounds the battery percent in the reason text', () => {
    const reasons = attentionReasons(asset({ batteryPercent: 8.6 }));
    expect(reasons[0].text).toBe('Battery critical at 9%.');
  });

  it('flags telemetry-stale only while streaming, strictly past 10s', () => {
    expect(attentionReasons(asset({ streaming: true, telemetryAgeMs: 10_000 }))).toEqual([]);
    expect(attentionReasons(asset({ streaming: false, telemetryAgeMs: 50_000 }))).toEqual([]);
    const reasons = attentionReasons(asset({ streaming: true, telemetryAgeMs: 10_001 }));
    expect(reasons).toHaveLength(1);
    expect(reasons[0].kind).toBe('telemetry-stale');
    expect(reasons[0].severity).toBe('critical');
  });

  it('does not flag telemetry-stale when there is no telemetry reading at all', () => {
    expect(attentionReasons(asset({ streaming: true, telemetryAgeMs: undefined }))).toEqual([]);
  });

  it('flags open-events only above zero, with correct singular/plural wording', () => {
    expect(attentionReasons(asset({ openEventCount: 0 }))).toEqual([]);
    expect(attentionReasons(asset({ openEventCount: 1 }))[0].text).toBe('1 open detection event.');
    expect(attentionReasons(asset({ openEventCount: 3 }))[0].text).toBe('3 open detection events.');
  });

  it('orders multiple triggered reasons most severe first', () => {
    const reasons = attentionReasons(
      asset({ batteryPercent: 5, streaming: true, telemetryAgeMs: 20_000, openEventCount: 2 }),
    );
    expect(reasons.map((r) => r.kind)).toEqual(['battery-critical', 'telemetry-stale', 'open-events']);
  });

  describe('failsafe (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-d)', () => {
    it('flags failsafe only for an explicit true, never fabricated from absent/false data', () => {
      expect(attentionReasons(asset({ failsafe: undefined }))).toEqual([]);
      expect(attentionReasons(asset({ failsafe: false }))).toEqual([]);
      const reasons = attentionReasons(asset({ failsafe: true }));
      expect(reasons).toHaveLength(1);
      expect(reasons[0]).toEqual({
        kind: 'failsafe',
        severity: 'critical',
        text: 'Failsafe active — returning to home.',
      });
    });

    it('ranks above every other reason, including battery-critical', () => {
      const reasons = attentionReasons(asset({ failsafe: true, batteryPercent: 5, openEventCount: 3 }));
      expect(reasons.map((r) => r.kind)).toEqual(['failsafe', 'battery-critical', 'open-events']);
    });
  });

  describe('gps-degraded (docs/plans/done/FC-INTEGRATIONS-PLAN.md F-d)', () => {
    it('never fires with no gpsFixType given at all', () => {
      expect(attentionReasons(asset(), undefined)).toEqual([]);
    });

    it('never fires for a healthy (3D+) fix', () => {
      expect(attentionReasons(asset(), 3)).toEqual([]);
    });

    it('flags a 2D fix as a warning', () => {
      const reasons = attentionReasons(asset(), 2);
      expect(reasons).toHaveLength(1);
      expect(reasons[0].kind).toBe('gps-degraded');
      expect(reasons[0].severity).toBe('warning');
    });

    it('flags no-GPS/no-fix as critical', () => {
      expect(attentionReasons(asset(), 0)[0].severity).toBe('critical');
      expect(attentionReasons(asset(), 1)[0].severity).toBe('critical');
    });

    it('ranks between battery-low and open-events', () => {
      const reasons = attentionReasons(asset({ batteryPercent: 15, openEventCount: 1 }), 2);
      expect(reasons.map((r) => r.kind)).toEqual(['battery-low', 'gps-degraded', 'open-events']);
    });
  });
});

describe('geofence-breach (docs/plans/done/OPS-CORE-PLAN.md §G-c)', () => {
  it('never fires with no breaches given at all', () => {
    expect(attentionReasons(asset())).toEqual([]);
    expect(attentionReasons(asset(), undefined, [])).toEqual([]);
  });

  it('fires with one active breach, naming the zone', () => {
    const reasons = attentionReasons(asset(), undefined, [
      { assetId: 'a-0', zoneId: 'z-1', zoneName: 'North perimeter', kind: 'KEEP_OUT', direction: 'enter' },
    ]);
    expect(reasons).toHaveLength(1);
    expect(reasons[0]).toEqual({ kind: 'geofence-breach', severity: 'critical', text: 'KEEP-OUT breach — North perimeter.' });
  });

  it('ranks above every other reason, including failsafe', () => {
    const reasons = attentionReasons(
      asset({ failsafe: true, batteryPercent: 5 }),
      undefined,
      [{ assetId: 'a-0', zoneId: 'z-1', zoneName: 'North perimeter', kind: 'KEEP_OUT', direction: 'enter' }],
    );
    expect(reasons.map((r) => r.kind)).toEqual(['geofence-breach', 'failsafe', 'battery-critical']);
  });

  it('joins multiple active breaches into one reason', () => {
    const reasons = attentionReasons(asset(), undefined, [
      { assetId: 'a-0', zoneId: 'z-1', zoneName: 'North perimeter', kind: 'KEEP_OUT', direction: 'enter' },
      { assetId: 'a-0', zoneId: 'z-2', zoneName: 'Charging pad', kind: 'KEEP_IN', direction: 'enter' },
    ]);
    expect(reasons).toHaveLength(1);
    expect(reasons[0].text).toBe('KEEP-OUT breach — North perimeter; KEEP-IN breach — Charging pad.');
  });
});

describe('pipeline-error (docs/plans/active/SYSTEM-STATUS-PLAN.md §3.4)', () => {
  it('never fires with no detail given at all', () => {
    expect(attentionReasons(asset())).toEqual([]);
    expect(attentionReasons(asset(), undefined, undefined, undefined)).toEqual([]);
  });

  it('fires with a detail, naming it verbatim in the reason text', () => {
    const reasons = attentionReasons(asset(), undefined, undefined, 'RTSP source unreachable');
    expect(reasons).toHaveLength(1);
    expect(reasons[0]).toEqual({
      kind: 'pipeline-error',
      severity: 'warning',
      text: 'Detection pipeline error — RTSP source unreachable',
    });
  });

  it('ranks below gps-degraded and above open-events', () => {
    const reasons = attentionReasons(asset({ openEventCount: 1 }), 2, undefined, 'RTSP source unreachable');
    expect(reasons.map((r) => r.kind)).toEqual(['gps-degraded', 'pipeline-error', 'open-events']);
  });

  it('ranks below every flight-safety reason, including battery-low', () => {
    const reasons = attentionReasons(asset({ batteryPercent: 15 }), undefined, undefined, 'RTSP source unreachable');
    expect(reasons.map((r) => r.kind)).toEqual(['battery-low', 'pipeline-error']);
  });
});

describe('attentionAgeLabel', () => {
  it('renders a dash with no telemetry reading yet', () => {
    expect(attentionAgeLabel(asset({ telemetryAgeMs: undefined }))).toBe('—');
  });

  it('renders a formatted duration otherwise', () => {
    expect(attentionAgeLabel(asset({ telemetryAgeMs: 12_000 }))).toBe('12s ago');
  });
});
