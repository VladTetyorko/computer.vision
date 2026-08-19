import { describe, expect, it } from 'vitest';
import { resolveDetectionEnabled, videoNotice } from './stream-state-logic';

describe('resolveDetectionEnabled', () => {
  it('renders the running stream truth, whichever way the draft points', () => {
    expect(resolveDetectionEnabled(true, false)).toBe(true);
    expect(resolveDetectionEnabled(false, true)).toBe(false);
  });

  it('falls back to the draft when no stream is running', () => {
    expect(resolveDetectionEnabled(undefined, true)).toBe(true);
    expect(resolveDetectionEnabled(undefined, false)).toBe(false);
  });

  it('treats `false` as a real answer, not as absence', () => {
    // The whole defect this replaces: `streamValue || draft` would have shown `true` here.
    expect(resolveDetectionEnabled(false, true)).toBe(false);
  });
});

describe('videoNotice', () => {
  it('says nothing when no stream is running', () => {
    expect(videoNotice(false, 'STALLED')).toBeNull();
  });

  it('says nothing while video is flowing', () => {
    expect(videoNotice(true, 'LIVE')).toBeNull();
  });

  it('says nothing when the backend could not judge', () => {
    expect(videoNotice(true, 'UNOBSERVED')).toBeNull();
    expect(videoNotice(true, undefined)).toBeNull();
  });

  it('warns on the two faults and keeps them apart', () => {
    expect(videoNotice(true, 'STALLED')).toEqual({
      tone: 'warn',
      text: 'No video arriving — the source stopped sending.',
    });
    expect(videoNotice(true, 'RECONNECTING')).toEqual({
      tone: 'warn',
      text: 'Reconnecting to the video source…',
    });
  });

  it('reports the pre-first-frame window neutrally — it is not a fault', () => {
    expect(videoNotice(true, 'STARTING')?.tone).toBe('neutral');
  });
});
