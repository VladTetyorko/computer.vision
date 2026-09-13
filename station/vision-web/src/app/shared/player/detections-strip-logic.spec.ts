import { describe, expect, it } from 'vitest';
import type { Detection, DetectionResult, WorldObject } from '../../core/api/models';
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

/** A tracked detection — {@link detection}'s own shape plus a `track`, needed for the wire-label
 *  grouping cases below (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6, wave W3.2). */
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

/** A minimal {@link WorldObject} carrying just enough for {@link displayLabel}'s own lookup — `tier`
 *  is irrelevant to `stripChips` (it never reads `render`), kept non-`'HIDDEN'` only for realism. */
function worldObject(trackId: number, label?: string): WorldObject {
  return {
    state: {
      id: trackId,
      lifecycle: 'CONFIRMED',
      streamId: 's-0',
      ...(label !== undefined ? { identity: { label, labelRaw: label, candidates: [], stability: 1 } } : {}),
    },
    operator: { followed: false, denied: false },
    event: {},
    render: { tier: 'T1' },
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

  // --- Wire label grouping (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.6, wave W3.2) -------------

  it('groups a tracked detection under its world object\'s wire-elected label, not its raw per-batch label', () => {
    // The world model already elected "plant" for track 7; the newest batch's own raw detector label
    // is "helicopter" (a die-roll on an open-vocab model) — the wire label wins the grouping.
    const r = result({ detections: [trackedDetection({ label: 'helicopter', confidence: 0.5 }, 7)] });
    const worldObjectsById = new Map([[7, worldObject(7, 'plant')]]);
    expect(stripChips([r], worldObjectsById)).toEqual([{ label: 'plant', count: 1, hidden: false }]);
  });

  it('a tracked detection with no matching world object yet falls back to its raw label — the transient pre-arrival gap', () => {
    const r = result({ detections: [trackedDetection({ label: 'helicopter' }, 7)] });
    expect(stripChips([r], new Map())).toEqual([{ label: 'helicopter', count: 1, hidden: false }]);
  });

  it('a tracked detection whose world object has no elected identity yet also falls back to its raw label', () => {
    const r = result({ detections: [trackedDetection({ label: 'helicopter' }, 7)] });
    const worldObjectsById = new Map([[7, worldObject(7)]]); // no `identity` group at all
    expect(stripChips([r], worldObjectsById)).toEqual([{ label: 'helicopter', count: 1, hidden: false }]);
  });

  it('an untracked detection is never looked up at all — same raw label regardless of worldObjectsById', () => {
    const r = result({ detections: [detection({ label: 'car' })] });
    const worldObjectsById = new Map([[999, worldObject(999, 'unrelated')]]);
    expect(stripChips([r], worldObjectsById)).toEqual([{ label: 'car', count: 1, hidden: false }]);
  });

  it('defaults worldObjectsById to empty — every label is used raw, unchanged from before wave W3.2', () => {
    const r = result({ detections: [trackedDetection({ label: 'car' }, 7)] });
    expect(stripChips([r])).toEqual([{ label: 'car', count: 1, hidden: false }]);
  });

  it('caps observed labels at the given cap', () => {
    const labels = Array.from({ length: STRIP_CHIP_CAP + 5 }, (_, i) => `l${i}`);
    const r = result({ detections: labels.map((label) => detection({ label })) });
    expect(stripChips([r]).length).toBe(STRIP_CHIP_CAP);
  });

  it('marks an observed label hidden when it is in labelDenyFilter, keeping its real count', () => {
    const r = result({ detections: [detection({ label: 'person' }), detection({ label: 'person' })] });
    expect(stripChips([r], new Map(), ['person'])).toEqual([{ label: 'person', count: 2, hidden: true }]);
  });

  it('appends a denied label that no longer appears in results at all, count frozen at 0', () => {
    const r = result({ detections: [detection({ label: 'car' })] });
    expect(stripChips([r], new Map(), ['truck'])).toEqual([
      { label: 'car', count: 1, hidden: false },
      { label: 'truck', count: 0, hidden: true },
    ]);
  });

  it('never double-counts a label that is both observed and denied', () => {
    const r = result({ detections: [detection({ label: 'person' })] });
    expect(stripChips([r], new Map(), ['person'])).toEqual([{ label: 'person', count: 1, hidden: true }]);
  });

  it('denied-only labels are still subject to the cap', () => {
    const denyFilter = Array.from({ length: STRIP_CHIP_CAP + 3 }, (_, i) => `d${i}`);
    expect(stripChips([], new Map(), denyFilter).length).toBe(STRIP_CHIP_CAP);
  });

  it('defaults labelDenyFilter to [] — nothing renders hidden without an explicit deny-list', () => {
    const r = result({ detections: [detection({ label: 'person' })] });
    expect(stripChips([r])).toEqual([{ label: 'person', count: 1, hidden: false }]);
  });

  it('returns [] for no results and no deny-list', () => {
    expect(stripChips([])).toEqual([]);
  });
});
