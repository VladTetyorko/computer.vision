import { describe, expect, it } from 'vitest';
import type { AssetSummary, AssetUsage, GeoPosition } from '../../core/api/models';
import {
  ALL_DRONES_OPTION_VALUE,
  cycleBoxesMode,
  isAllDronesOption,
  isSwitcherOptionSelected,
  isWatchMode,
  lastSeenLabel,
  latestFinishedUsage,
  nextCollapseAction,
  positionLabel,
  rememberedStreamingAssetId,
  showDetectionOffChip,
  sortAssetsForPicker,
  streamStateLabel,
} from './fly-logic';

function asset(partial: Partial<AssetSummary>): AssetSummary {
  return {
    assetId: 'a-0',
    displayName: 'Asset',
    category: 'drone',
    categoryName: 'Drone',
    owner: 'owner-0',
    status: 'OFFLINE',
    attributes: {},
    ...partial,
  };
}

function usage(partial: Partial<AssetUsage>): AssetUsage {
  return { usageId: 'u-0', startedAt: '2026-07-22T00:00:00Z', sampleCount: 0, ...partial };
}

describe('sortAssetsForPicker', () => {
  it('puts streaming assets before offline ones regardless of name', () => {
    const offlineZ = asset({ assetId: 'z', displayName: 'Zulu', status: 'OFFLINE' });
    const streamingA = asset({ assetId: 'a', displayName: 'Alpha', status: 'STREAMING' });
    expect(sortAssetsForPicker([offlineZ, streamingA]).map((a) => a.assetId)).toEqual(['a', 'z']);
  });

  it('sorts alphabetically, case-insensitively, within each status group', () => {
    const bravo = asset({ assetId: 'b', displayName: 'bravo' });
    const alpha = asset({ assetId: 'a', displayName: 'Alpha' });
    expect(sortAssetsForPicker([bravo, alpha]).map((a) => a.assetId)).toEqual(['a', 'b']);
  });

  it('does not mutate the input array', () => {
    const list = [asset({ assetId: 'b', displayName: 'B' }), asset({ assetId: 'a', displayName: 'A' })];
    const original = [...list];
    sortAssetsForPicker(list);
    expect(list).toEqual(original);
  });

  it('returns an empty array for an empty fleet', () => {
    expect(sortAssetsForPicker([])).toEqual([]);
  });
});

describe('rememberedStreamingAssetId (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.5 F12 — "/fly skips the picker when it has nothing to ask")', () => {
  const streaming = asset({ assetId: 'known-1', status: 'STREAMING' });
  const offline = asset({ assetId: 'known-2', status: 'OFFLINE' });
  const assets = [streaming, offline];

  it('redirects to the remembered drone when it is still streaming', () => {
    expect(rememberedStreamingAssetId(assets, 'known-1')).toBe('known-1');
  });

  it('does not redirect when the remembered drone exists but has landed — the picker still has something to ask', () => {
    expect(rememberedStreamingAssetId(assets, 'known-2')).toBeUndefined();
  });

  it('does not redirect when the remembered id no longer exists (archived/deleted since)', () => {
    expect(rememberedStreamingAssetId(assets, 'gone')).toBeUndefined();
  });

  it('does not redirect when nothing is remembered', () => {
    expect(rememberedStreamingAssetId(assets, null)).toBeUndefined();
  });
});

describe('latestFinishedUsage', () => {
  it('finds the first usage carrying an endedAt (newest-first input)', () => {
    const open = usage({ usageId: 'open' });
    const finished = usage({ usageId: 'finished', endedAt: '2026-07-22T01:00:00Z' });
    expect(latestFinishedUsage([open, finished])).toBe(finished);
  });

  it('returns undefined when every usage is still open', () => {
    expect(latestFinishedUsage([usage({ usageId: 'open' })])).toBeUndefined();
  });

  it('returns undefined for no usage history at all', () => {
    expect(latestFinishedUsage([])).toBeUndefined();
  });
});

describe('isWatchMode', () => {
  it('is true only for the exact literal "1"', () => {
    expect(isWatchMode('1')).toBe(true);
  });

  it('is false for anything else, including other truthy-looking strings', () => {
    expect(isWatchMode('true')).toBe(false);
    expect(isWatchMode('0')).toBe(false);
    expect(isWatchMode('')).toBe(false);
    expect(isWatchMode(undefined)).toBe(false);
  });
});

describe('showDetectionOffChip (docs/plans/active/CV-DEMAND-PLAN.md wave D3 — the cockpit\'s video-surface "Detection is off" affordance)', () => {
  it('shows once a stream is live and detection is off', () => {
    expect(showDetectionOffChip(true, false)).toBe(true);
  });

  it('stays hidden while detection is on, live or not', () => {
    expect(showDetectionOffChip(true, true)).toBe(false);
    expect(showDetectionOffChip(false, true)).toBe(false);
  });

  it('stays hidden before Start even if the draft would start dark — no video yet to call "video only"', () => {
    expect(showDetectionOffChip(false, false)).toBe(false);
  });
});

describe('cycleBoxesMode', () => {
  it('cycles overlay -> burned -> off -> overlay', () => {
    expect(cycleBoxesMode('overlay')).toBe('burned');
    expect(cycleBoxesMode('burned')).toBe('off');
    expect(cycleBoxesMode('off')).toBe('overlay');
  });
});

describe('isSwitcherOptionSelected (BROKEN #2 — switcher selection race, docs/plans/done/UX-QUICKWINS-PLAN.md QF-1)', () => {
  it('selects the option matching the active asset', () => {
    expect(isSwitcherOptionSelected('drone-a', 'drone-a')).toBe(true);
  });

  it('does not select an option that is not the active asset', () => {
    expect(isSwitcherOptionSelected('drone-b', 'drone-a')).toBe(false);
  });

  it('selects nothing while no asset is active yet', () => {
    expect(isSwitcherOptionSelected('drone-a', undefined)).toBe(false);
  });

  it('picks exactly the active asset regardless of where it falls in option order — the whole point of the fix', () => {
    // `drone-a` is neither first nor alphabetically first: the bug this replaces (`[value]` on the
    // `<select>` racing its own `<option>` children) always defaulted to the first-listed option
    // instead, so this must hold for every position, not just "happens to be first".
    const optionIds = ['zulu-drone', 'alpha-drone', 'mike-drone'];
    expect(optionIds.map((id) => isSwitcherOptionSelected(id, 'alpha-drone'))).toEqual([false, true, false]);

    const reordered = ['alpha-drone', 'zulu-drone', 'mike-drone'];
    expect(reordered.map((id) => isSwitcherOptionSelected(id, 'alpha-drone'))).toEqual([true, false, false]);
  });
});

// `trackingIdChanged` itself is tested in `core/telemetry/telemetry-logic.spec.ts` now that it
// lives there (docs/plans/done/REALTIME-PLAN.md §4 Phase R-c follow-up) — `fly-logic.ts` only re-exports it.

describe('streamStateLabel (docs/plans/done/UX-REWORK-PLAN.md §U-a2 — the picker card states its stream state as a word)', () => {
  it('reads "Streaming" for a streaming asset', () => {
    expect(streamStateLabel('STREAMING')).toBe('Streaming');
  });

  it('reads "Offline" for anything else', () => {
    expect(streamStateLabel('OFFLINE')).toBe('Offline');
  });
});

describe('lastSeenLabel', () => {
  const nowMs = Date.parse('2026-07-24T12:00:00Z');

  it('is undefined for an asset that has never been used', () => {
    expect(lastSeenLabel(undefined, nowMs)).toBeUndefined();
  });

  it('renders elapsed time since lastUsedAt, reusing formatDuration\'s own wording', () => {
    const fourMinutesAgo = '2026-07-24T11:55:53Z'; // 4m 07s before nowMs
    expect(lastSeenLabel(fourMinutesAgo, nowMs)).toBe('4m 07s ago');
  });

  it('never goes negative for a clock-skewed future timestamp', () => {
    const future = '2026-07-24T12:05:00Z';
    expect(lastSeenLabel(future, nowMs)).toBe('0s ago');
  });
});

describe('positionLabel', () => {
  it('is undefined when the asset has never reported a fix', () => {
    expect(positionLabel(undefined)).toBeUndefined();
  });

  it('formats lat/lon to 4 decimal places, altitude omitted', () => {
    const position: GeoPosition = { latitude: 37.774929, longitude: -122.419416, altitudeMeters: 120 };
    expect(positionLabel(position)).toBe('37.7749, -122.4194');
  });
});

describe('isAllDronesOption (docs/plans/done/UX-REWORK-PLAN.md §U-a bullet 4 — merges "All drones" into the switcher)', () => {
  it('is true for the sentinel value', () => {
    expect(isAllDronesOption(ALL_DRONES_OPTION_VALUE)).toBe(true);
  });

  it('is false for a real asset id', () => {
    expect(isAllDronesOption('known-1')).toBe(false);
  });
});

describe('nextCollapseAction (docs/plans/done/UI-REDESIGN-PLAN.md Wave 2 D-D — Esc\'s "closest thing open, first")', () => {
  it('closes an open tool-rail drawer first, even if the stop-confirm/map are also open', () => {
    expect(nextCollapseAction({ panelOpen: true, stopConfirmOpen: true, mapVisible: true })).toBe('panel');
  });

  it('closes the stop-confirm next once no drawer is open', () => {
    expect(nextCollapseAction({ panelOpen: false, stopConfirmOpen: true, mapVisible: true })).toBe('stop-confirm');
  });

  it('hides the map inset last, once nothing else is open', () => {
    expect(nextCollapseAction({ panelOpen: false, stopConfirmOpen: false, mapVisible: true })).toBe('map');
  });

  it('is a no-op when nothing is open', () => {
    expect(nextCollapseAction({ panelOpen: false, stopConfirmOpen: false, mapVisible: false })).toBeNull();
  });
});
