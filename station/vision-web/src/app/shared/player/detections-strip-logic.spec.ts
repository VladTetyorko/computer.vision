import { describe, expect, it } from 'vitest';
import type { Detection, DetectionResult } from '../../core/api/models';
import { STRIP_CHIP_CAP, stripChips } from './detections-strip-logic';

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

describe('stripChips', () => {
  it('aggregates per label with a count — "person x3"', () => {
    const r = result({
      detections: [
        detection({ label: 'person' }),
        detection({ label: 'person' }),
        detection({ label: 'person' }),
        detection({ label: 'car' }),
      ],
    });
    expect(stripChips([r])).toEqual([
      { label: 'person', count: 3, hidden: false },
      { label: 'car', count: 1, hidden: false },
    ]);
  });

  it('is recency-ordered — the newest result (first in the array) wins first position', () => {
    const newer = result({ frameSequence: 2, detections: [detection({ label: 'car' })] });
    const older = result({ frameSequence: 1, detections: [detection({ label: 'person' })] });
    expect(stripChips([newer, older]).map((c) => c.label)).toEqual(['car', 'person']);
  });

  it('a label already counted from a newer batch is not recounted from an older one', () => {
    const newer = result({
      frameSequence: 2,
      detections: [detection({ label: 'person' }), detection({ label: 'person' })],
    });
    const older = result({
      frameSequence: 1,
      detections: [detection({ label: 'person' }), detection({ label: 'person' }), detection({ label: 'person' })],
    });
    expect(stripChips([newer, older])).toEqual([{ label: 'person', count: 2, hidden: false }]);
  });

  it('caps observed labels at the given cap', () => {
    const labels = Array.from({ length: STRIP_CHIP_CAP + 5 }, (_, i) => `l${i}`);
    const r = result({ detections: labels.map((label) => detection({ label })) });
    expect(stripChips([r]).length).toBe(STRIP_CHIP_CAP);
  });

  it('marks an observed label hidden when it is in labelDenyFilter, keeping its real count', () => {
    const r = result({ detections: [detection({ label: 'person' }), detection({ label: 'person' })] });
    expect(stripChips([r], ['person'])).toEqual([{ label: 'person', count: 2, hidden: true }]);
  });

  it('appends a denied label that no longer appears in results at all, count frozen at 0', () => {
    const r = result({ detections: [detection({ label: 'car' })] });
    expect(stripChips([r], ['truck'])).toEqual([
      { label: 'car', count: 1, hidden: false },
      { label: 'truck', count: 0, hidden: true },
    ]);
  });

  it('never double-counts a label that is both observed and denied', () => {
    const r = result({ detections: [detection({ label: 'person' })] });
    expect(stripChips([r], ['person'])).toEqual([{ label: 'person', count: 1, hidden: true }]);
  });

  it('denied-only labels are still subject to the cap', () => {
    const denyFilter = Array.from({ length: STRIP_CHIP_CAP + 3 }, (_, i) => `d${i}`);
    expect(stripChips([], denyFilter).length).toBe(STRIP_CHIP_CAP);
  });

  it('defaults labelDenyFilter to [] — nothing renders hidden without an explicit deny-list', () => {
    const r = result({ detections: [detection({ label: 'person' })] });
    expect(stripChips([r])).toEqual([{ label: 'person', count: 1, hidden: false }]);
  });

  it('returns [] for no results and no deny-list', () => {
    expect(stripChips([])).toEqual([]);
  });
});
