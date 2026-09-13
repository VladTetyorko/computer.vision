import { describe, expect, it } from 'vitest';
import type {
  AssetSummary,
  AssetUsage,
  AuthCapability,
  GeoPosition,
  Membership,
  ScopeKind,
  SeatHolderResponse,
  SubsystemStatus,
  WorldObject,
} from '../../core/api/models';
import type { PreflightSummary } from '../../core/telemetry/flight-state-logic';
import {
  ALL_DRONES_OPTION_VALUE,
  REPLAY_PICKER_MAX_USAGES,
  cameraHeldByOther,
  cameraHolderLabel,
  commandSurfaceVisible,
  crewCameraDockLine,
  dockPreflightSummaryLabel,
  earlierReplayableUsages,
  flyHeroStatus,
  flyStage,
  isAllDronesOption,
  isAutostart,
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
  type FlyStageInput,
} from './fly-logic';

const FREE_CAMERA_SEAT: SeatHolderResponse = {
  holderUserId: null,
  holderDisplayName: null,
  acquiredAt: null,
  expiresAt: null,
  mine: false,
};

function heldCameraSeat(partial: Partial<SeatHolderResponse> = {}): SeatHolderResponse {
  return {
    holderUserId: 'user-crew-1',
    holderDisplayName: 'Anna',
    acquiredAt: '2026-09-05T10:00:00Z',
    expiresAt: '2026-09-05T10:00:15Z',
    mine: false,
    ...partial,
  };
}

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

function stageInput(partial: Partial<FlyStageInput>): FlyStageInput {
  return { live: false, stopped: false, busy: false, streamState: undefined, operatorEngaged: false, ...partial };
}

describe('flyStage', () => {
  it('is idle when nothing is happening', () => {
    expect(flyStage(stageInput({}))).toBe('idle');
  });

  it('is starting while a Start click is in flight', () => {
    expect(flyStage(stageInput({ busy: true }))).toBe('starting');
  });

  it('is live once a stream exists, regardless of streamState', () => {
    expect(flyStage(stageInput({ live: true, streamState: 'STARTING' }))).toBe('live');
    expect(flyStage(stageInput({ live: true, streamState: 'LIVE' }))).toBe('live');
  });

  it('is engaged when a telemetry-only asset has an open operator session with no video', () => {
    expect(flyStage(stageInput({ operatorEngaged: true }))).toBe('engaged');
  });

  it('prefers live over busy', () => {
    // A stream can be `live` while `busy` also happens to still be true (e.g. Stop just clicked) —
    // the video already exists, so the compact live row is the honest thing to show, not the
    // idle/starting card.
    expect(flyStage(stageInput({ live: true, busy: true }))).toBe('live');
  });

  it('prefers live over operatorEngaged — resolveSessionAffordance already stands the session-engage affordance down once live', () => {
    expect(flyStage(stageInput({ live: true, operatorEngaged: true }))).toBe('live');
  });

  it('prefers busy over operatorEngaged', () => {
    expect(flyStage(stageInput({ busy: true, operatorEngaged: true }))).toBe('starting');
  });

  it('ignores stopped/streamState for the idle/starting split — the dock card is honest even for a dropped-out stream', () => {
    expect(flyStage(stageInput({ stopped: true }))).toBe('idle');
    expect(flyStage(stageInput({ stopped: true, busy: true }))).toBe('starting');
  });
});

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

describe('earlierReplayableUsages', () => {
  it('drops the newest finished usage, keeping the rest in order', () => {
    const usages = [
      usage({ usageId: 'newest', endedAt: '2026-07-22T03:00:00Z' }),
      usage({ usageId: 'middle', endedAt: '2026-07-22T02:00:00Z' }),
      usage({ usageId: 'oldest', endedAt: '2026-07-22T01:00:00Z' }),
    ];
    expect(earlierReplayableUsages(usages).map((u) => u.usageId)).toEqual(['middle', 'oldest']);
  });

  it('filters out still-open usages entirely, even ahead of the newest finished one', () => {
    const usages = [
      usage({ usageId: 'open' }),
      usage({ usageId: 'newest', endedAt: '2026-07-22T03:00:00Z' }),
      usage({ usageId: 'older', endedAt: '2026-07-22T01:00:00Z' }),
    ];
    expect(earlierReplayableUsages(usages).map((u) => u.usageId)).toEqual(['older']);
  });

  it('caps at the given max', () => {
    const usages = Array.from({ length: 10 }, (_, i) =>
      usage({ usageId: `u-${i}`, endedAt: `2026-07-22T0${i}:00:00Z` }),
    );
    expect(earlierReplayableUsages(usages, 2)).toHaveLength(2);
  });

  it('defaults to REPLAY_PICKER_MAX_USAGES', () => {
    const usages = Array.from({ length: REPLAY_PICKER_MAX_USAGES + 5 }, (_, i) =>
      usage({ usageId: `u-${i}`, endedAt: `2026-07-22T0${i % 10}:00:00Z` }),
    );
    expect(earlierReplayableUsages(usages)).toHaveLength(REPLAY_PICKER_MAX_USAGES);
  });

  it('returns an empty list with zero or one finished usage', () => {
    expect(earlierReplayableUsages([])).toEqual([]);
    expect(earlierReplayableUsages([usage({ usageId: 'only', endedAt: '2026-07-22T01:00:00Z' })])).toEqual([]);
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

describe('isAutostart (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §3.3 "the terminal action")', () => {
  it('is true only for the exact literal "1"', () => {
    expect(isAutostart('1')).toBe(true);
  });

  it('is false for anything else, including other truthy-looking strings', () => {
    expect(isAutostart('true')).toBe(false);
    expect(isAutostart('0')).toBe(false);
    expect(isAutostart('')).toBe(false);
    expect(isAutostart(undefined)).toBe(false);
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

function summary(partial: Partial<PreflightSummary>): PreflightSummary {
  return { ok: 5, fail: 0, unknown: 0, state: 'ok', label: 'All clear', ...partial };
}

describe('dockPreflightSummaryLabel (docs/plans/active/FLY-FLOW-PLAN.md §4 W4 item 3b — the idle dock card\'s own quiet pre-flight line)', () => {
  it('reads "Pre-flight complete" once every row clears', () => {
    expect(dockPreflightSummaryLabel(summary({ state: 'ok', label: 'All clear' }))).toBe('Pre-flight complete');
  });

  it('reads "Pre-flight: N unchecked" while rows are still resolving, the owner\'s own example wording', () => {
    expect(dockPreflightSummaryLabel(summary({ state: 'unknown', unknown: 2, label: '2 unchecked' }))).toBe('Pre-flight: 2 unchecked');
  });

  it('never softens a real blocker into "unchecked" — a fail summary keeps its own blocker count', () => {
    expect(dockPreflightSummaryLabel(summary({ state: 'fail', fail: 1, label: '1 blocker' }))).toBe('Pre-flight: 1 blocker');
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

describe('pickerEmptyStateCopy (docs/plans/done/OPS-UX-PLAN.md §2 A2 — truthful Fly-picker empty state; scopeKind/capabilities per docs/plans/active/AUTH-ROLES-PLAN.md §3.2, wave W2)', () => {
  const PILOT_CAPS: readonly AuthCapability[] = ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT'];
  const MANAGER_CAPS: readonly AuthCapability[] = ['OPERATE_PAYLOAD', 'COMMAND_FLIGHT', 'MANAGE_FLEET', 'MANAGE_ORG'];

  it('an ASSIGNED_ASSETS scope (a PILOT session) with one membership is told which group has nobody assigned to them', () => {
    const state = pickerEmptyStateCopy('ASSIGNED_ASSETS', PILOT_CAPS, [membership('Alpha Squadron')]);
    expect(state.title).toBe('No aircraft assigned to you yet');
    expect(state.message).toContain('Alpha Squadron');
    expect(state.showAddSource).toBe(false);
  });

  it('a PILOT in several groups gets every distinct group name named, never just the first', () => {
    const state = pickerEmptyStateCopy('ASSIGNED_ASSETS', PILOT_CAPS, [membership('Alpha Squadron'), membership('Bravo Team')]);
    expect(state.message).toContain('Alpha Squadron');
    expect(state.message).toContain('Bravo Team');
  });

  it('de-duplicates a repeated group name rather than naming it twice', () => {
    const state = pickerEmptyStateCopy('ASSIGNED_ASSETS', PILOT_CAPS, [membership('Alpha Squadron'), membership('Alpha Squadron')]);
    expect(state.message.match(/Alpha Squadron/g)?.length).toBe(1);
  });

  it('a PILOT with no memberships at all gets an honest "could not determine" message, never a fabricated group', () => {
    const state = pickerEmptyStateCopy('ASSIGNED_ASSETS', PILOT_CAPS, []);
    expect(state.title).toBe('No aircraft assigned to you yet');
    expect(state.message).not.toMatch(/undefined|null/i);
    expect(state.message.toLowerCase()).toContain('could not determine');
    expect(state.showAddSource).toBe(false);
  });

  it('a PILOT never gets the Add source CTA, even with memberships resolved (and even if `capabilities` somehow carried MANAGE_ORG — ASSIGNED_ASSETS scope alone suppresses it)', () => {
    expect(pickerEmptyStateCopy('ASSIGNED_ASSETS', ['MANAGE_ORG'], [membership('Alpha Squadron')]).showAddSource).toBe(false);
  });

  it.each<readonly [ScopeKind | null | undefined, readonly AuthCapability[]]>([
    ['GROUPS', MANAGER_CAPS], // MANAGER
    ['UNBOUNDED', MANAGER_CAPS], // ADMIN
    ['GROUPS', []], // VIEWER — GROUPS scope, no MANAGE_ORG
    [undefined, []], // not-yet-loaded session
    [null, []],
  ])('scope=%s/capabilities=%s sees the genuinely-empty-fleet message, Add source CTA following MANAGE_ORG alone', (scopeKind, capabilities) => {
    const state = pickerEmptyStateCopy(scopeKind, capabilities, []);
    expect(state.title).toBe('No drones registered yet');
    expect(state.showAddSource).toBe(capabilities.includes('MANAGE_ORG'));
  });

  it('MANAGER/ADMIN copy is unaffected by memberships — the fleet-empty message never mentions a group', () => {
    const state = pickerEmptyStateCopy('GROUPS', MANAGER_CAPS, [membership('Alpha Squadron')]);
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

describe('cameraHeldByOther (docs/plans/active/CREW-CONTROL-PLAN.md §3.2 rule 2, wave W4)', () => {
  it('is false for a genuinely free seat, even though `mine` is also false', () => {
    expect(cameraHeldByOther(FREE_CAMERA_SEAT)).toBe(false);
  });

  it('is false for a seat this pilot themself holds', () => {
    expect(cameraHeldByOther(heldCameraSeat({ mine: true }))).toBe(false);
  });

  it('is true for a seat a crew member holds', () => {
    expect(cameraHeldByOther(heldCameraSeat())).toBe(true);
  });
});

describe('cameraHolderLabel', () => {
  it('reads the held seat’s display name', () => {
    expect(cameraHolderLabel(heldCameraSeat({ holderDisplayName: 'Anna Kovalenko' }))).toBe('Anna Kovalenko');
  });

  it('degrades honestly to a generic phrase if the wire ever violated its own non-null contract', () => {
    expect(cameraHolderLabel(heldCameraSeat({ holderDisplayName: null }))).toBe('another operator');
  });
});

describe('crewCameraDockLine (docs/plans/active/CREW-CONTROL-PLAN.md §3.5 — the dock’s one crew-presence line)', () => {
  it('is null at rest — a free camera seat renders zero pixels', () => {
    expect(crewCameraDockLine(FREE_CAMERA_SEAT)).toBeNull();
  });

  it('is null while this pilot themself holds the camera', () => {
    expect(crewCameraDockLine(heldCameraSeat({ mine: true }))).toBeNull();
  });

  it('names the crew member exactly as §3.5’s own worked example — "Crew · Anna on camera"', () => {
    expect(crewCameraDockLine(heldCameraSeat({ holderDisplayName: 'Anna' }))).toBe('Crew · Anna on camera');
  });

  it('falls back to the generic label rather than fabricating a name', () => {
    expect(crewCameraDockLine(heldCameraSeat({ holderDisplayName: null }))).toBe('Crew · another operator on camera');
  });
});

describe('commandSurfaceVisible (docs/plans/active/CREW-CONTROL-PLAN.md §2.3 D1, wave W4 — the fix for the fly-hud gate that used to ignore watch mode entirely)', () => {
  it('mounts the command surface when commandable and not watching', () => {
    expect(commandSurfaceVisible(true, false)).toBe(true);
  });

  it('hides the command surface in watch mode even on an otherwise-commandable vehicle — the D1 fix itself', () => {
    expect(commandSurfaceVisible(true, true)).toBe(false);
  });

  it('stays hidden when not commandable, watch mode or not', () => {
    expect(commandSurfaceVisible(false, false)).toBe(false);
    expect(commandSurfaceVisible(false, true)).toBe(false);
  });
});

function worldObject(id: number, tier: WorldObject['render']['tier']): WorldObject {
  return {
    state: { id, lifecycle: 'CONFIRMED', streamId: 's-1' },
    operator: { followed: false, denied: false },
    event: {},
    render: { tier },
  };
}

function subsystem(health: SubsystemStatus['health'], detail = 'cv-service unreachable'): SubsystemStatus {
  return { id: 'cv-service', label: 'CV service', health, detail };
}

describe('flyHeroStatus (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.8, wave W3.3 — the Fly hero’s own status line)', () => {
  it('reports Degraded — <detail> verbatim, regardless of detectionEnabled, when cv-service health is DEGRADED', () => {
    expect(flyHeroStatus(true, subsystem('DEGRADED', 'cv-service restarting'), 'RUNNING', [])).toEqual({
      text: 'Degraded — cv-service restarting',
      tone: 'degraded',
    });
    expect(flyHeroStatus(false, subsystem('DEGRADED', 'cv-service restarting'), undefined, [])).toEqual({
      text: 'Degraded — cv-service restarting',
      tone: 'degraded',
    });
  });

  it('reports Degraded — <detail> for DOWN and UNKNOWN health too, wins over a RUNNING_UNWATCHED tracker state', () => {
    expect(flyHeroStatus(true, subsystem('DOWN', 'cv-service crashed'), 'RUNNING_UNWATCHED', [])).toEqual({
      text: 'Degraded — cv-service crashed',
      tone: 'degraded',
    });
    expect(flyHeroStatus(true, subsystem('UNKNOWN', 'cv-service status unavailable'), 'RUNNING', [])).toEqual({
      text: 'Degraded — cv-service status unavailable',
      tone: 'degraded',
    });
  });

  it('does not treat OK or DISABLED health as degraded', () => {
    expect(flyHeroStatus(false, subsystem('OK'), undefined, [])).toEqual({ text: 'Off', tone: 'off' });
    expect(flyHeroStatus(false, subsystem('DISABLED'), undefined, [])).toEqual({ text: 'Off', tone: 'off' });
  });

  it('does not guess a fault from a system-status read that has not completed yet', () => {
    expect(flyHeroStatus(false, undefined, undefined, [])).toEqual({ text: 'Off', tone: 'off' });
  });

  it('reports Off when detection is not enabled, health permitting', () => {
    expect(flyHeroStatus(false, subsystem('OK'), 'RUNNING', [worldObject(1, 'T0')])).toEqual({
      text: 'Off',
      tone: 'off',
    });
  });

  it('reports On — no viewer when the tracker is running unwatched, without counting objects', () => {
    expect(flyHeroStatus(true, subsystem('OK'), 'RUNNING_UNWATCHED', [worldObject(1, 'T0'), worldObject(2, 'T1')])).toEqual({
      text: 'On — no viewer',
      tone: 'warn',
    });
  });

  it('reports On — N objects, counting only non-HIDDEN render tiers', () => {
    const objects = [worldObject(1, 'T0'), worldObject(2, 'HIDDEN'), worldObject(3, 'T2')];
    expect(flyHeroStatus(true, subsystem('OK'), 'RUNNING', objects)).toEqual({
      text: 'On — 2 objects',
      tone: 'on',
    });
  });

  it('renders the N=0 edge case literally as On — 0 objects, never a softened phrase', () => {
    expect(flyHeroStatus(true, subsystem('OK'), 'RUNNING', [])).toEqual({ text: 'On — 0 objects', tone: 'on' });
    expect(flyHeroStatus(true, undefined, 'RUNNING', [])).toEqual({ text: 'On — 0 objects', tone: 'on' });
  });

  it('singularizes exactly one object', () => {
    expect(flyHeroStatus(true, subsystem('OK'), 'RUNNING', [worldObject(1, 'T0')])).toEqual({
      text: 'On — 1 object',
      tone: 'on',
    });
  });
});
