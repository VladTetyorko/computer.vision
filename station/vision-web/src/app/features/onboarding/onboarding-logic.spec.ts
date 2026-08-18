import { describe, expect, it } from 'vitest';
import type { Membership, UserSummary } from '../../core/api/models';
import {
  buildCreateAssetRequest,
  buildPostSimulationAssetEdit,
  buildProbeRequest,
  buildVerifyRequest,
  canAdvanceFromConnect,
  canAdvanceFromProfile,
  canAdvanceFromTest,
  canAdvanceFromVerify,
  creatorOwnershipGroup,
  defaultPilotSelection,
  nextStep,
  pilotsInGroup,
  prevStep,
  simulateNeedsVideoPath,
  type ConnectDraft,
} from './onboarding-logic';

function connectDraft(partial: Partial<ConnectDraft> = {}): ConnectDraft {
  return {
    method: 'register',
    protocol: 'rtsp',
    uri: 'rtsp://192.168.1.50:554/stream',
    simMode: 'direct',
    simVideoPath: '',
    ...partial,
  };
}

describe('nextStep', () => {
  it('goes profile -> connect regardless of method', () => {
    expect(nextStep('profile', null)).toBe('connect');
    expect(nextStep('profile', 'simulate')).toBe('connect');
  });

  it('goes connect -> test for register/discover/listen/drone', () => {
    expect(nextStep('connect', 'register')).toBe('test');
    expect(nextStep('connect', 'discover')).toBe('test');
    expect(nextStep('connect', 'listen')).toBe('test');
    expect(nextStep('connect', 'drone')).toBe('test');
  });

  it('skips test entirely for simulate — connect -> create', () => {
    expect(nextStep('connect', 'simulate')).toBe('create');
  });

  it('goes test -> verify', () => {
    expect(nextStep('test', 'register')).toBe('verify');
  });

  it('goes verify -> create', () => {
    expect(nextStep('verify', 'register')).toBe('create');
  });

  it('is a no-op past create', () => {
    expect(nextStep('create', 'register')).toBe('create');
  });

  it('is a no-op past assign — the wizard reaches it only via a successful create, never via next()', () => {
    expect(nextStep('assign', 'register')).toBe('assign');
  });
});

describe('prevStep', () => {
  it('is a no-op before profile', () => {
    expect(prevStep('profile', null)).toBe('profile');
  });

  it('goes connect -> profile', () => {
    expect(prevStep('connect', 'register')).toBe('profile');
  });

  it('goes test -> connect', () => {
    expect(prevStep('test', 'register')).toBe('connect');
  });

  it('goes verify -> test', () => {
    expect(prevStep('verify', 'register')).toBe('test');
  });

  it('goes create -> verify for register/discover', () => {
    expect(prevStep('create', 'register')).toBe('verify');
    expect(prevStep('create', 'discover')).toBe('verify');
  });

  it('skips test entirely for simulate — create -> connect', () => {
    expect(prevStep('create', 'simulate')).toBe('connect');
  });

  it('goes assign -> create — its own immediate predecessor, never further (onboarding.html never renders a Back button here regardless)', () => {
    expect(prevStep('assign', 'register')).toBe('create');
    expect(prevStep('assign', 'simulate')).toBe('create');
  });

  it('round-trips with nextStep for every method', () => {
    for (const method of ['register', 'discover', 'simulate', 'listen', 'drone'] as const) {
      let step = nextStep('profile', method);
      step = nextStep(step, method);
      // simulate skips both test and verify (connect -> create directly); every other method
      // passes through both.
      if (method !== 'simulate') {
        step = nextStep(step, method);
        step = nextStep(step, method);
      }
      expect(step).toBe('create');
      let back = prevStep(step, method);
      if (method !== 'simulate') {
        back = prevStep(back, method);
        back = prevStep(back, method);
      }
      back = prevStep(back, method);
      expect(back).toBe('profile');
    }
  });
});

describe('canAdvanceFromProfile', () => {
  it('requires a non-blank name and category', () => {
    expect(canAdvanceFromProfile('Falcon-2', 'drone')).toBe(true);
    expect(canAdvanceFromProfile('', 'drone')).toBe(false);
    expect(canAdvanceFromProfile('Falcon-2', '')).toBe(false);
    expect(canAdvanceFromProfile('   ', '   ')).toBe(false);
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

describe('canAdvanceFromConnect', () => {
  it('requires protocol and uri for register', () => {
    expect(canAdvanceFromConnect(connectDraft({ method: 'register' }))).toBe(true);
    expect(canAdvanceFromConnect(connectDraft({ method: 'register', protocol: '' }))).toBe(false);
    expect(canAdvanceFromConnect(connectDraft({ method: 'register', uri: '   ' }))).toBe(false);
  });

  it('never advances directly from discover — a candidate must first flip the method to register', () => {
    expect(
      canAdvanceFromConnect(connectDraft({ method: 'discover', protocol: 'rtsp', uri: 'rtsp://x' })),
    ).toBe(false);
  });

  it('never advances directly from listen either (docs/plans/active/DRONE-INFRA-PLAN.md I-b) — same candidate-must-flip-to-register rule', () => {
    expect(
      canAdvanceFromConnect(connectDraft({ method: 'listen', protocol: 'mavlink', uri: 'udp://0.0.0.0:14550' })),
    ).toBe(false);
  });

  it('never advances directly from drone either (docs/plans/active/DRONE-INFRA-PLAN.md I-g) — it hands off to listen before anything can advance', () => {
    expect(
      canAdvanceFromConnect(connectDraft({ method: 'drone', protocol: 'mavlink', uri: 'udp://0.0.0.0:14550' })),
    ).toBe(false);
  });

  it('never advances with no method chosen yet', () => {
    expect(canAdvanceFromConnect(connectDraft({ method: null }))).toBe(false);
  });

  it('requires a video path for the file-based simulate modes', () => {
    expect(canAdvanceFromConnect(connectDraft({ method: 'simulate', simMode: 'direct', simVideoPath: '' }))).toBe(
      false,
    );
    expect(
      canAdvanceFromConnect(connectDraft({ method: 'simulate', simMode: 'direct', simVideoPath: '/a.mp4' })),
    ).toBe(true);
  });

  it('needs no video path for synthetic/testDrone simulate modes', () => {
    expect(
      canAdvanceFromConnect(connectDraft({ method: 'simulate', simMode: 'synthetic', simVideoPath: '' })),
    ).toBe(true);
    expect(
      canAdvanceFromConnect(connectDraft({ method: 'simulate', simMode: 'testDrone', simVideoPath: '' })),
    ).toBe(true);
  });
});

describe('canAdvanceFromTest', () => {
  it('always allows advancing for simulate — the step is skipped entirely', () => {
    expect(canAdvanceFromTest('simulate', undefined)).toBe(true);
    expect(canAdvanceFromTest('simulate', false)).toBe(true);
  });

  it('requires a successful probe for register/discover', () => {
    expect(canAdvanceFromTest('register', true)).toBe(true);
    expect(canAdvanceFromTest('register', false)).toBe(false);
    expect(canAdvanceFromTest('register', undefined)).toBe(false);
    expect(canAdvanceFromTest('discover', true)).toBe(true);
  });

  it('requires a successful probe for listen too — it always flips to register before reaching this step', () => {
    expect(canAdvanceFromTest('listen', true)).toBe(true);
    expect(canAdvanceFromTest('listen', undefined)).toBe(false);
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

describe('canAdvanceFromVerify', () => {
  it('is always true, for every method — a vehicle-link observation may never strand the wizard (OQ3 resolved advisory-only, and probing defaults off)', () => {
    expect(canAdvanceFromVerify('register')).toBe(true);
    expect(canAdvanceFromVerify('simulate')).toBe(true);
    expect(canAdvanceFromVerify(null)).toBe(true);
  });
});

describe('buildVerifyRequest', () => {
  it('builds the same trimmed {protocol, uri, options} shape as buildProbeRequest', () => {
    expect(buildVerifyRequest({ protocol: '  mavlink  ', uri: '  udp://0.0.0.0:14550  ' })).toEqual({
      protocol: 'mavlink',
      uri: 'udp://0.0.0.0:14550',
    });
    expect(
      buildVerifyRequest({ protocol: 'mavlink', uri: 'udp://0.0.0.0:14550', options: { sysid: '7' } }),
    ).toEqual({ protocol: 'mavlink', uri: 'udp://0.0.0.0:14550', options: { sysid: '7' } });
  });
});

describe('buildCreateAssetRequest', () => {
  const profile = { displayName: 'Falcon-2', registrationNumber: '', category: 'drone' };
  const connect = { protocol: 'rtsp', uri: 'rtsp://192.168.1.50:554/stream' };

  it('creates the asset with the device embedded, named after the asset', () => {
    expect(buildCreateAssetRequest(profile, connect)).toEqual({
      displayName: 'Falcon-2',
      category: 'drone',
      devices: [{ name: 'Falcon-2', protocol: 'rtsp', uri: 'rtsp://192.168.1.50:554/stream' }],
    });
  });

  it('includes registrationNumber in attributes when given', () => {
    const request = buildCreateAssetRequest({ ...profile, registrationNumber: 'N12345' }, connect);
    expect(request.attributes).toEqual({ registrationNumber: 'N12345' });
  });

  it('omits attributes entirely when registrationNumber is blank', () => {
    expect(buildCreateAssetRequest(profile, connect)).not.toHaveProperty('attributes');
  });

  it('includes device options when given', () => {
    const request = buildCreateAssetRequest(profile, { ...connect, options: { rtsp_transport: 'tcp' } });
    expect(request.devices?.[0]).toEqual({
      name: 'Falcon-2',
      protocol: 'rtsp',
      uri: 'rtsp://192.168.1.50:554/stream',
      options: { rtsp_transport: 'tcp' },
    });
  });

  it('trims displayName/category/protocol/uri', () => {
    const request = buildCreateAssetRequest(
      { displayName: '  Falcon-2  ', registrationNumber: '', category: '  drone  ' },
      { protocol: '  rtsp  ', uri: '  rtsp://x  ' },
    );
    expect(request.displayName).toBe('Falcon-2');
    expect(request.category).toBe('drone');
    expect(request.devices?.[0]).toMatchObject({ protocol: 'rtsp', uri: 'rtsp://x' });
  });
});

describe('buildPostSimulationAssetEdit', () => {
  it('omits displayName when blank', () => {
    expect(buildPostSimulationAssetEdit({ displayName: '   ', registrationNumber: '' })).toEqual({});
  });

  it('includes a trimmed displayName when given', () => {
    expect(buildPostSimulationAssetEdit({ displayName: '  Falcon-2  ', registrationNumber: '' })).toEqual({
      displayName: 'Falcon-2',
    });
  });

  it('includes registrationNumber in attributes when given', () => {
    expect(
      buildPostSimulationAssetEdit({ displayName: 'Falcon-2', registrationNumber: 'N12345' }),
    ).toEqual({ displayName: 'Falcon-2', attributes: { registrationNumber: 'N12345' } });
  });

  it('never includes a category', () => {
    const edit = buildPostSimulationAssetEdit({ displayName: 'Falcon-2', registrationNumber: 'N12345' });
    expect(edit).not.toHaveProperty('category');
  });
});

// --- Step 5: "Who flies this?" (docs/plans/active/OPS-UX-PLAN.md §2 A3) -------------------------------

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
