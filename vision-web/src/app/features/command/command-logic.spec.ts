import { describe, expect, it } from 'vitest';
import type { AssetAttention } from '../../core/api/models';
import {
  attentionAgeLabel,
  attentionReasons,
  batteryAttentionSeverity,
  buildEntityRows,
  commandGridColumns,
} from './command-logic';

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
});

describe('attentionAgeLabel', () => {
  it('renders a dash with no telemetry reading yet', () => {
    expect(attentionAgeLabel(asset({ telemetryAgeMs: undefined }))).toBe('—');
  });

  it('renders a formatted duration otherwise', () => {
    expect(attentionAgeLabel(asset({ telemetryAgeMs: 12_000 }))).toBe('12s ago');
  });
});

describe('buildEntityRows', () => {
  it('includes every asset, not only flagged ones (the rail is a slim always-available list)', () => {
    const quiet = asset({ assetId: 'q', displayName: 'Quiet', batteryPercent: 90 });
    const flagged = asset({ assetId: 'f', displayName: 'Flagged', batteryPercent: 5 });
    const rows = buildEntityRows([quiet, flagged]);
    expect(rows).toHaveLength(2);
    expect(rows.find((r) => r.asset.assetId === 'q')!.severity).toBe('ok');
    expect(rows.find((r) => r.asset.assetId === 'f')!.severity).toBe('critical');
  });

  it('ranks a critical-tier asset above a warning-only one regardless of reason count', () => {
    const warningTwice = asset({ assetId: 'w', displayName: 'Warned', batteryPercent: 15, openEventCount: 2 });
    const criticalOnce = asset({ assetId: 'c', displayName: 'Zulu-critical', batteryPercent: 5 });
    const rows = buildEntityRows([warningTwice, criticalOnce]);
    expect(rows.map((row) => row.asset.assetId)).toEqual(['c', 'w']);
  });

  it('within the same top rank, ranks more simultaneous reasons first', () => {
    const oneReason = asset({ assetId: 'one', displayName: 'Alpha', batteryPercent: 5 });
    const twoReasons = asset({
      assetId: 'two',
      displayName: 'Zulu',
      batteryPercent: 5,
      streaming: true,
      telemetryAgeMs: 99_000,
    });
    const rows = buildEntityRows([oneReason, twoReasons]);
    expect(rows.map((row) => row.asset.assetId)).toEqual(['two', 'one']);
  });

  it('sorts every flagged asset ahead of every quiet one, quiet ones alphabetical among themselves', () => {
    const quietB = asset({ assetId: 'qb', displayName: 'Bravo-quiet', batteryPercent: 90 });
    const quietA = asset({ assetId: 'qa', displayName: 'Alpha-quiet', batteryPercent: 90 });
    const flagged = asset({ assetId: 'fl', displayName: 'Zulu-flagged', batteryPercent: 5 });
    const rows = buildEntityRows([quietB, quietA, flagged]);
    expect(rows.map((row) => row.asset.assetId)).toEqual(['fl', 'qa', 'qb']);
  });

  it('falls back to alphabetical, case-insensitive, once rank and reason count both tie', () => {
    const bravo = asset({ assetId: 'b', displayName: 'bravo', batteryPercent: 15 });
    const alpha = asset({ assetId: 'a', displayName: 'Alpha', batteryPercent: 15 });
    const rows = buildEntityRows([bravo, alpha]);
    expect(rows.map((row) => row.asset.assetId)).toEqual(['a', 'b']);
  });

  it('does not mutate the input array', () => {
    const list = [asset({ assetId: 'z', displayName: 'Z', batteryPercent: 5 }), asset({ assetId: 'a', displayName: 'A', batteryPercent: 5 })];
    const original = [...list];
    buildEntityRows(list);
    expect(list).toEqual(original);
  });

  it('holds at scale (N=100): pure selection logic never issues a request and stays correct', () => {
    const assets: AssetAttention[] = [];
    for (let i = 0; i < 100; i++) {
      assets.push(
        asset({
          assetId: `a-${i}`,
          displayName: `Asset ${String(i).padStart(3, '0')}`,
          // Every 10th asset is critically low on battery; everything else is fine.
          batteryPercent: i % 10 === 0 ? 5 : 80,
        }),
      );
    }
    const rows = buildEntityRows(assets);
    expect(rows).toHaveLength(100);
    expect(rows.slice(0, 10).every((row) => row.severity === 'critical')).toBe(true);
    expect(rows.slice(10).every((row) => row.severity === 'ok')).toBe(true);
  });
});

describe('commandGridColumns', () => {
  it('rail open, no selection', () => {
    expect(commandGridColumns(true, 'hidden')).toBe('300px auto minmax(0, 1fr)');
  });

  it('rail closed, no selection', () => {
    expect(commandGridColumns(false, 'hidden')).toBe('auto minmax(0, 1fr)');
  });

  it('rail open, panel open', () => {
    expect(commandGridColumns(true, 'open')).toBe('300px auto minmax(0, 1fr) auto 380px');
  });

  it('rail open, panel collapsed to its reopen chip', () => {
    expect(commandGridColumns(true, 'collapsed')).toBe('300px auto minmax(0, 1fr) auto');
  });

  it('rail closed, panel open', () => {
    expect(commandGridColumns(false, 'open')).toBe('auto minmax(0, 1fr) auto 380px');
  });

  it('rail closed, panel collapsed', () => {
    expect(commandGridColumns(false, 'collapsed')).toBe('auto minmax(0, 1fr) auto');
  });
});
