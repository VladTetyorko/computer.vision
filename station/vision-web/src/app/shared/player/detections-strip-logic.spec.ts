import { describe, expect, it } from 'vitest';
import type { Detection, DetectionResult } from '../../core/api/models';
import { STRIP_CHIP_CAP, STRIP_WINDOW_SECONDS, stripChips } from './detections-strip-logic';

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

/** A tracked detection — {@link detection}'s own shape plus a `track`, needed for the sticky-label
 *  grouping cases below (docs/plans/done/TRACK-IDENTITY-PLAN.md §L3 item 2). */
function trackedDetection(partial: Partial<Detection>, trackId: number): Detection {
  return detection({
    track: { id: trackId, state: 'CONFIRMED', source: 'TRACKER', velocityX: 0, velocityY: 0, reupdated: false },
    ...partial,
  });
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

  it('aggregates the max concurrent count within the window, not a naive sum across batches (docs/plans/done/TRACK-IDENTITY-PLAN.md §L3 item 2)', () => {
    // Both batches land inside the default 5s window (2s apart) — the same handful of people
    // re-detected every batch must not multiply into a growing count; the busiest single instant wins.
    const newer = result({
      capturedAt: '2026-07-23T10:00:02Z',
      detections: [detection({ label: 'person' }), detection({ label: 'person' })],
    });
    const older = result({
      capturedAt: '2026-07-23T10:00:00Z',
      detections: [detection({ label: 'person' }), detection({ label: 'person' }), detection({ label: 'person' })],
    });
    expect(stripChips([newer, older])).toEqual([{ label: 'person', count: 3, hidden: false }]);
  });

  it('a batch older than STRIP_WINDOW_SECONDS off the newest one does not contribute to the aggregate', () => {
    expect(STRIP_WINDOW_SECONDS).toBe(5);
    const newer = result({ capturedAt: '2026-07-23T10:00:10Z', detections: [detection({ label: 'person' })] });
    const tooOld = result({
      capturedAt: '2026-07-23T10:00:00Z', // 10s before `newer`, outside the 5s window
      detections: Array.from({ length: 5 }, () => detection({ label: 'person' })),
    });
    expect(stripChips([newer, tooOld])).toEqual([{ label: 'person', count: 1, hidden: false }]);
  });

  it('groups a track whose raw label flips batch-to-batch under one sticky chip instead of churning between two', () => {
    // Mirrors `detection-overlay-logic.spec.ts#electStickyLabels`'s own "incumbent holds under
    // alternating noise" case: with matched, moderate confidence neither label ever leads by the
    // switch margin, so the track's elected label stays its first observation, "plant" — even though
    // the newest batch's own raw label is "helicopter". Pre-item-2, this would have surfaced as two
    // separate chips (one per raw label, each blinking in as the other blinked out); the strip now
    // shows the one stable identity the operator actually cares about.
    const newer = result({
      capturedAt: '2026-07-23T10:00:01Z',
      detections: [trackedDetection({ label: 'helicopter', confidence: 0.5 }, 7)],
    });
    const older = result({
      capturedAt: '2026-07-23T10:00:00Z',
      detections: [trackedDetection({ label: 'plant', confidence: 0.5 }, 7)],
    });
    expect(stripChips([newer, older])).toEqual([{ label: 'plant', count: 1, hidden: false }]);
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
