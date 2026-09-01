import { describe, expect, it } from 'vitest';
import type { AssetSummary, AssetUsage, GeoPosition, Membership, Role } from '../../core/api/models';
import {
  ALL_DRONES_OPTION_VALUE,
  isAllDronesOption,
  isSwitcherOptionSelected,
  isWatchMode,
  lastSeenLabel,
  latestFinishedUsage,
  migratedPanelId,
  nextCollapseAction,
  pickerEmptyStateCopy,
  positionFact,
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

describe('showDetectionOffChip (docs/plans/done/CV-DEMAND-PLAN.md wave D3 — the cockpit\'s video-surface "Detection is off" affordance)', () => {
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

  it('renders elapsed time since lastUsedAt via humanAge (docs/plans/active/OPERATOR-UX-4-PLAN.md finding N4, this cycle\'s W5 — not formatDuration\'s zero-padded wording)', () => {
    const fourMinutesAgo = '2026-07-24T11:55:53Z'; // 4m 7s before nowMs
    expect(lastSeenLabel(fourMinutesAgo, nowMs)).toBe('4m 7s ago');
  });

  it('renders a multi-day age past the hour-capped register formatDuration used to force it into', () => {
    const threeDaysAgo = '2026-07-20T18:00:00Z'; // 3d 18h before nowMs
    expect(lastSeenLabel(threeDaysAgo, nowMs)).toBe('3d 18h ago');
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

describe('positionFact (docs/plans/active/OPERATOR-UX-4-PLAN.md finding 1, this cycle\'s W5 — reproduced live: "Your vehicles" picker cards printed POSITION 0.0000, 0.0000 for a no-fix rover)', () => {
  it('is undefined when the asset has never reported a position at all — the card omits the fact', () => {
    expect(positionFact(undefined)).toBeUndefined();
  });

  it('reads the faint "No GPS fix yet" register for a no-fix position (Null Island), never a fabricated coordinate', () => {
    expect(positionFact({ latitude: 0, longitude: 0 })).toEqual({ value: 'No GPS fix yet', faint: true });
  });

  it('formats a real fix in the mono numeric register, reusing positionLabel', () => {
    const position: GeoPosition = { latitude: 37.774929, longitude: -122.419416, altitudeMeters: 120 };
    expect(positionFact(position)).toEqual({ value: '37.7749, -122.4194', mono: true });
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
  it('closes the CV setup modal first, even if a drawer/stop-confirm/map are also open (docs/plans/done/CV-PANEL-SPLIT-PLAN.md P1)', () => {
    expect(nextCollapseAction({ cvSetupOpen: true, panelOpen: true, stopConfirmOpen: true, mapVisible: true })).toBe('cv-setup');
  });

  it('closes an open tool-rail drawer next, once the setup modal is closed, even if the stop-confirm/map are also open', () => {
    expect(nextCollapseAction({ cvSetupOpen: false, panelOpen: true, stopConfirmOpen: true, mapVisible: true })).toBe('panel');
  });

  it('closes the stop-confirm next once no modal or drawer is open', () => {
    expect(nextCollapseAction({ cvSetupOpen: false, panelOpen: false, stopConfirmOpen: true, mapVisible: true })).toBe('stop-confirm');
  });

  it('hides the map inset last, once nothing else is open', () => {
    expect(nextCollapseAction({ cvSetupOpen: false, panelOpen: false, stopConfirmOpen: false, mapVisible: true })).toBe('map');
  });

  it('is a no-op when nothing is open', () => {
    expect(nextCollapseAction({ cvSetupOpen: false, panelOpen: false, stopConfirmOpen: false, mapVisible: false })).toBeNull();
  });
});

function membership(groupName: string, role: Membership['role'] = 'PILOT'): Membership {
  return { groupId: `g-${groupName}`, groupName, role };
}

describe('pickerEmptyStateCopy (docs/plans/done/OPS-UX-PLAN.md §2 A2 — truthful Fly-picker empty state)', () => {
  it('a PILOT with one membership is told which group has nobody assigned to them', () => {
    const state = pickerEmptyStateCopy('PILOT', [membership('Alpha Squadron')]);
    expect(state.title).toBe('No aircraft assigned to you yet');
    expect(state.message).toContain('Alpha Squadron');
    expect(state.showAddSource).toBe(false);
  });

  it('a PILOT in several groups gets every distinct group name named, never just the first', () => {
    const state = pickerEmptyStateCopy('PILOT', [membership('Alpha Squadron'), membership('Bravo Team')]);
    expect(state.message).toContain('Alpha Squadron');
    expect(state.message).toContain('Bravo Team');
  });

  it('de-duplicates a repeated group name rather than naming it twice', () => {
    const state = pickerEmptyStateCopy('PILOT', [membership('Alpha Squadron'), membership('Alpha Squadron')]);
    expect(state.message.match(/Alpha Squadron/g)?.length).toBe(1);
  });

  it('a PILOT with no memberships at all gets an honest "could not determine" message, never a fabricated group', () => {
    const state = pickerEmptyStateCopy('PILOT', []);
    expect(state.title).toBe('No aircraft assigned to you yet');
    expect(state.message).not.toMatch(/undefined|null/i);
    expect(state.message.toLowerCase()).toContain('could not determine');
    expect(state.showAddSource).toBe(false);
  });

  it('a PILOT never gets the Add source CTA, even with memberships resolved', () => {
    expect(pickerEmptyStateCopy('PILOT', [membership('Alpha Squadron')]).showAddSource).toBe(false);
  });

  it.each<Role | null | undefined>(['MANAGER', 'ADMIN', undefined, null])(
    '%s sees the genuinely-empty-fleet message, with the Add source CTA offered',
    (topRole) => {
      const state = pickerEmptyStateCopy(topRole, []);
      expect(state.title).toBe('No drones registered yet');
      expect(state.showAddSource).toBe(topRole === 'MANAGER' || topRole === 'ADMIN');
    },
  );

  it('MANAGER/ADMIN copy is unaffected by memberships — the fleet-empty message never mentions a group', () => {
    const state = pickerEmptyStateCopy('MANAGER', [membership('Alpha Squadron')]);
    expect(state.message).not.toContain('Alpha Squadron');
  });
});

describe('migratedPanelId (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md C10 — the retired `flight` drawer)', () => {
  it('lands a persisted `flight` on the drawer that absorbed it', () => {
    expect(migratedPanelId('flight')).toBe('rc');
  });

  it('leaves every id the rail still has exactly as it was', () => {
    for (const id of ['rc', 'cv', 'marks', 'map', 'help']) {
      expect(migratedPanelId(id)).toBe(id);
    }
  });

  it('keeps "nothing was open" meaning nothing is open', () => {
    expect(migratedPanelId(null)).toBeNull();
  });
});
