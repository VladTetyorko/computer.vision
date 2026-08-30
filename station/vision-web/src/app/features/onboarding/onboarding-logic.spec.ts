import { describe, expect, it } from 'vitest';
import type { Membership, UserSummary } from '../../core/api/models';
import { emptyFitOutRows, type FitOutRowDraft, type FitOutRows } from '../../core/onboarding/fit-out-logic';
import {
  buildCreateAssetRequest,
  buildIdentityRequest,
  buildPostSimulationAssetEdit,
  buildProbeRequest,
  buildVerifyRequest,
  canAdvanceFromIdentify,
  creatorOwnershipGroup,
  defaultPilotSelection,
  isTelemetryOnlyProtocol,
  nextStep,
  pilotsInGroup,
  prevStep,
  simulateNeedsVideoPath,
  visibleSteps,
  type IdentifyDraft,
  type StepContext,
} from './onboarding-logic';

function identify(partial: Partial<IdentifyDraft> = {}): IdentifyDraft {
  return {
    displayName: 'Falcon-2',
    category: 'drone',
    registrationNumber: '',
    serialNumber: '',
    make: '',
    model: '',
    ...partial,
  };
}

function ctx(partial: Partial<StepContext> = {}): StepContext {
  return { connected: true, needsProve: true, ...partial };
}

function row(partial: Partial<FitOutRowDraft> & Pick<FitOutRowDraft, 'role'>): FitOutRowDraft {
  return {
    value: 'none',
    findMethod: null,
    protocolSelect: '',
    customProtocol: '',
    uri: '',
    options: [],
    ...partial,
  };
}

function rows(sense: Partial<FitOutRowDraft> = {}, sight: Partial<FitOutRowDraft> = {}): FitOutRows {
  return { sense: row({ role: 'sense', ...sense }), sight: row({ role: 'sight', ...sight }) };
}

describe('visibleSteps', () => {
  it('shows all five for a connected category', () => {
    expect(visibleSteps(true)).toEqual(['identify', 'connect', 'prove', 'register', 'handover']);
  });

  it('collapses to just Identify and Hand over for equipment', () => {
    expect(visibleSteps(false)).toEqual(['identify', 'handover']);
  });
});

describe('nextStep', () => {
  it('goes identify -> connect for a connected category', () => {
    expect(nextStep('identify', ctx({ connected: true }))).toBe('connect');
  });

  it('goes identify -> register directly for equipment (the Receive short-circuit)', () => {
    expect(nextStep('identify', ctx({ connected: false }))).toBe('register');
  });

  it('goes connect -> prove when a row needs proving', () => {
    expect(nextStep('connect', ctx({ needsProve: true }))).toBe('prove');
  });

  it('skips prove entirely when no row needs it — connect -> register', () => {
    expect(nextStep('connect', ctx({ needsProve: false }))).toBe('register');
  });

  it('goes prove -> register', () => {
    expect(nextStep('prove', ctx())).toBe('register');
  });

  it('is a no-op past register', () => {
    expect(nextStep('register', ctx())).toBe('register');
  });

  it('is a no-op past handover — the wizard reaches it only via a successful create, never via next()', () => {
    expect(nextStep('handover', ctx())).toBe('handover');
  });

  it('is a no-op past sysid — the wizard reaches it only via OnboardingStore#finishCreate, never via next()', () => {
    expect(nextStep('sysid', ctx())).toBe('sysid');
  });
});

describe('prevStep', () => {
  it('is a no-op before identify', () => {
    expect(prevStep('identify', ctx())).toBe('identify');
  });

  it('goes connect -> identify', () => {
    expect(prevStep('connect', ctx())).toBe('identify');
  });

  it('goes prove -> connect', () => {
    expect(prevStep('prove', ctx())).toBe('connect');
  });

  it('goes register -> prove when the connect draft needed proving', () => {
    expect(prevStep('register', ctx({ connected: true, needsProve: true }))).toBe('prove');
  });

  it('skips prove entirely for register -> connect when nothing needed proving', () => {
    expect(prevStep('register', ctx({ connected: true, needsProve: false }))).toBe('connect');
  });

  it('goes register -> identify for equipment, regardless of needsProve', () => {
    expect(prevStep('register', ctx({ connected: false }))).toBe('identify');
  });

  it('goes handover -> register — its own immediate predecessor, never further (onboarding.html never renders a Back button here regardless)', () => {
    expect(prevStep('handover', ctx())).toBe('register');
  });

  it('goes sysid -> register — its own immediate predecessor, never further (onboarding.html never renders a Back button here regardless)', () => {
    expect(prevStep('sysid', ctx())).toBe('register');
  });

  it('round-trips with nextStep for a connected category needing prove', () => {
    const context = ctx({ connected: true, needsProve: true });
    let step = nextStep('identify', context);
    step = nextStep(step, context);
    step = nextStep(step, context);
    expect(step).toBe('register');
    let back = prevStep(step, context);
    back = prevStep(back, context);
    back = prevStep(back, context);
    expect(back).toBe('identify');
  });

  it('round-trips with nextStep for equipment', () => {
    const context = ctx({ connected: false });
    const step = nextStep('identify', context);
    expect(step).toBe('register');
    expect(prevStep(step, context)).toBe('identify');
  });
});

describe('canAdvanceFromIdentify', () => {
  it('requires a non-blank name and category', () => {
    expect(canAdvanceFromIdentify('Falcon-2', 'drone')).toBe(true);
    expect(canAdvanceFromIdentify('', 'drone')).toBe(false);
    expect(canAdvanceFromIdentify('Falcon-2', '')).toBe(false);
    expect(canAdvanceFromIdentify('   ', '   ')).toBe(false);
  });
});

describe('simulateNeedsVideoPath', () => {
  it('is true only for the file-based modes', () => {
    expect(simulateNeedsVideoPath('direct')).toBe(true);
    expect(simulateNeedsVideoPath('rtsp')).toBe(true);
    expect(simulateNeedsVideoPath('synthetic')).toBe(false);
    expect(simulateNeedsVideoPath('testDrone')).toBe(false);
  });
});

describe('isTelemetryOnlyProtocol', () => {
  it('recognizes mavlink whatever case or padding the select hands over', () => {
    expect(isTelemetryOnlyProtocol('mavlink')).toBe(true);
    expect(isTelemetryOnlyProtocol('MAVLink')).toBe(true);
    expect(isTelemetryOnlyProtocol('  mavlink  ')).toBe(true);
  });

  it('leaves every video protocol alone, so its Prove row keeps asking for a frame', () => {
    for (const protocol of ['rtsp', 'mjpeg', 'srt', 'udp', 'v4l2', 'file', 'sim']) {
      expect(isTelemetryOnlyProtocol(protocol)).toBe(false);
    }
  });

  it('is false for the not-yet-chosen protocol a fresh row starts in', () => {
    expect(isTelemetryOnlyProtocol('')).toBe(false);
    expect(isTelemetryOnlyProtocol(null)).toBe(false);
    expect(isTelemetryOnlyProtocol(undefined)).toBe(false);
  });
});

describe('buildProbeRequest', () => {
  it('trims protocol and uri', () => {
    expect(buildProbeRequest({ protocol: '  rtsp  ', uri: '  rtsp://x  ' })).toEqual({
      protocol: 'rtsp',
      uri: 'rtsp://x',
    });
  });

  it('omits options when absent or empty', () => {
    expect(buildProbeRequest({ protocol: 'rtsp', uri: 'rtsp://x' })).not.toHaveProperty('options');
    expect(buildProbeRequest({ protocol: 'rtsp', uri: 'rtsp://x', options: {} })).not.toHaveProperty('options');
  });

  it('includes non-empty options verbatim', () => {
    expect(
      buildProbeRequest({ protocol: 'rtsp', uri: 'rtsp://x', options: { rtsp_transport: 'tcp' } }),
    ).toEqual({ protocol: 'rtsp', uri: 'rtsp://x', options: { rtsp_transport: 'tcp' } });
  });
});

describe('buildVerifyRequest', () => {
  it('builds the same trimmed {protocol, uri, options} shape as buildProbeRequest', () => {
    expect(buildVerifyRequest({ protocol: '  mavlink  ', uri: '  udp://0.0.0.0:14550  ' })).toEqual({
      protocol: 'mavlink',
      uri: 'udp://0.0.0.0:14550',
    });
  });
});

describe('buildIdentityRequest', () => {
  it('is undefined when every field is blank', () => {
    expect(buildIdentityRequest(identify())).toBeUndefined();
  });

  it('includes only the non-blank, trimmed fields', () => {
    expect(
      buildIdentityRequest(identify({ registrationNumber: '  N12345  ', serialNumber: 'SN-1' })),
    ).toEqual({ serialNumber: 'SN-1', registration: 'N12345' });
  });

  it('includes make/model too', () => {
    expect(buildIdentityRequest(identify({ make: 'DJI', model: 'Mavic 3' }))).toEqual({
      make: 'DJI',
      model: 'Mavic 3',
    });
  });
});

describe('buildCreateAssetRequest', () => {
  it('creates the asset with one device embedded, named after the asset', () => {
    const oneRow = rows({}, { value: 'find', protocolSelect: 'rtsp', uri: 'rtsp://192.168.1.50:554/stream' });
    expect(buildCreateAssetRequest(identify(), oneRow)).toEqual({
      displayName: 'Falcon-2',
      category: 'drone',
      devices: [{ name: 'Falcon-2', protocol: 'rtsp', uri: 'rtsp://192.168.1.50:554/stream' }],
    });
  });

  it('embeds both devices, role-suffixed, when both rows are filled — a camera + FC in one visit', () => {
    const both = rows(
      { value: 'find', protocolSelect: 'mavlink', uri: 'udp://0.0.0.0:14550' },
      { value: 'find', protocolSelect: 'rtsp', uri: 'rtsp://192.168.1.50:554/stream' },
    );
    const request = buildCreateAssetRequest(identify(), both);
    expect(request.devices?.map((d) => d.name)).toEqual(['Falcon-2 — Sense', 'Falcon-2 — Sight']);
  });

  it('includes identity when any field is given', () => {
    const request = buildCreateAssetRequest(identify({ registrationNumber: 'N12345' }), emptyFitOutRows());
    expect(request.identity).toEqual({ registration: 'N12345' });
  });

  it('omits identity entirely when every field is blank', () => {
    expect(buildCreateAssetRequest(identify(), emptyFitOutRows())).not.toHaveProperty('identity');
  });

  it('omits devices entirely for the equipment short-circuit — zero rows filled', () => {
    expect(buildCreateAssetRequest(identify(), emptyFitOutRows())).not.toHaveProperty('devices');
  });

  it('trims displayName/category', () => {
    const request = buildCreateAssetRequest(identify({ displayName: '  Falcon-2  ', category: '  drone  ' }), emptyFitOutRows());
    expect(request.displayName).toBe('Falcon-2');
    expect(request.category).toBe('drone');
  });
});

describe('buildPostSimulationAssetEdit', () => {
  it('omits displayName when blank', () => {
    expect(buildPostSimulationAssetEdit(identify({ displayName: '   ' }))).toEqual({});
  });

  it('includes a trimmed displayName when given', () => {
    expect(buildPostSimulationAssetEdit(identify({ displayName: '  Falcon-2  ' }))).toEqual({
      displayName: 'Falcon-2',
    });
  });

  it('includes identity when any field is given', () => {
    expect(buildPostSimulationAssetEdit(identify({ registrationNumber: 'N12345' }))).toEqual({
      displayName: 'Falcon-2',
      identity: { registration: 'N12345' },
    });
  });

  it('never includes a category', () => {
    const edit = buildPostSimulationAssetEdit(identify({ registrationNumber: 'N12345' }));
    expect(edit).not.toHaveProperty('category');
  });
});

// --- Hand over: "Who takes this?" (docs/plans/done/OPS-UX-PLAN.md §2 A3) -------------------------------

function membership(partial: Partial<Membership> = {}): Membership {
  return { groupId: 'group-1', groupName: 'Alpha Squad', role: 'PILOT', ...partial };
}

function user(partial: Partial<UserSummary> = {}): UserSummary {
  return {
    userId: 'user-1',
    username: 'user1',
    displayName: 'User One',
    email: 'user1@example.com',
    enabled: true,
    memberships: [],
    ...partial,
  };
}

describe('creatorOwnershipGroup', () => {
  it('is undefined for a membership-less account', () => {
    expect(creatorOwnershipGroup([])).toBeUndefined();
  });

  it('picks the single membership when there is only one', () => {
    const m = membership({ role: 'MANAGER' });
    expect(creatorOwnershipGroup([m])).toBe(m);
  });

  it('picks the highest-role membership across several groups (mirrors the backend\'s own max-by-Role rule)', () => {
    const pilot = membership({ groupId: 'g-pilot', role: 'PILOT' });
    const manager = membership({ groupId: 'g-manager', role: 'MANAGER' });
    const admin = membership({ groupId: 'g-admin', role: 'ADMIN' });
    expect(creatorOwnershipGroup([pilot, manager])).toBe(manager);
    expect(creatorOwnershipGroup([manager, admin, pilot])).toBe(admin);
  });

  it('keeps the first-encountered membership on a tie', () => {
    const first = membership({ groupId: 'g-1', role: 'MANAGER' });
    const second = membership({ groupId: 'g-2', role: 'MANAGER' });
    expect(creatorOwnershipGroup([first, second])).toBe(first);
  });
});

describe('pilotsInGroup', () => {
  const pilotHere = user({ userId: 'p1', memberships: [{ groupId: 'g-1', role: 'PILOT' }] });
  const pilotElsewhere = user({ userId: 'p2', memberships: [{ groupId: 'g-2', role: 'PILOT' }] });
  const managerHere = user({ userId: 'm1', memberships: [{ groupId: 'g-1', role: 'MANAGER' }] });
  const disabledPilotHere = user({ userId: 'p3', enabled: false, memberships: [{ groupId: 'g-1', role: 'PILOT' }] });

  it('returns only enabled pilots whose membership matches the given group', () => {
    expect(pilotsInGroup([pilotHere, pilotElsewhere, managerHere, disabledPilotHere], 'g-1')).toEqual([pilotHere]);
  });

  it('is empty when the group is unresolved — never falls back to every pilot app-wide', () => {
    expect(pilotsInGroup([pilotHere, pilotElsewhere], undefined)).toEqual([]);
  });

  it('is empty when nobody in the group is a pilot', () => {
    expect(pilotsInGroup([managerHere], 'g-1')).toEqual([]);
  });
});

describe('defaultPilotSelection', () => {
  it('preselects the creator when their own ownership-group role is PILOT (the solo self-registration case)', () => {
    const group = membership({ groupId: 'g-1', role: 'PILOT' });
    expect(defaultPilotSelection('user-1', group)).toEqual(['user-1']);
  });

  it('selects nobody when the creator\'s ownership-group role is MANAGER/ADMIN (the common case)', () => {
    expect(defaultPilotSelection('user-1', membership({ role: 'MANAGER' }))).toEqual([]);
    expect(defaultPilotSelection('user-1', membership({ role: 'ADMIN' }))).toEqual([]);
  });

  it('selects nobody when no group could be resolved at all', () => {
    expect(defaultPilotSelection('user-1', undefined)).toEqual([]);
  });
});
