import { describe, expect, it } from 'vitest';
import { PLAYGROUND_MODES, canSubmitPlayground, playgroundModeNeedsVideoPath } from './playground-logic';

describe('playgroundModeNeedsVideoPath', () => {
  it('is true for the two file-based modes', () => {
    expect(playgroundModeNeedsVideoPath('direct')).toBe(true);
    expect(playgroundModeNeedsVideoPath('rtsp')).toBe(true);
  });

  it('is false for the fully synthetic test-drone mode', () => {
    expect(playgroundModeNeedsVideoPath('testDrone')).toBe(false);
  });
});

describe('canSubmitPlayground', () => {
  it('never blocks testDrone — it has no required field', () => {
    expect(canSubmitPlayground({ mode: 'testDrone', videoPath: '' })).toBe(true);
  });

  it('blocks direct/rtsp on a blank or whitespace-only video path', () => {
    expect(canSubmitPlayground({ mode: 'direct', videoPath: '' })).toBe(false);
    expect(canSubmitPlayground({ mode: 'rtsp', videoPath: '   ' })).toBe(false);
  });

  it('allows direct/rtsp once a video path is entered', () => {
    expect(canSubmitPlayground({ mode: 'direct', videoPath: '/srv/videos/flight.mp4' })).toBe(true);
    expect(canSubmitPlayground({ mode: 'rtsp', videoPath: '/srv/videos/flight.mp4' })).toBe(true);
  });
});

describe('PLAYGROUND_MODES', () => {
  it('lists exactly the three /api/simulations-backed modes, testDrone first (the zero-setup default)', () => {
    expect(PLAYGROUND_MODES).toEqual(['testDrone', 'direct', 'rtsp']);
  });
});
