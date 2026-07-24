import { describe, expect, it } from 'vitest';
import {
  buildCreateAssetRequest,
  buildPostSimulationAssetEdit,
  buildProbeRequest,
  canAdvanceFromConnect,
  canAdvanceFromProfile,
  canAdvanceFromTest,
  nextStep,
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

  it('goes connect -> test for register/discover', () => {
    expect(nextStep('connect', 'register')).toBe('test');
    expect(nextStep('connect', 'discover')).toBe('test');
  });

  it('skips test entirely for simulate — connect -> create', () => {
    expect(nextStep('connect', 'simulate')).toBe('create');
  });

  it('goes test -> create', () => {
    expect(nextStep('test', 'register')).toBe('create');
  });

  it('is a no-op past create', () => {
    expect(nextStep('create', 'register')).toBe('create');
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

  it('goes create -> test for register/discover', () => {
    expect(prevStep('create', 'register')).toBe('test');
    expect(prevStep('create', 'discover')).toBe('test');
  });

  it('skips test entirely for simulate — create -> connect', () => {
    expect(prevStep('create', 'simulate')).toBe('connect');
  });

  it('round-trips with nextStep for every method', () => {
    for (const method of ['register', 'discover', 'simulate'] as const) {
      let step = nextStep('profile', method);
      step = nextStep(step, method);
      if (method !== 'simulate') {
        step = nextStep(step, method);
      }
      expect(step).toBe('create');
      let back = prevStep(step, method);
      if (method !== 'simulate') {
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
