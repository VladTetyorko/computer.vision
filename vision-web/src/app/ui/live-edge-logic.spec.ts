import { describe, expect, it } from 'vitest';
import {
  BEHIND_LIVE_HIDE_THRESHOLD_SECONDS,
  BEHIND_LIVE_SHOW_THRESHOLD_SECONDS,
  SNAP_TO_LIVE_THRESHOLD_SECONDS,
  behindLiveChipLabel,
  shouldShowBehindLive,
  shouldSnapToLive,
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
});

describe('shouldShowBehindLive — hysteresis', () => {
  it('never shows on an unmeasured (null) latency, whether or not currently showing', () => {
    expect(shouldShowBehindLive(null, false)).toBe(false);
    expect(shouldShowBehindLive(null, true)).toBe(false);
  });

  it('starts showing once behindLiveSeconds clears the show threshold', () => {
    expect(shouldShowBehindLive(BEHIND_LIVE_SHOW_THRESHOLD_SECONDS, false)).toBe(false);
    expect(shouldShowBehindLive(BEHIND_LIVE_SHOW_THRESHOLD_SECONDS + 0.1, false)).toBe(true);
  });

  it('stays hidden below the show threshold even as it approaches it', () => {
    expect(shouldShowBehindLive(BEHIND_LIVE_SHOW_THRESHOLD_SECONDS - 0.1, false)).toBe(false);
  });

  it('once showing, stays showing through the dead band between hide and show thresholds', () => {
    const midBand = (BEHIND_LIVE_HIDE_THRESHOLD_SECONDS + BEHIND_LIVE_SHOW_THRESHOLD_SECONDS) / 2;
    expect(shouldShowBehindLive(midBand, true)).toBe(true);
  });

  it('once showing, only hides once behindLiveSeconds drops below the (lower) hide threshold', () => {
    expect(shouldShowBehindLive(BEHIND_LIVE_HIDE_THRESHOLD_SECONDS, true)).toBe(false);
    expect(shouldShowBehindLive(BEHIND_LIVE_HIDE_THRESHOLD_SECONDS + 0.1, true)).toBe(true);
  });

  it('no flicker at a single value straddling neither threshold\'s exact line, across both states', () => {
    const between = (BEHIND_LIVE_HIDE_THRESHOLD_SECONDS + BEHIND_LIVE_SHOW_THRESHOLD_SECONDS) / 2;
    // Same measured value, opposite starting states — hysteresis means the two agree with
    // "whatever it already was", not with each other.
    expect(shouldShowBehindLive(between, false)).toBe(false);
    expect(shouldShowBehindLive(between, true)).toBe(true);
  });

  it('accepts custom thresholds', () => {
    expect(shouldShowBehindLive(5, false, 10, 1)).toBe(false);
    expect(shouldShowBehindLive(11, false, 10, 1)).toBe(true);
    expect(shouldShowBehindLive(2, true, 10, 1)).toBe(true);
    expect(shouldShowBehindLive(0.5, true, 10, 1)).toBe(false);
  });
});

describe('behindLiveChipLabel', () => {
  it('webrtc always reads "live · WebRTC", ignoring latency/showBehindLive entirely', () => {
    expect(behindLiveChipLabel('webrtc', null, false)).toBe('live · WebRTC');
    expect(behindLiveChipLabel('webrtc', 12, true)).toBe('live · WebRTC');
  });

  it('hls reads plain "live · HLS" while showBehindLive is false, even with a known latency', () => {
    expect(behindLiveChipLabel('hls', 6.7, false)).toBe('live · HLS');
  });

  it('hls reads plain "live · HLS" when latency is unmeasured, even if showBehindLive is true', () => {
    expect(behindLiveChipLabel('hls', null, true)).toBe('live · HLS');
  });

  it('hls quantifies to one decimal place once showBehindLive is true and latency is known', () => {
    expect(behindLiveChipLabel('hls', 4.25, true)).toBe('live · HLS · 4.3s behind');
    expect(behindLiveChipLabel('hls', 4.24, true)).toBe('live · HLS · 4.2s behind');
    expect(behindLiveChipLabel('hls', 0, true)).toBe('live · HLS · 0.0s behind');
  });
});
