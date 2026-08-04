import { describe, expect, it, vi } from 'vitest';
import type { CvModel, DetectionResult } from '../../core/api/models';
import type { PipelineSettings } from '../../core/settings/settings-store';
import {
  PEOPLE_VEHICLES_BUILDINGS_PRESET,
  addLabel,
  applyPreset,
  buildHotKnobPatch,
  buildModelChangePatch,
  chipCandidates,
  debounce,
  filterLabelsByQuery,
  findModel,
  hasExactLabelMatch,
  isLabelChecked,
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
});
