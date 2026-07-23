import { describe, expect, it } from 'vitest';
import {
  describeSource,
  formatDuration,
  formatLatency,
  sessionDurationSeconds,
  transportLabel,
} from './stream-info-logic';

describe('describeSource', () => {
  it('describes an rtsp device by host:port, never the raw credentialed URI', () => {
    const result = describeSource({
      protocol: 'rtsp',
      uri: 'rtsp://user:secret@192.168.1.50:554/stream',
      options: {},
    });
    expect(result).toEqual({ protocolLabel: 'RTSP', description: '192.168.1.50:554' });
  });

  it('describes an mjpeg device by host', () => {
    const result = describeSource({ protocol: 'mjpeg', uri: 'http://192.168.0.107:8080/video', options: {} });
    expect(result).toEqual({ protocolLabel: 'MJPEG', description: '192.168.0.107:8080' });
  });

  it('describes a file device by its file name', () => {
    const result = describeSource({ protocol: 'file', uri: '/srv/videos/flight.mp4', options: {} });
    expect(result).toEqual({ protocolLabel: 'File', description: 'flight.mp4' });
  });

  it('appends "(looped)" only when the loop option is the string "true"', () => {
    const looping = describeSource({
      protocol: 'file',
      uri: '/srv/videos/flight.mp4',
      options: { loop: 'true' },
    });
    expect(looping.description).toBe('flight.mp4 (looped)');

    const notLooping = describeSource({
      protocol: 'file',
      uri: '/srv/videos/flight.mp4',
      options: { loop: 'false' },
    });
    expect(notLooping.description).toBe('flight.mp4');
  });

  it('describes a sim device as a pattern generator, not its sim:// uri', () => {
    const result = describeSource({ protocol: 'sim', uri: 'sim://demo', options: {} });
    expect(result).toEqual({ protocolLabel: 'Simulated', description: 'Pattern generator, no camera' });
  });

  it('falls back to the uppercased protocol + host for an unrecognized protocol', () => {
    const result = describeSource({ protocol: 'onvif', uri: 'http://10.0.0.5:8899/onvif', options: {} });
    expect(result).toEqual({ protocolLabel: 'ONVIF', description: '10.0.0.5:8899' });
  });

  it('omits the port when it is the scheme default (URL omits it too)', () => {
    const result = describeSource({ protocol: 'onvif', uri: 'http://10.0.0.5:80/onvif', options: {} });
    expect(result).toEqual({ protocolLabel: 'ONVIF', description: '10.0.0.5' });
  });

  it('falls back to the raw uri when it does not parse as a URL', () => {
    const result = describeSource({ protocol: 'weird', uri: 'not-a-url', options: {} });
    expect(result).toEqual({ protocolLabel: 'WEIRD', description: 'not-a-url' });
  });
});

describe('sessionDurationSeconds', () => {
  it('computes elapsed seconds since startedAt', () => {
    const startedAt = '2026-07-22T00:00:00.000Z';
    const now = Date.parse(startedAt) + 65_000;
    expect(sessionDurationSeconds(startedAt, now)).toBe(65);
  });

  it('never goes negative for a clock-skewed future startedAt', () => {
    const startedAt = '2026-07-22T00:00:10.000Z';
    const now = Date.parse(startedAt) - 5_000;
    expect(sessionDurationSeconds(startedAt, now)).toBe(0);
  });
});

describe('formatDuration', () => {
  it('renders seconds only under a minute', () => {
    expect(formatDuration(42)).toBe('42s');
  });

  it('renders minutes and seconds under an hour', () => {
    expect(formatDuration(65)).toBe('1m 05s');
  });

  it('renders hours and minutes at or past an hour', () => {
    expect(formatDuration(3_723)).toBe('1h 02m');
  });

  it('floors fractional seconds and clamps negatives to zero', () => {
    expect(formatDuration(5.9)).toBe('5s');
    expect(formatDuration(-3)).toBe('0s');
  });
});

describe('formatLatency', () => {
  it('reports "measuring…" while unknown', () => {
    expect(formatLatency(null)).toBe('measuring…');
  });

  it('reports the measured seconds behind live to one decimal', () => {
    expect(formatLatency(2.34)).toBe('~2.3s behind live');
  });
});

describe('transportLabel', () => {
  it('names WebRTC (WHEP) for the webrtc transport', () => {
    expect(transportLabel('webrtc')).toBe('WebRTC (WHEP)');
  });

  it('names HLS for the hls transport', () => {
    expect(transportLabel('hls')).toBe('HLS');
  });
});
