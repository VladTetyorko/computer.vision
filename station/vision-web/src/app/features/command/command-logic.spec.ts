import { describe, expect, it } from 'vitest';
import type { AssetAttention } from '../../core/api/models';
import { buildEntityRows, commandGridColumns } from './command-logic';

/**
 * The `batteryAttentionSeverity`/`attentionReasons`/`attentionAgeLabel` cases that used to live here
 * moved to `core/fleet/attention-logic.spec.ts` alongside the rules themselves (docs/plans/done/UI-REDESIGN-PLAN.md
 * Wave 4 — see `command-logic.ts`'s own doc comment for why). What's left below is Command-specific:
 * `buildEntityRows`' own sort/wiring and `commandGridColumns`' layout arithmetic.
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

describe('buildEntityRows — geofenceBreachesByAssetId (docs/plans/done/OPS-CORE-PLAN.md §G-c)', () => {
  it('feeds breaches into each streaming asset\'s own reason, by id, ranking it above every other asset', () => {
    const breaching = asset({ assetId: 'b', displayName: 'Breaching', streaming: true });
    const critical = asset({ assetId: 'c', displayName: 'Zulu-critical', batteryPercent: 5 });
    const breachesByAssetId = new Map([
      ['b', [{ assetId: 'b', zoneId: 'z-1', zoneName: 'North perimeter', kind: 'KEEP_OUT' as const, direction: 'enter' as const }]],
    ]);
    const rows = buildEntityRows([breaching, critical], undefined, breachesByAssetId);
    expect(rows.map((row) => row.asset.assetId)).toEqual(['b', 'c']);
    expect(rows[0].severity).toBe('critical');
  });

  /** docs/plans/active/OPERATOR-UX-4-PLAN.md finding N2 follow-up, §2 N2, this cycle's W5 — the
   *  live-only gate now inside `core/fleet/attention-logic.ts#geofenceBreachReason`; the rail row for
   *  an offline asset with a leftover breach must read quiet, exactly like the "ranking it above
   *  every other asset" case above does for a *streaming* one. */
  it('never ranks a non-streaming asset\'s leftover breach as an active reason', () => {
    const offlineBreached = asset({ assetId: 'o', displayName: 'Offline-breached', streaming: false });
    const breachesByAssetId = new Map([
      ['o', [{ assetId: 'o', zoneId: 'z-1', zoneName: 'Demo operating area', kind: 'KEEP_IN' as const, direction: 'enter' as const }]],
    ]);
    const rows = buildEntityRows([offlineBreached], undefined, breachesByAssetId);
    expect(rows[0].severity).toBe('ok');
    expect(rows[0].reasons).toEqual([]);
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

  it('feeds gpsFixTypeByAssetId into each asset\'s own gps-degraded reason, by id (streaming only — docs/plans/active/OPERATOR-UX-4-PLAN.md N2)', () => {
    const degraded = asset({ assetId: 'd', displayName: 'Degraded', streaming: true });
    const healthy = asset({ assetId: 'h', displayName: 'Healthy', streaming: true });
    const noMarker = asset({ assetId: 'n', displayName: 'NoMarker' });
    const gpsFixTypeByAssetId = new Map([['d', 1], ['h', 3]]);
    const rows = buildEntityRows([degraded, healthy, noMarker], gpsFixTypeByAssetId);
    expect(rows.find((r) => r.asset.assetId === 'd')!.severity).toBe('critical');
    expect(rows.find((r) => r.asset.assetId === 'h')!.severity).toBe('ok');
    expect(rows.find((r) => r.asset.assetId === 'n')!.severity).toBe('ok');
  });

  /** docs/plans/active/OPERATOR-UX-4-PLAN.md finding N2, §2 N2 — reproduced live: a rail row read
   *  CRIT off a fleet marker's leftover `gpsFixType` for an ESP32 rover that was not streaming. */
  it('never ranks an offline asset critical for a bad gpsFixType — it is not trying to get a fix', () => {
    const offlineNoFix = asset({ assetId: 'o', displayName: 'Offline', streaming: false });
    const gpsFixTypeByAssetId = new Map([['o', 0]]);
    const rows = buildEntityRows([offlineNoFix], gpsFixTypeByAssetId);
    expect(rows[0].severity).toBe('ok');
    expect(rows[0].reasons).toEqual([]);
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
