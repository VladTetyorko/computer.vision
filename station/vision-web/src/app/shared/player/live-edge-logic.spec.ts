import { describe, expect, it } from 'vitest';
import {
  SNAP_TO_LIVE_THRESHOLD_SECONDS,
  shouldSnapToLive,
  transportLatencyLabel,
} from './live-edge-logic';

describe('shouldSnapToLive', () => {
  it('"recovered" always snaps, regardless of the measured latency', () => {
    expect(shouldSnapToLive('recovered', null)).toBe(true);
    expect(shouldSnapToLive('recovered', 0)).toBe(true);
    expect(shouldSnapToLive('recovered', 0.1)).toBe(true);
    expect(shouldSnapToLive('recovered', 100)).toBe(true);
  });

  it('"visibilityRestored" never snaps on an unmeasured (null) latency', () => {
    expect(shouldSnapToLive('visibilityRestored', null)).toBe(false);
  });

  it('"visibilityRestored" snaps once behindLiveSeconds clears the threshold', () => {
    expect(shouldSnapToLive('visibilityRestored', SNAP_TO_LIVE_THRESHOLD_SECONDS)).toBe(false);
    expect(shouldSnapToLive('visibilityRestored', SNAP_TO_LIVE_THRESHOLD_SECONDS + 0.1)).toBe(true);
  });

  it('"visibilityRestored" does not snap for a small, barely-behind drift', () => {
    expect(shouldSnapToLive('visibilityRestored', 0.5)).toBe(false);
  });

  it('accepts a custom threshold', () => {
    expect(shouldSnapToLive('visibilityRestored', 10, 20)).toBe(false);
    expect(shouldSnapToLive('visibilityRestored', 21, 20)).toBe(true);
  });

  it('"firstAttach" always snaps, regardless of the measured latency — fix/stream-start-latency', () => {
    expect(shouldSnapToLive('firstAttach', null)).toBe(true);
    expect(shouldSnapToLive('firstAttach', 0)).toBe(true);
    expect(shouldSnapToLive('firstAttach', 0.1)).toBe(true);
    expect(shouldSnapToLive('firstAttach', 100)).toBe(true);
  });
});

describe('transportLatencyLabel', () => {
  it('shows "—" for webrtc while the getStats()-derived latency estimate is not yet known', () => {
    expect(transportLatencyLabel('webrtc', null, null)).toBe('WebRTC —');
    expect(transportLatencyLabel('webrtc', 6.7, null)).toBe('WebRTC —'); // hlsBehindLiveSeconds is irrelevant here
  });

  it('quantifies webrtc to one decimal place once the estimate is known', () => {
    expect(transportLatencyLabel('webrtc', null, 0.4)).toBe('WebRTC 0.4s');
    expect(transportLatencyLabel('webrtc', null, 0)).toBe('WebRTC 0.0s');
    expect(transportLatencyLabel('webrtc', null, 1.25)).toBe('WebRTC 1.3s');
  });

  it('shows "—" for hls while the live-edge distance is not yet measured', () => {
    expect(transportLatencyLabel('hls', null, null)).toBe('HLS —');
    expect(transportLatencyLabel('hls', null, 0.4)).toBe('HLS —'); // webrtcLatencySeconds is irrelevant here
  });

  it('quantifies hls to a rounded whole second with an approximation tilde once measured', () => {
    expect(transportLatencyLabel('hls', 6.4, null)).toBe('HLS ~6s');
    expect(transportLatencyLabel('hls', 6.5, null)).toBe('HLS ~7s');
    expect(transportLatencyLabel('hls', 0.2, null)).toBe('HLS ~0s');
  });
});
