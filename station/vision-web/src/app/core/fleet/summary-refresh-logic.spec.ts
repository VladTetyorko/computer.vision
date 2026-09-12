import { describe, expect, it } from 'vitest';
import {
  SUMMARY_INVALIDATION_DEBOUNCE_MS,
  anyNamesListedAsset,
  invalidationDelayMs,
  listedAssetIds,
} from './summary-refresh-logic';
import type { AssetAttention, DetectionEvent, FleetSummary } from '../api/models';

function attention(assetId: string): AssetAttention {
  return {
    assetId,
    displayName: assetId,
    categoryId: 'drone',
    categoryName: 'Drone',
    lifecycle: 'ACTIVE',
    streaming: false,
  } as AssetAttention;
}

function summary(assetIds: readonly string[]): FleetSummary {
  return { categories: [], assets: assetIds.map(attention), totalAssets: assetIds.length };
}

function event(partial: Partial<DetectionEvent> = {}): DetectionEvent {
  return {
    id: 'e-1',
    streamId: 's-1',
    label: 'person',
    peakConfidence: 0.9,
    firstSeen: '2026-09-07T00:00:00Z',
    lastSeen: '2026-09-07T00:00:01Z',
    state: 'OPEN',
    ...partial,
  } as DetectionEvent;
}

describe('invalidationDelayMs (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §5 L8a)', () => {
  it('goes immediately when nothing has ever been fetched', () => {
    expect(invalidationDelayMs(0, 1_000_000)).toBe(0);
  });

  it('goes immediately once the debounce window has fully elapsed', () => {
    expect(invalidationDelayMs(1_000, 1_000 + SUMMARY_INVALIDATION_DEBOUNCE_MS)).toBe(0);
    expect(invalidationDelayMs(1_000, 1_000 + SUMMARY_INVALIDATION_DEBOUNCE_MS + 5_000)).toBe(0);
  });

  it('waits out only the remainder of the window mid-flight', () => {
    expect(invalidationDelayMs(1_000, 1_500, 2_000)).toBe(1_500);
    expect(invalidationDelayMs(1_000, 2_900, 2_000)).toBe(100);
  });

  it('never returns more than one full window, even if the clock jumped backwards', () => {
    // A backwards clock step (NTP correction, a suspended laptop resuming) must not park the next
    // refetch arbitrarily far in the future — the worst case stays one debounce window.
    expect(invalidationDelayMs(10_000, 1_000, 2_000)).toBe(2_000);
  });

  it('never returns a negative delay', () => {
    expect(invalidationDelayMs(1_000, 99_000, 2_000)).toBe(0);
  });
});

describe('detection-event invalidation filtering', () => {
  it('lists exactly the summary\'s own asset ids, and nothing for an absent summary', () => {
    expect([...listedAssetIds(summary(['a-1', 'a-2']))]).toEqual(['a-1', 'a-2']);
    expect(listedAssetIds(undefined).size).toBe(0);
  });

  it('invalidates on an event naming a listed asset', () => {
    const listed = listedAssetIds(summary(['a-1']));
    expect(anyNamesListedAsset([event({ assetId: 'a-1' })], listed)).toBe(true);
  });

  it('ignores an event naming an asset this viewer\'s summary does not list', () => {
    const listed = listedAssetIds(summary(['a-1']));
    expect(anyNamesListedAsset([event({ assetId: 'other' })], listed)).toBe(false);
  });

  it('ignores an event with no assetId at all — it names nothing to refresh', () => {
    const listed = listedAssetIds(summary(['a-1']));
    expect(anyNamesListedAsset([event()], listed)).toBe(false);
  });

  it('invalidates if any one event in a batch qualifies', () => {
    const listed = listedAssetIds(summary(['a-1']));
    expect(anyNamesListedAsset([event(), event({ assetId: 'a-1' })], listed)).toBe(true);
  });

  it('an empty batch never invalidates', () => {
    expect(anyNamesListedAsset([], listedAssetIds(summary(['a-1'])))).toBe(false);
  });
});
