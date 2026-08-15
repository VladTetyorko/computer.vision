import { describe, expect, it } from 'vitest';
import type { Detection, DetectionResult } from '../api/models';
import { CV_STATUS_FRESH_SECONDS, cvStatus, deriveChips, MAX_DETECTION_CHIPS } from './detections-logic';

function detection(partial: Partial<Detection>): Detection {
  return {
    label: 'person',
    confidence: 0.9,
    box: { x: 0.1, y: 0.1, width: 0.2, height: 0.2 },
    modelId: 'yolo',
    modelVersion: 'latest',
    ...partial,
  };
}

function result(partial: Partial<DetectionResult>): DetectionResult {
  return {
    streamId: 's-0',
    frameSequence: 0,
    capturedAt: '2026-07-23T10:00:00Z',
    inferenceMillis: 5,
    detections: [],
    ...partial,
  };
}

describe('deriveChips', () => {
  it('returns one chip per distinct label', () => {
    const r = result({
      detections: [detection({ label: 'person', confidence: 0.87 }), detection({ label: 'car', confidence: 0.65 })],
    });

    expect(deriveChips([r])).toEqual([
      { label: 'person', confidence: 0.87 },
      { label: 'car', confidence: 0.65 },
    ]);
  });

  it('keeps only the most recent confidence for a label that reappears across results', () => {
    const newer = result({
      frameSequence: 2,
      capturedAt: '2026-07-23T10:00:02Z',
      detections: [detection({ label: 'person', confidence: 0.95 })],
    });
    const older = result({
      frameSequence: 1,
      capturedAt: '2026-07-23T10:00:01Z',
      detections: [detection({ label: 'person', confidence: 0.5 })],
    });

    // results are passed newest-first, matching what the backend/VisionApi returns
    expect(deriveChips([newer, older])).toEqual([{ label: 'person', confidence: 0.95 }]);
  });

  it('caps at maxChips distinct labels', () => {
    const labels = ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j'];
    const r = result({ detections: labels.map((label) => detection({ label, confidence: 0.5 })) });

    const chips = deriveChips([r]);

    expect(chips).toHaveLength(MAX_DETECTION_CHIPS);
    expect(chips.map((c) => c.label)).toEqual(labels.slice(0, MAX_DETECTION_CHIPS));
  });

  it('respects a custom maxChips', () => {
    const r = result({
      detections: [detection({ label: 'a' }), detection({ label: 'b' }), detection({ label: 'c' })],
    });

    expect(deriveChips([r], 2)).toHaveLength(2);
  });

  it('returns an empty array for no results', () => {
    expect(deriveChips([])).toEqual([]);
  });

  it('returns an empty array when every result has no detections', () => {
    expect(deriveChips([result({}), result({})])).toEqual([]);
  });
});

describe('cvStatus', () => {
  const now = Date.parse('2026-07-23T10:00:10Z');

  it('is off when no result has ever arrived', () => {
    expect(cvStatus(undefined, now)).toBe('off');
  });

  it('is on when the latest result is within the freshness window', () => {
    const capturedAt = new Date(now - CV_STATUS_FRESH_SECONDS * 1000).toISOString();
    expect(cvStatus(capturedAt, now)).toBe('on');
  });

  it('is off once the latest result is older than the freshness window', () => {
    const capturedAt = new Date(now - (CV_STATUS_FRESH_SECONDS * 1000 + 1)).toISOString();
    expect(cvStatus(capturedAt, now)).toBe('off');
  });

  it('is on for a result captured at exactly now', () => {
    expect(cvStatus(new Date(now).toISOString(), now)).toBe('on');
  });

  it('clamps a future-dated sample (clock skew) to age zero rather than going negative', () => {
    const capturedAt = new Date(now + 1000).toISOString();
    expect(cvStatus(capturedAt, now)).toBe('on');
  });
});
