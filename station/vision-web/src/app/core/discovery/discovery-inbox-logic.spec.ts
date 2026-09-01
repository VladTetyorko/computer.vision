import { describe, expect, it } from 'vitest';
import type { DiscoveryCandidate, DiscoveryCandidateStatus, DiscoverySource } from '../api/models';
import {
  buildDeviceSpecFromCandidate,
  buildRegisterCommand,
  candidateActions,
  candidateAgeLabel,
  canSubmitRegisterDraft,
  defaultRegisterDraft,
  discoveryMethodLabel,
  dismissedCandidateCount,
  newCandidateCount,
  registeredCandidateCount,
  sourceUnreachableWarnings,
  visibleCandidates,
  type DiscoveryInboxVisibility,
} from './discovery-inbox-logic';

function candidate(partial: Partial<DiscoveryCandidate> = {}): DiscoveryCandidate {
  return {
    id: 'c-1',
    method: 'mavlink',
    name: 'Vehicle 7',
    address: 'udp:14550',
    details: {},
    firstSeen: '2026-08-31T00:00:00Z',
    lastSeen: '2026-08-31T00:00:00Z',
    status: 'NEW',
    ...partial,
  };
}

const HIDE_ALL: DiscoveryInboxVisibility = { showRegistered: false, showDismissed: false };
const SHOW_ALL: DiscoveryInboxVisibility = { showRegistered: true, showDismissed: true };

describe('discoveryMethodLabel', () => {
  it('maps every known method to its chip label', () => {
    expect(discoveryMethodLabel('mavlink')).toBe('MAVLink');
    expect(discoveryMethodLabel('onvif')).toBe('ONVIF');
    expect(discoveryMethodLabel('mdns')).toBe('mDNS');
    expect(discoveryMethodLabel('v4l2')).toBe('V4L2');
    expect(discoveryMethodLabel('mediamtx')).toBe('Mediamtx push');
  });

  it('is case-insensitive on the raw wire value', () => {
    expect(discoveryMethodLabel('MAVLINK')).toBe('MAVLink');
  });

  it('falls back to a capitalized raw string for an unknown method rather than throwing', () => {
    expect(discoveryMethodLabel('future-method')).toBe('Future-method');
    expect(discoveryMethodLabel('')).toBe('');
  });
});

describe('sourceUnreachableWarnings', () => {
  function source(partial: Partial<DiscoverySource> = {}): DiscoverySource {
    return { id: 'mediamtx', status: 'OK', ...partial };
  }

  it('is empty when every source is OK — the plan\'s own "keep the current empty state" rule', () => {
    expect(sourceUnreachableWarnings([source({ id: 'mediamtx' }), source({ id: 'mdns' })])).toEqual([]);
  });

  it('is empty for no sources at all', () => {
    expect(sourceUnreachableWarnings([])).toEqual([]);
  });

  it('names an unreachable source using its own discoveryMethodLabel', () => {
    const warnings = sourceUnreachableWarnings([source({ id: 'mediamtx', status: 'UNREACHABLE' })]);
    expect(warnings).toEqual(['Mediamtx push unreachable — found devices may be incomplete.']);
  });

  it('produces one message per unreachable source, ignoring OK ones', () => {
    const warnings = sourceUnreachableWarnings([
      source({ id: 'mediamtx', status: 'UNREACHABLE' }),
      source({ id: 'mdns', status: 'OK' }),
      source({ id: 'onvif', status: 'UNREACHABLE' }),
    ]);
    expect(warnings).toEqual([
      'Mediamtx push unreachable — found devices may be incomplete.',
      'ONVIF unreachable — found devices may be incomplete.',
    ]);
  });
});

describe('candidateAgeLabel', () => {
  const nowMs = Date.parse('2026-08-31T00:00:12Z');

  it('renders "last heard <age> ago" using the shared humanAge vocabulary', () => {
    expect(candidateAgeLabel('2026-08-31T00:00:00Z', nowMs)).toBe('last heard 12s ago');
  });

  it('never goes negative for a lastSeen that is (clock-skew) after now', () => {
    expect(candidateAgeLabel('2026-08-31T00:00:20Z', nowMs)).toBe('last heard 0s ago');
  });

  it('degrades to 0s for an unparsable timestamp rather than throwing/NaN', () => {
    expect(candidateAgeLabel('not-a-date', nowMs)).toBe('last heard 0s ago');
  });
});

describe('visibleCandidates', () => {
  const rows: readonly DiscoveryCandidate[] = [
    candidate({ id: 'new-old', status: 'NEW', lastSeen: '2026-08-31T00:00:00Z' }),
    candidate({ id: 'new-fresh', status: 'NEW', lastSeen: '2026-08-31T00:05:00Z' }),
    candidate({ id: 'registered', status: 'REGISTERED', lastSeen: '2026-08-31T00:03:00Z' }),
    candidate({ id: 'dismissed', status: 'DISMISSED', lastSeen: '2026-08-31T00:04:00Z' }),
  ];

  it('always shows NEW candidates, freshest first', () => {
    const visible = visibleCandidates(rows, HIDE_ALL);
    expect(visible.map((c) => c.id)).toEqual(['new-fresh', 'new-old']);
  });

  it('hides REGISTERED/DISMISSED until their own toggle is on', () => {
    expect(visibleCandidates(rows, HIDE_ALL).some((c) => c.status !== 'NEW')).toBe(false);
  });

  it('reveals REGISTERED/DISMISSED once toggled on, NEW still ranked first', () => {
    const visible = visibleCandidates(rows, SHOW_ALL);
    expect(visible.map((c) => c.id)).toEqual(['new-fresh', 'new-old', 'registered', 'dismissed']);
  });

  it('shows only REGISTERED when just that toggle is on', () => {
    const visible = visibleCandidates(rows, { showRegistered: true, showDismissed: false });
    expect(visible.map((c) => c.id)).toEqual(['new-fresh', 'new-old', 'registered']);
  });

  it('does not mutate the input array', () => {
    const copy = [...rows];
    visibleCandidates(rows, SHOW_ALL);
    expect(rows).toEqual(copy);
  });
});

describe('candidate counts', () => {
  const rows: readonly DiscoveryCandidate[] = [
    candidate({ id: '1', status: 'NEW' }),
    candidate({ id: '2', status: 'NEW' }),
    candidate({ id: '3', status: 'REGISTERED' }),
    candidate({ id: '4', status: 'DISMISSED' }),
  ];

  it('counts each status independently', () => {
    expect(newCandidateCount(rows)).toBe(2);
    expect(registeredCandidateCount(rows)).toBe(1);
    expect(dismissedCandidateCount(rows)).toBe(1);
  });

  it('is 0 for an empty inbox', () => {
    expect(newCandidateCount([])).toBe(0);
  });
});

describe('candidateActions', () => {
  it('offers every action for a NEW candidate with a suggested stream', () => {
    const actions = candidateActions(
      candidate({ status: 'NEW', suggestedStreamProtocol: 'rtsp', suggestedStreamUri: 'rtsp://cam/1' }),
    );
    expect(actions).toEqual({ canAdd: true, canAttach: true, canDismiss: true });
  });

  it('offers Add/Dismiss but not Attach for a NEW candidate with no suggested stream', () => {
    const actions = candidateActions(candidate({ status: 'NEW' }));
    expect(actions).toEqual({ canAdd: true, canAttach: false, canDismiss: true });
  });

  it.each<DiscoveryCandidateStatus>(['REGISTERED', 'DISMISSED'])('offers nothing once %s', (status) => {
    const actions = candidateActions(
      candidate({ status, suggestedStreamProtocol: 'rtsp', suggestedStreamUri: 'rtsp://cam/1' }),
    );
    expect(actions).toEqual({ canAdd: false, canAttach: false, canDismiss: false });
  });
});

describe('register draft', () => {
  it('prefills displayName from the candidate name and category from suggestedCategory', () => {
    expect(defaultRegisterDraft(candidate({ name: 'Rover 1', suggestedCategory: 'rover' }))).toEqual({
      displayName: 'Rover 1',
      category: 'rover',
    });
  });

  it('leaves category blank when the candidate suggests none', () => {
    expect(defaultRegisterDraft(candidate({ suggestedCategory: undefined }))).toEqual({
      displayName: 'Vehicle 7',
      category: '',
    });
  });

  it('requires both a non-blank name and category to submit', () => {
    expect(canSubmitRegisterDraft({ displayName: 'Rover 1', category: 'rover' })).toBe(true);
    expect(canSubmitRegisterDraft({ displayName: '  ', category: 'rover' })).toBe(false);
    expect(canSubmitRegisterDraft({ displayName: 'Rover 1', category: '' })).toBe(false);
  });

  it('trims the built command', () => {
    expect(buildRegisterCommand({ displayName: '  Rover 1  ', category: ' rover ' })).toEqual({
      displayName: 'Rover 1',
      category: 'rover',
    });
  });
});

describe('buildDeviceSpecFromCandidate', () => {
  it('carries protocol/uri/options verbatim and omits capabilities', () => {
    const spec = buildDeviceSpecFromCandidate(
      candidate({
        name: 'Rover cam',
        suggestedStreamProtocol: 'rtsp',
        suggestedStreamUri: 'rtsp://192.168.1.5:8554/ingest/rover-1',
        suggestedStreamOptions: { sysid: '7' },
      }),
    );
    expect(spec).toEqual({
      name: 'Rover cam',
      protocol: 'rtsp',
      uri: 'rtsp://192.168.1.5:8554/ingest/rover-1',
      options: { sysid: '7' },
    });
    expect(spec).not.toHaveProperty('capabilities');
  });

  it('defaults missing options to {} rather than omitting the field', () => {
    const spec = buildDeviceSpecFromCandidate(
      candidate({ suggestedStreamProtocol: 'rtsp', suggestedStreamUri: 'rtsp://cam/1', suggestedStreamOptions: undefined }),
    );
    expect(spec?.options).toEqual({});
  });

  it('returns undefined when the candidate has no suggested stream', () => {
    expect(buildDeviceSpecFromCandidate(candidate())).toBeUndefined();
  });
});
