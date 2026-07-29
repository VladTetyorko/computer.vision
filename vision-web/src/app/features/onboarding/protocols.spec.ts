import { describe, expect, it } from 'vitest';
import {
  CUSTOM_PROTOCOL_OPTION,
  REGISTERABLE_PROTOCOLS,
  isKnownProtocol,
  placeholderForProtocol,
  protocolSelectionFor,
} from './protocols';

describe('REGISTERABLE_PROTOCOLS', () => {
  it('lists exactly the eight protocols the app actually consumes, grouped network-stream / local / special (docs/DRONE-INFRA-PLAN.md I-h wave B)', () => {
    expect(REGISTERABLE_PROTOCOLS.map((option) => option.value)).toEqual([
      'rtsp',
      'mjpeg',
      'srt',
      'udp',
      'v4l2',
      'file',
      'sim',
      'mavlink',
    ]);
  });

  it('gives every option a non-empty hint and a realistic placeholder', () => {
    for (const option of REGISTERABLE_PROTOCOLS) {
      expect(option.hint.trim().length).toBeGreaterThan(0);
      expect(option.placeholder.trim().length).toBeGreaterThan(0);
    }
  });
});

describe('isKnownProtocol', () => {
  it('is true for every registerable protocol', () => {
    for (const option of REGISTERABLE_PROTOCOLS) {
      expect(isKnownProtocol(option.value)).toBe(true);
    }
  });

  it('is false for an unlisted protocol', () => {
    expect(isKnownProtocol('onvif')).toBe(false);
  });

  it('is false for the Custom… sentinel itself', () => {
    expect(isKnownProtocol(CUSTOM_PROTOCOL_OPTION)).toBe(false);
  });
});

describe('placeholderForProtocol', () => {
  it('returns the realistic example for a known protocol', () => {
    expect(placeholderForProtocol('rtsp')).toBe('rtsp://192.168.1.50:554/stream');
    expect(placeholderForProtocol('file')).toBe('file:///srv/videos/flight.mp4');
    expect(placeholderForProtocol('mjpeg')).toBe('http://192.168.1.60:8080/video');
    expect(placeholderForProtocol('v4l2')).toBe('file:///dev/video0');
    expect(placeholderForProtocol('sim')).toBe('sim://demo');
    expect(placeholderForProtocol('mavlink')).toBe('udp://0.0.0.0:14550');
    expect(placeholderForProtocol('srt')).toBe('srt://0.0.0.0:8890');
    expect(placeholderForProtocol('udp')).toBe('udp://0.0.0.0:5600');
  });

  it('falls back to a generic placeholder for an unrecognized protocol', () => {
    expect(placeholderForProtocol('onvif')).toBe('protocol://host/path');
  });

  it('falls back to a generic placeholder for a blank protocol', () => {
    expect(placeholderForProtocol('')).toBe('protocol://host/path');
  });
});

describe('protocolSelectionFor', () => {
  it('resolves a known protocol straight to the select, with no custom text', () => {
    expect(protocolSelectionFor('rtsp')).toEqual({ select: 'rtsp', custom: '' });
  });

  it('resolves an unknown protocol to the Custom… sentinel, preserving the original value', () => {
    expect(protocolSelectionFor('onvif')).toEqual({ select: CUSTOM_PROTOCOL_OPTION, custom: 'onvif' });
  });

  it('resolves blank/undefined to no selection at all', () => {
    expect(protocolSelectionFor(undefined)).toEqual({ select: '', custom: '' });
    expect(protocolSelectionFor('   ')).toEqual({ select: '', custom: '' });
  });

  it('trims whitespace before resolving', () => {
    expect(protocolSelectionFor('  rtsp  ')).toEqual({ select: 'rtsp', custom: '' });
  });
});

describe('srt / udp (docs/DRONE-INFRA-PLAN.md I-h wave B — low-latency drone video ingest)', () => {
  it('are known protocols', () => {
    expect(isKnownProtocol('srt')).toBe(true);
    expect(isKnownProtocol('udp')).toBe(true);
  });

  it('resolve straight to the select via protocolSelectionFor, like every other known protocol', () => {
    expect(protocolSelectionFor('srt')).toEqual({ select: 'srt', custom: '' });
    expect(protocolSelectionFor('udp')).toEqual({ select: 'udp', custom: '' });
  });

  it('srt\'s hint carries the listener-vs-caller guidance inline (mode=listener is the common drone case)', () => {
    const srt = REGISTERABLE_PROTOCOLS.find((option) => option.value === 'srt');
    expect(srt?.hint).toContain('mode=listener');
    expect(srt?.hint).toContain('caller');
  });

  it('udp\'s hint states this app listens rather than dials out', () => {
    const udp = REGISTERABLE_PROTOCOLS.find((option) => option.value === 'udp');
    expect(udp?.hint.toLowerCase()).toContain('listens');
  });

  it('are grouped with the other network-stream protocols, ahead of the local/special-purpose ones', () => {
    const values = REGISTERABLE_PROTOCOLS.map((option) => option.value);
    const networkStreamProtocols = ['rtsp', 'mjpeg', 'srt', 'udp'];
    const localAndSpecialProtocols = ['v4l2', 'file', 'sim', 'mavlink'];
    const lastNetworkIndex = Math.max(...networkStreamProtocols.map((p) => values.indexOf(p)));
    const firstOtherIndex = Math.min(...localAndSpecialProtocols.map((p) => values.indexOf(p)));
    expect(lastNetworkIndex).toBeLessThan(firstOtherIndex);
  });
});
