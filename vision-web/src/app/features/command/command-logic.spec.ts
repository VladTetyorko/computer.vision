import { describe, expect, it } from 'vitest';
import type { AssetAttention, CategoryCounts } from '../../core/api/models';
import {
  attentionAgeLabel,
  attentionReasons,
  batteryAttentionSeverity,
  buildAttentionQueue,
  shouldPollSnapshot,
  sortReadinessTiles,
  streamingAssets,
  totalStreaming,
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

function category(partial: Partial<CategoryCounts> = {}): CategoryCounts {
  return {
    categoryId: 'drone',
    categoryName: 'Drone',
    total: 0,
    active: 0,
    deactivated: 0,
    deleted: 0,
    streaming: 0,
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

describe('buildAttentionQueue', () => {
  it('omits assets with nothing triggered', () => {
    expect(buildAttentionQueue([asset({ batteryPercent: 90 })])).toEqual([]);
  });

  it('ranks a critical-tier asset above a warning-only one regardless of reason count', () => {
    const warningTwice = asset({ assetId: 'w', displayName: 'Warned', batteryPercent: 15, openEventCount: 2 });
    const criticalOnce = asset({ assetId: 'c', displayName: 'Zulu-critical', batteryPercent: 5 });
    const queue = buildAttentionQueue([warningTwice, criticalOnce]);
    expect(queue.map((row) => row.asset.assetId)).toEqual(['c', 'w']);
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
    const queue = buildAttentionQueue([oneReason, twoReasons]);
    expect(queue.map((row) => row.asset.assetId)).toEqual(['two', 'one']);
  });

  it('falls back to alphabetical, case-insensitive, once rank and reason count both tie', () => {
    const bravo = asset({ assetId: 'b', displayName: 'bravo', batteryPercent: 15 });
    const alpha = asset({ assetId: 'a', displayName: 'Alpha', batteryPercent: 15 });
    const queue = buildAttentionQueue([bravo, alpha]);
    expect(queue.map((row) => row.asset.assetId)).toEqual(['a', 'b']);
  });

  it('joins multiple reasons into one why sentence', () => {
    const row = buildAttentionQueue([asset({ batteryPercent: 5, openEventCount: 1 })])[0];
    expect(row.why).toBe('Battery critical at 5%. 1 open detection event.');
  });

  it('does not mutate the input array', () => {
    const list = [asset({ assetId: 'z', displayName: 'Z', batteryPercent: 5 }), asset({ assetId: 'a', displayName: 'A', batteryPercent: 5 })];
    const original = [...list];
    buildAttentionQueue(list);
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
    const queue = buildAttentionQueue(assets);
    expect(queue).toHaveLength(10);
    expect(queue.every((row) => row.severity === 'critical')).toBe(true);
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

describe('totalStreaming', () => {
  it('sums the streaming count across every category', () => {
    expect(totalStreaming([category({ streaming: 2 }), category({ categoryId: 'camera', streaming: 3 })])).toBe(5);
  });

  it('is zero for no categories', () => {
    expect(totalStreaming([])).toBe(0);
  });
});

describe('streamingAssets', () => {
  it('keeps only streaming assets carrying a streamId', () => {
    const streaming = asset({ assetId: 's', streaming: true, streamId: 'stream-1' });
    const notStreaming = asset({ assetId: 'n', streaming: false });
    const inconsistent = asset({ assetId: 'i', streaming: true, streamId: undefined });
    expect(streamingAssets([streaming, notStreaming, inconsistent]).map((a) => a.assetId)).toEqual(['s']);
  });

  it('sorts alphabetically, case-insensitively', () => {
    const bravo = asset({ assetId: 'b', displayName: 'bravo', streaming: true, streamId: 's-b' });
    const alpha = asset({ assetId: 'a', displayName: 'Alpha', streaming: true, streamId: 's-a' });
    expect(streamingAssets([bravo, alpha]).map((a) => a.assetId)).toEqual(['a', 'b']);
  });

  it('does not mutate the input array', () => {
    const list = [
      asset({ assetId: 'b', displayName: 'B', streaming: true, streamId: 's-b' }),
      asset({ assetId: 'a', displayName: 'A', streaming: true, streamId: 's-a' }),
    ];
    const original = [...list];
    streamingAssets(list);
    expect(list).toEqual(original);
  });

  it('holds at scale (N=100): membership stays a pure filter over the one already-fetched list', () => {
    const assets: AssetAttention[] = [];
    for (let i = 0; i < 100; i++) {
      const streaming = i % 4 === 0;
      assets.push(
        asset({
          assetId: `a-${i}`,
          displayName: `Asset ${String(i).padStart(3, '0')}`,
          streaming,
          streamId: streaming ? `stream-${i}` : undefined,
        }),
      );
    }
    expect(streamingAssets(assets)).toHaveLength(25);
  });
});

describe('shouldPollSnapshot', () => {
  it('polls only when visible and a streamId exists', () => {
    expect(shouldPollSnapshot(true, true)).toBe(true);
  });

  it('does not poll an off-screen tile even with a streamId', () => {
    expect(shouldPollSnapshot(false, true)).toBe(false);
  });

  it('does not poll a visible tile whose asset has stopped streaming since the last summary poll', () => {
    expect(shouldPollSnapshot(true, false)).toBe(false);
  });

  it('does not poll when neither condition holds', () => {
    expect(shouldPollSnapshot(false, false)).toBe(false);
  });
});

describe('sortReadinessTiles', () => {
  it('sorts categories alphabetically by name, case-insensitively', () => {
    const zebra = category({ categoryId: 'zebra', categoryName: 'Zebra-cam' });
    const drone = category({ categoryId: 'drone', categoryName: 'drone' });
    expect(sortReadinessTiles([zebra, drone]).map((c) => c.categoryId)).toEqual(['drone', 'zebra']);
  });

  it('does not mutate the input array', () => {
    const list = [category({ categoryId: 'z', categoryName: 'Z' }), category({ categoryId: 'a', categoryName: 'A' })];
    const original = [...list];
    sortReadinessTiles(list);
    expect(list).toEqual(original);
  });
});
