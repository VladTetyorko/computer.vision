import { describe, expect, it } from 'vitest';
import { canStartTrainingDataset, canSubmitTrainingRequest, streamCaptureLabel } from './dataset-detail-logic';

describe('streamCaptureLabel', () => {
  const stream = { streamId: 'stream-1234567890', deviceId: 'dev-1' };

  it("uses the device's own name when resolvable", () => {
    expect(streamCaptureLabel(stream, [{ id: 'dev-1', name: 'North camera' }])).toBe('North camera');
  });

  it('falls back to a short stream id when the device is unknown', () => {
    expect(streamCaptureLabel(stream, [])).toBe('Stream stream-1');
  });
});

describe('canStartTrainingDataset', () => {
  it('is false with no labeled samples', () => {
    expect(canStartTrainingDataset({ sampleCounts: { PENDING: 3, LABELED: 0, DISCARDED: 1 } })).toBe(false);
  });

  it('is true once at least one sample is labeled', () => {
    expect(canStartTrainingDataset({ sampleCounts: { PENDING: 3, LABELED: 1, DISCARDED: 1 } })).toBe(true);
  });

  it('is false for a null dataset (not yet loaded)', () => {
    expect(canStartTrainingDataset(null)).toBe(false);
  });
});

describe('canSubmitTrainingRequest', () => {
  it('requires a non-blank base model and a positive integer epoch count', () => {
    expect(canSubmitTrainingRequest('yolo26n.pt', 50, false)).toBe(true);
    expect(canSubmitTrainingRequest('  ', 50, false)).toBe(false);
    expect(canSubmitTrainingRequest('yolo26n.pt', 0, false)).toBe(false);
    expect(canSubmitTrainingRequest('yolo26n.pt', -1, false)).toBe(false);
    expect(canSubmitTrainingRequest('yolo26n.pt', 1.5, false)).toBe(false);
    expect(canSubmitTrainingRequest('yolo26n.pt', null, false)).toBe(false);
  });

  it('is false while a previous start is still in flight', () => {
    expect(canSubmitTrainingRequest('yolo26n.pt', 50, true)).toBe(false);
  });
});
