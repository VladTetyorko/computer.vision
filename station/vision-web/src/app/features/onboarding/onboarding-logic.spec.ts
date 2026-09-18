import { describe, expect, it } from 'vitest';
import type { DiscoveryCandidate, Membership, NetworkAddress, ProbeDeviceResult, UserSummary, VehicleProfile } from '../../core/api/models';
import { emptyFitOutRows, type FitOutRowDraft, type FitOutRows } from '../../core/onboarding/fit-out-logic';
import {
  buildCreateAssetRequest,
  buildIdentityRequest,
  buildPostSimulationAssetEdit,
  buildProbeRequest,
  buildVerifyRequest,
  canAdvanceFromIdentify,
  composePushAddress,
  creatorOwnershipGroup,
  defaultPilotSelection,
  isEquipmentPath,
  isTelemetryOnlyProtocol,
  nextStep,
  pilotsInGroup,
  prefillFromDiscoveryCandidate,
  prevStep,
  relativeAge,
  roleForDiscoveryMethod,
  senseTerminalProof,
  simulateNeedsVideoPath,
  sightTerminalProof,
  visibleSteps,
  type IdentifyDraft,
  type StepContext,
} from './onboarding-logic';

function candidate(partial: Partial<DiscoveryCandidate> = {}): DiscoveryCandidate {
  return {
    id: 'cand-1',
    method: 'mediamtx',
    name: 'Cam 1',
    address: '192.168.1.20',
    details: {},
    firstSeen: '2026-09-01T00:00:00Z',
    lastSeen: '2026-09-01T00:00:05Z',
    status: 'NEW',
    ...partial,
  };
}

function address(partial: Partial<NetworkAddress> = {}): NetworkAddress {
  return { address: '192.168.1.5', interfaceName: 'eth0', kind: 'LAN', ...partial };
}

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
  return { equipment: false, needsProve: true, ...partial };
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

describe('isEquipmentPath', () => {
  it('is true only when both rows are none', () => {
    expect(isEquipmentPath(emptyFitOutRows())).toBe(true);
  });

  it('is false once either row is filled', () => {
    expect(isEquipmentPath(rows({ value: 'find' }))).toBe(false);
    expect(isEquipmentPath(rows({}, { value: 'simulate' }))).toBe(false);
  });
});

describe('visibleSteps', () => {
  it('collapses to Source · Identify · Hand over on the equipment path', () => {
    expect(visibleSteps(emptyFitOutRows(), false)).toEqual(['source', 'identify', 'handover']);
  });

  it('includes Prove when a row needs it', () => {
    expect(visibleSteps(rows({ value: 'find' }), true)).toEqual(['source', 'prove', 'identify', 'attach', 'handover']);
  });

  it('skips Prove when nothing needs it, but still shows Attach — a plain connected vehicle', () => {
    expect(visibleSteps(rows({ value: 'simulate' }), false)).toEqual(['source', 'identify', 'attach', 'handover']);
  });

  it('never includes sysid on any branch', () => {
    expect(visibleSteps(emptyFitOutRows(), false)).not.toContain('sysid');
    expect(visibleSteps(rows({ value: 'find' }), true)).not.toContain('sysid');
  });
});

describe('nextStep', () => {
  it('goes source -> prove when the context needs it', () => {
    expect(nextStep('source', ctx({ needsProve: true }))).toBe('prove');
  });

  it('skips prove entirely when nothing needs it — source -> identify', () => {
    expect(nextStep('source', ctx({ needsProve: false }))).toBe('identify');
  });

  it('goes source -> identify directly on the equipment path, regardless of needsProve', () => {
    expect(nextStep('source', ctx({ equipment: true, needsProve: true }))).toBe('identify');
  });

  it('goes prove -> identify', () => {
    expect(nextStep('prove', ctx())).toBe('identify');
  });

  it('goes identify -> attach for a connected vehicle', () => {
    expect(nextStep('identify', ctx({ equipment: false }))).toBe('attach');
  });

  it('goes identify -> handover directly for equipment (the Receive short-circuit)', () => {
    expect(nextStep('identify', ctx({ equipment: true }))).toBe('handover');
  });

  it('is a no-op past attach', () => {
    expect(nextStep('attach', ctx())).toBe('attach');
  });

  it('is a no-op past handover — the wizard reaches it only via a successful create/attach, never via next()', () => {
    expect(nextStep('handover', ctx())).toBe('handover');
  });

  it('is a no-op past sysid — the wizard reaches it only via OnboardingStore#finishCreate, never via next()', () => {
    expect(nextStep('sysid', ctx())).toBe('sysid');
  });

  it('is a no-op past confirm — the wizard reaches it only via OnboardingStore#chooseFoundCandidate, and leaves it via #continueFromConfirm, never via next()', () => {
    expect(nextStep('confirm', ctx())).toBe('confirm');
  });
});

describe('prevStep', () => {
  it('is a no-op before source', () => {
    expect(prevStep('source', ctx())).toBe('source');
  });

  it('goes identify -> source when nothing needed proving', () => {
    expect(prevStep('identify', ctx({ needsProve: false }))).toBe('source');
  });

  it('goes identify -> prove when the fit-out draft needed proving', () => {
    expect(prevStep('identify', ctx({ equipment: false, needsProve: true }))).toBe('prove');
  });

  it('goes identify -> source for equipment, regardless of needsProve', () => {
    expect(prevStep('identify', ctx({ equipment: true, needsProve: true }))).toBe('source');
  });

  it('goes prove -> source', () => {
    expect(prevStep('prove', ctx())).toBe('source');
  });

  it('goes attach -> identify', () => {
    expect(prevStep('attach', ctx())).toBe('identify');
  });

  it('goes handover -> attach for a connected vehicle', () => {
    expect(prevStep('handover', ctx({ equipment: false }))).toBe('attach');
  });

  it('goes handover -> identify for equipment — there is no attach step to return to', () => {
    expect(prevStep('handover', ctx({ equipment: true }))).toBe('identify');
  });

  it('goes sysid -> attach — its own immediate predecessor, never further (onboarding never renders a Back button here regardless)', () => {
    expect(prevStep('sysid', ctx())).toBe('attach');
  });

  it('is a no-op past confirm — OnboardingStore#backFromConfirm sets \'source\' directly, never via prev()', () => {
    expect(prevStep('confirm', ctx())).toBe('confirm');
  });

  it('round-trips with nextStep for a connected vehicle needing prove', () => {
    const context = ctx({ equipment: false, needsProve: true });
    let step = nextStep('source', context);
    step = nextStep(step, context);
    step = nextStep(step, context);
    expect(step).toBe('attach');
    let back = prevStep(step, context);
    back = prevStep(back, context);
    back = prevStep(back, context);
    expect(back).toBe('source');
  });

  it('round-trips with nextStep for equipment', () => {
    const context = ctx({ equipment: true });
    const step = nextStep('source', context);
    expect(step).toBe('identify');
    expect(prevStep(step, context)).toBe('source');
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

// --- Terminal Ready screen: the two-half proof (D9) -----------------------------------------------

function probeResult(partial: Partial<ProbeDeviceResult> = {}): ProbeDeviceResult {
  return { ok: true, telemetryDetected: false, ...partial };
}

function vehicleProfile(partial: Partial<VehicleProfile> = {}): VehicleProfile {
  return {
    linkKey: 'udp:0.0.0.0:14550',
    observedAt: '2026-09-05T00:00:00Z',
    sysid: null,
    firmware: null,
    firmwareVersion: null,
    vehicleKind: null,
    capabilityBitmask: null,
    capabilityFlags: [],
    messages: [],
    parameters: [],
    linkBytesPerSecond: null,
    complete: true,
    incompleteReason: null,
    ...partial,
  };
}

describe('sightTerminalProof', () => {
  it('renders — for a none row', () => {
    expect(sightTerminalProof('none', undefined)).toEqual({ proven: false, label: '—' });
  });

  it('renders honestly unverified for a simulate row — never a green tick it hasn\'t earned', () => {
    expect(sightTerminalProof('simulate', undefined)).toEqual({ proven: false, label: 'simulated, not yet verified' });
  });

  it('renders not yet verified for a find row with no probe result', () => {
    expect(sightTerminalProof('find', undefined)).toEqual({ proven: false, label: 'not yet verified' });
    expect(sightTerminalProof('find', null)).toEqual({ proven: false, label: 'not yet verified' });
  });

  it('renders not yet verified when the probe itself failed', () => {
    expect(sightTerminalProof('find', probeResult({ ok: false }))).toEqual({ proven: false, label: 'not yet verified' });
  });

  it('renders the frame facts once proven', () => {
    expect(sightTerminalProof('find', probeResult({ widthPx: 1280, heightPx: 720, codec: 'H.264' }))).toEqual({
      proven: true,
      label: 'first frame 1280×720 H.264',
    });
  });

  it('degrades to a bare "first frame received" when dims/codec are absent', () => {
    expect(sightTerminalProof('find', probeResult({}))).toEqual({ proven: true, label: 'first frame received' });
  });
});

describe('senseTerminalProof', () => {
  it('renders — for a none row', () => {
    expect(senseTerminalProof('none', undefined)).toEqual({ proven: false, label: '—' });
  });

  it('renders honestly unverified for a simulate row', () => {
    expect(senseTerminalProof('simulate', undefined)).toEqual({ proven: false, label: 'simulated, not yet verified' });
  });

  it('renders not yet verified for a find row with no verify result', () => {
    expect(senseTerminalProof('find', undefined)).toEqual({ proven: false, label: 'not yet verified' });
    expect(senseTerminalProof('find', null)).toEqual({ proven: false, label: 'not yet verified' });
  });

  it('renders heartbeat + sysid + firmware/vehicleKind once proven', () => {
    expect(
      senseTerminalProof('find', vehicleProfile({ sysid: 7, firmware: 'ArduPilot', vehicleKind: 'rover' })),
    ).toEqual({ proven: true, label: 'heartbeat, sysid 7 — ArduPilot rover' });
  });

  it('degrades to a bare "heartbeat" when sysid/firmware/vehicleKind are all absent', () => {
    expect(senseTerminalProof('find', vehicleProfile({}))).toEqual({ proven: true, label: 'heartbeat' });
  });

  it('omits the sysid clause but keeps the firmware trailer when only sysid is absent', () => {
    expect(senseTerminalProof('find', vehicleProfile({ firmware: 'INAV' }))).toEqual({
      proven: true,
      label: 'heartbeat — INAV',
    });
  });
});

describe('roleForDiscoveryMethod', () => {
  it('maps mavlink to sense, case-insensitively', () => {
    expect(roleForDiscoveryMethod('mavlink')).toBe('sense');
    expect(roleForDiscoveryMethod('MAVLink')).toBe('sense');
  });

  it('maps every other method to sight', () => {
    expect(roleForDiscoveryMethod('mediamtx')).toBe('sight');
    expect(roleForDiscoveryMethod('onvif')).toBe('sight');
    expect(roleForDiscoveryMethod('mdns')).toBe('sight');
    expect(roleForDiscoveryMethod('v4l2')).toBe('sight');
  });
});

describe('prefillFromDiscoveryCandidate', () => {
  it('is undefined when the candidate has no suggested stream', () => {
    expect(prefillFromDiscoveryCandidate(candidate())).toBeUndefined();
  });

  it('resolves a sight row from a non-mavlink candidate, findMethod discover', () => {
    expect(
      prefillFromDiscoveryCandidate(
        candidate({ suggestedStreamProtocol: 'rtsp', suggestedStreamUri: 'rtsp://192.168.1.20/stream', suggestedCategory: 'drone' }),
      ),
    ).toEqual({
      role: 'sight',
      findMethod: 'discover',
      protocol: 'rtsp',
      uri: 'rtsp://192.168.1.20/stream',
      options: undefined,
      displayName: 'Cam 1',
      category: 'drone',
    });
  });

  it('resolves a sense row from a mavlink candidate, findMethod listen', () => {
    expect(
      prefillFromDiscoveryCandidate(
        candidate({ method: 'mavlink', name: 'Rover-9', suggestedStreamProtocol: 'mavlink', suggestedStreamUri: 'udp://0.0.0.0:14550' }),
      ),
    ).toEqual({
      role: 'sense',
      findMethod: 'listen',
      protocol: 'mavlink',
      uri: 'udp://0.0.0.0:14550',
      options: undefined,
      displayName: 'Rover-9',
      category: undefined,
    });
  });

  it('carries suggestedStreamOptions through verbatim', () => {
    const options = { sysid: '7' };
    expect(
      prefillFromDiscoveryCandidate(
        candidate({ suggestedStreamProtocol: 'rtsp', suggestedStreamUri: 'rtsp://x', suggestedStreamOptions: options }),
      )?.options,
    ).toBe(options);
  });
});

describe('composePushAddress', () => {
  it('is undefined when either optional network field is absent', () => {
    expect(composePushAddress([address()], undefined, 'ingest/')).toBeUndefined();
    expect(composePushAddress([address()], 8554, undefined)).toBeUndefined();
  });

  it('is undefined when no address is known at all', () => {
    expect(composePushAddress([], 8554, 'ingest/')).toBeUndefined();
  });

  it('prefers a LAN address over a non-LAN one', () => {
    expect(composePushAddress([address({ address: '10.0.0.9', kind: 'VIRTUAL' }), address({ address: '192.168.1.5' })], 8554, 'ingest/')).toEqual({
      base: 'rtsp://192.168.1.5:8554/ingest/',
      placeholder: 'your-camera-name',
    });
  });

  it('falls back to the first address when none is LAN', () => {
    expect(composePushAddress([address({ address: '10.0.0.9', kind: 'VIRTUAL' })], 8554, 'ingest/')).toEqual({
      base: 'rtsp://10.0.0.9:8554/ingest/',
      placeholder: 'your-camera-name',
    });
  });
});

describe('relativeAge', () => {
  const now = Date.parse('2026-09-01T00:01:00Z');

  it('is undefined for an absent or unparseable timestamp', () => {
    expect(relativeAge(undefined, now)).toBeUndefined();
    expect(relativeAge('not-a-date', now)).toBeUndefined();
  });

  it('renders a humanized "ago" suffix', () => {
    expect(relativeAge('2026-09-01T00:00:45Z', now)).toBe('15s ago');
  });
});
