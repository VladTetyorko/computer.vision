import { describe, expect, it, vi } from 'vitest';
import type { CvModel, CvTracker, DetectionResult, FrameTracking, TrackingCapability, TrackStats } from '../../core/api/models';
import type { PipelineSettings } from '../../core/settings/settings-store';
import {
  CAPABILITY_LEVEL_OPTIONS,
  DETECTION_LAG_BUDGET_MILLIS,
  PEOPLE_VEHICLES_BUILDINGS_PRESET,
  addLabel,
  applyPreset,
  buildCapabilityLevelPatch,
  buildFollowFpsPatch,
  buildFollowLockPatch,
  buildHotKnobPatch,
  buildModelChangePatch,
  buildReleaseLockPatch,
  buildTrackingEnginePatch,
  buildTrackingModePatch,
  buildVerifyCadencePatch,
  capabilityLevelHint,
  capabilityLevelLabel,
  capabilityLevelOption,
  chipCandidates,
  debounce,
  engineOptionsForMode,
  filterLabelsByQuery,
  findModel,
  formatDetectionLag,
  formatFlowStrip,
  hasExactLabelMatch,
  isCapabilityDowngraded,
  isDetectionLagOverBudget,
  isLabelChecked,
  latestFrameTracking,
  observedLabels,
  perfHint,
  reArmHint,
  seedLabelFilterForModel,
  sortSelectedFirst,
  toggleLabelChip,
} from './cv-control-panel-logic';

function model(partial: Partial<CvModel> = {}): CvModel {
  return {
    id: 'yolo26n.pt',
    displayName: 'General (people & vehicles, fast)',
    kind: 'general',
    openVocab: false,
    defaultLabelFilter: [],
    ...partial,
  };
}

function detectionResult(labels: readonly string[]): DetectionResult {
  return {
    streamId: 'stream-1',
    frameSequence: 0,
    capturedAt: '2026-07-30T00:00:00Z',
    inferenceMillis: 10,
    detections: labels.map((label) => ({
      label,
      confidence: 0.9,
      box: { x: 0, y: 0, width: 0.1, height: 0.1 },
      modelId: 'yolo26n.pt',
      modelVersion: 'latest',
    })),
  };
}

function settings(partial: Partial<PipelineSettings> = {}): PipelineSettings {
  return {
    confidenceThreshold: 0.4,
    inferenceFps: 5,
    model: 'yolo26n.pt',
    labelFilter: [],
    detectionEnabled: true,
    ...partial,
  };
}

describe('cv-control-panel-logic', () => {
  describe('findModel', () => {
    it('finds a roster entry by id', () => {
      const models = [model({ id: 'a' }), model({ id: 'b' })];
      expect(findModel(models, 'b')?.id).toBe('b');
    });

    it('returns undefined for an unknown id', () => {
      expect(findModel([model({ id: 'a' })], 'z')).toBeUndefined();
    });
  });

  describe('seedLabelFilterForModel', () => {
    it('seeds a closed-set model\'s own defaultLabelFilter as-is', () => {
      const m = model({ openVocab: false, defaultLabelFilter: ['person', 'car'] });
      expect(seedLabelFilterForModel(m)).toEqual(['person', 'car']);
    });

    it('always seeds [] for an open-vocab model, even if the roster carries a non-empty defaultLabelFilter', () => {
      const m = model({ openVocab: true, defaultLabelFilter: ['person', 'car', 'building'] });
      expect(seedLabelFilterForModel(m)).toEqual([]);
    });

    it('seeds [] for an unresolved (undefined) model', () => {
      expect(seedLabelFilterForModel(undefined)).toEqual([]);
    });
  });

  describe('observedLabels', () => {
    it('collects distinct labels across results, first-seen order', () => {
      const results = [detectionResult(['person', 'car']), detectionResult(['car', 'building'])];
      expect(observedLabels(results)).toEqual(['person', 'car', 'building']);
    });

    it('returns [] for no results', () => {
      expect(observedLabels([])).toEqual([]);
    });
  });

  describe('chipCandidates', () => {
    it('unions the current filter with observed labels, sorted', () => {
      expect(chipCandidates(['zebra'], ['car', 'apple'])).toEqual(['apple', 'car', 'zebra']);
    });

    it('deduplicates overlapping entries', () => {
      expect(chipCandidates(['car'], ['car', 'bus'])).toEqual(['bus', 'car']);
    });
  });

  describe('isLabelChecked', () => {
    it('reads every candidate as checked when the filter is empty (all)', () => {
      expect(isLabelChecked([], 'anything')).toBe(true);
    });

    it('reads only listed labels as checked otherwise', () => {
      expect(isLabelChecked(['person'], 'person')).toBe(true);
      expect(isLabelChecked(['person'], 'car')).toBe(false);
    });
  });

  describe('toggleLabelChip', () => {
    it('unchecking one candidate while "all" narrows to every other known candidate', () => {
      const next = toggleLabelChip([], 'car', ['person', 'car', 'building']);
      expect(next).toEqual(['person', 'building']);
    });

    it('unchecks a label from a concrete filter', () => {
      expect(toggleLabelChip(['person', 'car'], 'car', ['person', 'car'])).toEqual(['person']);
    });

    it('checks (adds) a label not yet in a concrete filter', () => {
      expect(toggleLabelChip(['person'], 'car', ['person', 'car'])).toEqual(['person', 'car']);
    });

    it('unchecking the last remaining label honestly reverts to "all" ([])', () => {
      expect(toggleLabelChip(['person'], 'person', ['person'])).toEqual([]);
    });
  });

  describe('addLabel', () => {
    it('adds a trimmed label', () => {
      expect(addLabel(['person'], '  car  ')).toEqual(['person', 'car']);
    });

    it('is a no-op (same reference) for a blank label', () => {
      const current = ['person'];
      expect(addLabel(current, '   ')).toBe(current);
    });

    it('is a no-op (same reference) for a duplicate label', () => {
      const current = ['person'];
      expect(addLabel(current, 'person')).toBe(current);
    });

    it('narrows [] ("all") down to just the added label', () => {
      expect(addLabel([], 'airplane')).toEqual(['airplane']);
    });
  });

  describe('filterLabelsByQuery', () => {
    it('filters case-insensitively by substring', () => {
      expect(filterLabelsByQuery(['person', 'car', 'bicycle'], 'CAR')).toEqual(['car']);
    });

    it('returns the identical reference for a blank query', () => {
      const candidates = ['person', 'car'];
      expect(filterLabelsByQuery(candidates, '   ')).toBe(candidates);
    });

    it('returns [] when nothing matches', () => {
      expect(filterLabelsByQuery(['person', 'car'], 'airplane')).toEqual([]);
    });
  });

  describe('hasExactLabelMatch', () => {
    it('matches case-insensitively, trimmed', () => {
      expect(hasExactLabelMatch(['person', 'car'], '  Car  ')).toBe(true);
    });

    it('is false for a substring-only match', () => {
      expect(hasExactLabelMatch(['bicycle'], 'bi')).toBe(false);
    });

    it('is false for a blank query', () => {
      expect(hasExactLabelMatch(['person'], '   ')).toBe(false);
    });
  });

  describe('sortSelectedFirst', () => {
    it('promotes selected candidates ahead of unselected ones, keeping each half\'s own order', () => {
      expect(sortSelectedFirst(['airplane', 'bicycle', 'car', 'person'], ['person', 'car'])).toEqual([
        'car',
        'person',
        'airplane',
        'bicycle',
      ]);
    });

    it('is a no-op while the filter is [] ("all") — nothing to promote', () => {
      const candidates = ['airplane', 'bicycle', 'car'];
      expect(sortSelectedFirst(candidates, [])).toBe(candidates);
    });
  });

  describe('applyPreset', () => {
    it('narrows [] ("all") down to exactly the preset', () => {
      expect(applyPreset([], ['person', 'car'])).toEqual(['person', 'car']);
    });

    it('adds the preset into an existing concrete filter without disturbing existing entries', () => {
      expect(applyPreset(['airplane'], ['person', 'car'])).toEqual(['airplane', 'person', 'car']);
    });

    it('never duplicates an already-selected preset label', () => {
      expect(applyPreset(['person'], ['person', 'car'])).toEqual(['person', 'car']);
    });

    it('defaults to PEOPLE_VEHICLES_BUILDINGS_PRESET when no preset is given', () => {
      expect(applyPreset([])).toEqual(PEOPLE_VEHICLES_BUILDINGS_PRESET);
    });
  });

  describe('buildHotKnobPatch / buildModelChangePatch', () => {
    it('carries confidence/fps/labelFilter/detectionEnabled, never model', () => {
      const patch = buildHotKnobPatch(settings({ confidenceThreshold: 0.6, inferenceFps: 8, labelFilter: ['person'], detectionEnabled: false }));
      expect(patch).toEqual({
        confidenceThreshold: 0.6,
        inferenceFps: 8,
        labelFilter: ['person'],
        detectionEnabled: false,
      });
      expect(patch).not.toHaveProperty('model');
    });

    it('builds a model-only patch', () => {
      expect(buildModelChangePatch('yoloe-26s-seg-pf.pt')).toEqual({ model: 'yoloe-26s-seg-pf.pt' });
    });
  });

  describe('reArmHint', () => {
    it('returns a hint when modelReArmed is true', () => {
      expect(reArmHint({ streamId: 's-1', modelReArmed: true })).toMatch(/re-arming/i);
    });

    it('returns null when modelReArmed is false', () => {
      expect(reArmHint({ streamId: 's-1', modelReArmed: false })).toBeNull();
    });

    it('never claims the video was interrupted', () => {
      expect(reArmHint({ streamId: 's-1', modelReArmed: true })).toMatch(/video keeps playing/i);
    });
  });

  describe('perfHint', () => {
    it('warns more urgently for the open-vocab model', () => {
      expect(perfHint(true)).toMatch(/open-vocabulary/i);
      expect(perfHint(true)).toMatch(/slower/i);
    });

    it('never claims the class filter reduces CPU cost', () => {
      expect(perfHint(false)).not.toMatch(/class filter reduces cpu/i);
      expect(perfHint(false)).toMatch(/inference rate and detection on\/off/i);
    });
  });

  describe('debounce', () => {
    it('coalesces repeated calls into one, using the last call\'s args', () => {
      vi.useFakeTimers();
      try {
        const fn = vi.fn();
        const d = debounce(fn, 100);
        d.run('a');
        d.run('b');
        d.run('c');
        expect(fn).not.toHaveBeenCalled();
        vi.advanceTimersByTime(100);
        expect(fn).toHaveBeenCalledOnce();
        expect(fn).toHaveBeenCalledWith('c');
      } finally {
        vi.useRealTimers();
      }
    });

    it('cancel() drops a pending call', () => {
      vi.useFakeTimers();
      try {
        const fn = vi.fn();
        const d = debounce(fn, 100);
        d.run('a');
        d.cancel();
        vi.advanceTimersByTime(200);
        expect(fn).not.toHaveBeenCalled();
      } finally {
        vi.useRealTimers();
      }
    });

    it('a call after the delay elapsed fires independently', () => {
      vi.useFakeTimers();
      try {
        const fn = vi.fn();
        const d = debounce(fn, 100);
        d.run('a');
        vi.advanceTimersByTime(100);
        expect(fn).toHaveBeenCalledWith('a');

        d.run('b');
        vi.advanceTimersByTime(100);
        expect(fn).toHaveBeenCalledWith('b');
        expect(fn).toHaveBeenCalledTimes(2);
      } finally {
        vi.useRealTimers();
      }
    });
  });

  // --- Tracking engine (docs/plans/done/TRACKING-PLAN.md §4's frozen wire contract, wave T7) ---------------

  describe('tracking patch builders', () => {
    it('buildTrackingModePatch sends mode alone', () => {
      expect(buildTrackingModePatch('FOLLOW')).toEqual({ tracking: { mode: 'FOLLOW' } });
      expect(buildTrackingModePatch('OFF')).toEqual({ tracking: { mode: 'OFF' } });
    });

    it('buildTrackingEnginePatch sends engineId alone', () => {
      expect(buildTrackingEnginePatch('lk')).toEqual({ tracking: { engineId: 'lk' } });
    });

    it('buildVerifyCadencePatch sends verifyEveryMillis alone', () => {
      expect(buildVerifyCadencePatch(1500)).toEqual({ tracking: { verifyEveryMillis: 1500 } });
    });

    it('buildFollowFpsPatch sends followFps alone', () => {
      expect(buildFollowFpsPatch(20)).toEqual({ tracking: { followFps: 20 } });
    });

    it('buildFollowLockPatch sets mode FOLLOW alongside the lock, in one call', () => {
      expect(buildFollowLockPatch(7)).toEqual({ tracking: { mode: 'FOLLOW', lock: { trackId: 7 } } });
    });

    it('buildReleaseLockPatch leaves mode untouched', () => {
      expect(buildReleaseLockPatch()).toEqual({ tracking: { lock: { release: true } } });
    });
  });

  describe('engineOptionsForMode', () => {
    const bytetrack: CvTracker = { id: 'bytetrack', displayName: 'ByteTrack', modes: ['ASSOCIATE'], needsAssets: false, costHint: '~0.8 ms/frame' };
    const lk: CvTracker = { id: 'lk', displayName: 'Optical flow', modes: ['FOLLOW'], needsAssets: false, costHint: '~0.4 ms/frame' };
    const ncc: CvTracker = { id: 'ncc', displayName: 'Template match', modes: ['FOLLOW'], needsAssets: false, costHint: '~0.6 ms/frame' };
    const roster = [bytetrack, lk, ncc];

    it('OFF always has no engine to pick, regardless of roster contents', () => {
      expect(engineOptionsForMode(roster, 'OFF')).toEqual([]);
    });

    it('ASSOCIATE offers only engines advertising ASSOCIATE', () => {
      expect(engineOptionsForMode(roster, 'ASSOCIATE')).toEqual([bytetrack]);
    });

    it('FOLLOW offers only engines advertising FOLLOW', () => {
      expect(engineOptionsForMode(roster, 'FOLLOW')).toEqual([lk, ncc]);
    });

    it('an empty roster degrades to no options for any mode', () => {
      expect(engineOptionsForMode([], 'FOLLOW')).toEqual([]);
    });
  });

  describe('formatFlowStrip', () => {
    function stats(partial: Partial<TrackStats> = {}): TrackStats {
      return {
        mode: 'FOLLOW',
        engineId: 'lk',
        windowSeconds: 30,
        detectorPasses: 1,
        trackerFrames: 450,
        dutyRatio: 1 / 30,
        trackerMillisP50: 0.4,
        trackerMillisP95: 0.9,
        lastDetectorReason: 'CADENCE',
        byState: { TENTATIVE: 0, CONFIRMED: 3, COASTING: 1, LOST: 2 },
        ...partial,
      };
    }

    it('renders the DETECT/TRACK/duty/engine/cadence line', () => {
      expect(formatFlowStrip(stats())).toBe('DETECT 0/s ▸ TRACK 15/s · 1 in 30 · lk 0.4 ms · cadence');
    });

    it('shows the engine actually serving, per R11 — this is a display concern only, no roster involved', () => {
      expect(formatFlowStrip(stats({ engineId: 'ncc' }))).toContain('ncc');
    });

    it('lowercases the detector reason, expanding an underscore to a space', () => {
      expect(formatFlowStrip(stats({ lastDetectorReason: 'TRACKER_FAILED' }))).toContain('tracker failed');
    });

    it('degrades a zero duty ratio to an em dash rather than "1 in Infinity"', () => {
      expect(formatFlowStrip(stats({ dutyRatio: 0, detectorPasses: 0 }))).toContain('· — ·');
    });

    it('degrades a blank engine id to an em dash', () => {
      expect(formatFlowStrip(stats({ engineId: '' }))).toContain(' — ');
    });

    it('a zero-second window never divides by zero', () => {
      expect(() => formatFlowStrip(stats({ windowSeconds: 0 }))).not.toThrow();
      expect(formatFlowStrip(stats({ windowSeconds: 0 }))).toContain('0/s');
    });
  });

  // --- Capability ladder (docs/plans/active/TRACKING-V3-BAND1-CONTEXT.md, wave J4) -----------------

  function capability(partial: Partial<TrackingCapability> = {}): TrackingCapability {
    return { levelServed: 2, reason: '', ...partial };
  }

  function frameTracking(partial: Partial<FrameTracking> = {}): FrameTracking {
    return {
      detectorRan: true,
      detectorReason: 'CADENCE',
      trackerMillis: 0.4,
      engineId: 'lk',
      lockedTrackId: 3,
      detectionLagMillis: 42,
      reupdateMillis: 6,
      reupdatedTracks: 2,
      ...partial,
    };
  }

  function resultWithTracking(tracking: FrameTracking | undefined, capturedAt = '2026-08-14T00:00:00Z'): DetectionResult {
    return { ...detectionResult([]), capturedAt, tracking };
  }

  describe('CAPABILITY_LEVEL_OPTIONS / capabilityLevelOption / capabilityLevelLabel / capabilityLevelHint', () => {
    it('offers exactly Auto (0) through L5, in ascending order', () => {
      expect(CAPABILITY_LEVEL_OPTIONS.map((o) => o.value)).toEqual([0, 1, 2, 3, 4, 5]);
    });

    it('0 is labeled Auto — the default, and must read as a default, not a level', () => {
      expect(capabilityLevelOption(0)?.label).toBe('Auto');
    });

    it('every option carries a non-blank cost/benefit hint', () => {
      for (const option of CAPABILITY_LEVEL_OPTIONS) {
        expect(option.hint.trim().length).toBeGreaterThan(0);
      }
    });

    it('capabilityLevelLabel names a known level (shared by the picker and the served-level readout)', () => {
      expect(capabilityLevelLabel(1)).toBe('L1 · Relay');
      expect(capabilityLevelLabel(3)).toBe('L3 · Detect');
      expect(capabilityLevelLabel(5)).toBe('L5 · Study');
    });

    it('capabilityLevelLabel falls back to a bare "Ln" for an unrecognized level, never blank', () => {
      expect(capabilityLevelLabel(9)).toBe('L9');
    });

    it('capabilityLevelHint returns "" for an unrecognized level rather than throwing', () => {
      expect(capabilityLevelHint(9)).toBe('');
    });
  });

  describe('buildCapabilityLevelPatch', () => {
    it('sends capabilityLevel alone, nested under tracking', () => {
      expect(buildCapabilityLevelPatch(3)).toEqual({ tracking: { capabilityLevel: 3 } });
    });

    it('0 (Auto) is sent explicitly too — the proto zero-value, byte-identical to unset (B2)', () => {
      expect(buildCapabilityLevelPatch(0)).toEqual({ tracking: { capabilityLevel: 0 } });
    });
  });

  describe('isCapabilityDowngraded', () => {
    it('is false when no capability was reported at all (absence is never a downgrade)', () => {
      expect(isCapabilityDowngraded(undefined)).toBe(false);
    });

    it('is false when the served level matched the request — reason is empty', () => {
      expect(isCapabilityDowngraded(capability({ levelServed: 4, reason: '' }))).toBe(false);
    });

    it('is true whenever the server names a reason, regardless of the levels involved', () => {
      expect(isCapabilityDowngraded(capability({ levelServed: 2, reason: 'OpenVINO unavailable; degraded from requested L4 to L2' }))).toBe(true);
    });

    it('treats a whitespace-only reason as "no reason" too', () => {
      expect(isCapabilityDowngraded(capability({ reason: '   ' }))).toBe(false);
    });
  });

  describe('latestFrameTracking', () => {
    it('is undefined for no results', () => {
      expect(latestFrameTracking([])).toBeUndefined();
    });

    it('is undefined when the newest result carries no tracking object', () => {
      expect(latestFrameTracking([resultWithTracking(undefined)])).toBeUndefined();
    });

    it('reads the newest result\'s tracking object when present', () => {
      const tracking = frameTracking({ engineId: 'ncc' });
      expect(latestFrameTracking([resultWithTracking(tracking)])).toEqual(tracking);
    });

    it('never falls back to an older, stale tracking object once the newest result has none — B5\'s honesty rule applied to staleness, not just to the request/outcome split', () => {
      const stale = frameTracking({ engineId: 'lk' });
      const results = [resultWithTracking(undefined, '2026-08-14T00:00:05Z'), resultWithTracking(stale, '2026-08-14T00:00:00Z')];
      expect(latestFrameTracking(results)).toBeUndefined();
    });
  });

  describe('isDetectionLagOverBudget / formatDetectionLag', () => {
    it('DETECTION_LAG_BUDGET_MILLIS is the CV-RATE-BUDGET §1 Hold figure', () => {
      expect(DETECTION_LAG_BUDGET_MILLIS).toBe(50);
    });

    it('is not over budget at exactly the budget', () => {
      expect(isDetectionLagOverBudget(50)).toBe(false);
    });

    it('is over budget just past it', () => {
      expect(isDetectionLagOverBudget(51)).toBe(true);
    });

    it('0 ("unknown" on the wire) is never flagged as over budget', () => {
      expect(isDetectionLagOverBudget(0)).toBe(false);
    });

    it('a non-finite value is never flagged as over budget', () => {
      expect(isDetectionLagOverBudget(Number.NaN)).toBe(false);
    });

    it('formats a genuine positive reading in whole milliseconds', () => {
      expect(formatDetectionLag(42)).toBe('42 ms');
      expect(formatDetectionLag(42.6)).toBe('43 ms');
    });

    it('formats 0 as an em dash — "unknown", never a fabricated "0 ms"', () => {
      expect(formatDetectionLag(0)).toBe('—');
    });

    it('formats a negative or non-finite value as an em dash too', () => {
      expect(formatDetectionLag(-1)).toBe('—');
      expect(formatDetectionLag(Number.NaN)).toBe('—');
    });
  });
});
