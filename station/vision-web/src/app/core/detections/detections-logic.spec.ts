import { describe, expect, it } from 'vitest';
import type { Detection, DetectionResult } from '../api/models';
import {
  CV_STATUS_FRESH_SECONDS,
  cvStatus,
  deriveChips,
  freshResults,
  HIDDEN_CLASS_TRUTH,
  isLabelDenied,
  MAX_DETECTION_CHIPS,
  toggleLabelDeny,
} from './detections-logic';

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

describe('freshResults', () => {
  const now = Date.parse('2026-07-23T10:00:10Z');

  it('keeps a result within the freshness window', () => {
    const r = result({ capturedAt: new Date(now - CV_STATUS_FRESH_SECONDS * 1000).toISOString() });
    expect(freshResults([r], now)).toEqual([r]);
  });

  it('drops a result older than the freshness window', () => {
    const r = result({ capturedAt: new Date(now - (CV_STATUS_FRESH_SECONDS * 1000 + 1)).toISOString() });
    expect(freshResults([r], now)).toEqual([]);
  });

  it('filters per-result rather than all-or-nothing, preserving newest-first order', () => {
    const fresh = result({
      frameSequence: 2,
      capturedAt: new Date(now - 1000).toISOString(),
    });
    const stale = result({
      frameSequence: 1,
      capturedAt: new Date(now - (CV_STATUS_FRESH_SECONDS * 1000 + 1)).toISOString(),
    });

    // newest-first, matching what DetectionsStore.results() feeds it
    expect(freshResults([fresh, stale], now)).toEqual([fresh]);
  });

  it('returns an empty array once every result has aged out — the same moment cvStatus would read off', () => {
    const r = result({ capturedAt: new Date(now - (CV_STATUS_FRESH_SECONDS * 1000 + 1)).toISOString() });

    expect(freshResults([r], now)).toEqual([]);
    expect(cvStatus(r.capturedAt, now)).toBe('off');
  });

  it('returns an empty array for no results', () => {
    expect(freshResults([], now)).toEqual([]);
  });

  it('respects a custom freshSeconds window', () => {
    const r = result({ capturedAt: new Date(now - 30_000).toISOString() });
    expect(freshResults([r], now, 60)).toEqual([r]);
    expect(freshResults([r], now, 10)).toEqual([]);
  });
});

// --- Class deny-list (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-2/D-3, wave W5) -------------------------------

describe('isLabelDenied', () => {
  it('is false for an empty deny-list', () => {
    expect(isLabelDenied([], 'person')).toBe(false);
  });

  it('is true when the label is present', () => {
    expect(isLabelDenied(['person', 'car'], 'person')).toBe(true);
  });

  it('is false for a label not in the list', () => {
    expect(isLabelDenied(['car'], 'person')).toBe(false);
  });
});

describe('toggleLabelDeny', () => {
  it('adds a label not yet denied', () => {
    expect(toggleLabelDeny([], 'person')).toEqual(['person']);
    expect(toggleLabelDeny(['car'], 'person')).toEqual(['car', 'person']);
  });

  it('removes a label already denied, leaving the rest untouched', () => {
    expect(toggleLabelDeny(['person'], 'person')).toEqual([]);
    expect(toggleLabelDeny(['car', 'person', 'truck'], 'person')).toEqual(['car', 'truck']);
  });

  it('is symmetric — toggling twice is a no-op', () => {
    const start: readonly string[] = ['car'];
    expect(toggleLabelDeny(toggleLabelDeny(start, 'person'), 'person')).toEqual(start);
  });

  it('never mutates the input array', () => {
    const start = ['car'];
    toggleLabelDeny(start, 'person');
    expect(start).toEqual(['car']);
  });
});

describe('HIDDEN_CLASS_TRUTH', () => {
  it('is a non-empty, stable disclosure string shared by every hide affordance', () => {
    expect(HIDDEN_CLASS_TRUTH.length).toBeGreaterThan(0);
    expect(HIDDEN_CLASS_TRUTH).toContain('screen');
    expect(HIDDEN_CLASS_TRUTH).toContain('alerts');
    expect(HIDDEN_CLASS_TRUTH).toContain('recording');
  });
});
