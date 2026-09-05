import { describe, expect, it, vi } from 'vitest';
import type {
  CvModel,
  CvProfile,
  CvTracker,
  DetectionRate,
  DetectionResult,
  EffectiveCvProfile,
  FrameTracking,
  StreamConfigResponse,
  TrackingCapability,
  TrackStats,
} from '../../core/api/models';
import { HIDDEN_CLASS_TRUTH } from '../../core/detections/detections-logic';
import {
  CAPABILITY_LEVEL_OPTIONS,
  DETECTION_LAG_BUDGET_MILLIS,
  PEOPLE_VEHICLES_BUILDINGS_PRESET,
  SEEN_NOW_CHIP_CAP,
  addLabel,
  applyPreset,
  buildCapabilityLevelPatch,
  buildFollowFpsPatch,
  buildFollowLockPatch,
  buildHotKnobPatch,
  buildModelChangePatch,
  buildProfileRequestFromConfig,
  buildReleaseLockPatch,
  buildTrackingEnginePatch,
  buildTrackingModePatch,
  buildVerifyCadencePatch,
  capabilityLevelHint,
  capabilityLevelLabel,
  capabilityLevelOption,
  chipCandidates,
  classesOnScreenCount,
  debounce,
  defaultAssetProfileDescription,
  defaultAssetProfileName,
  describeProfileSource,
  detectionStatus,
  effectiveProfileLine,
  engineOptionsForMode,
  filterLabelsByQuery,
  findModel,
  formatDetectionLag,
  formatFlowStrip,
  formatMeasuredRate,
  hasExactLabelMatch,
  intentCardSentence,
  isCapabilityDowngraded,
  isDetectionLagOverBudget,
  isLabelChecked,
  latestFrameTracking,
  modelCostWord,
  observedLabels,
  perfHint,
  reArmHint,
  recentObservedLabels,
  resolveCvConfig,
  resolvedConfigFromProfile,
  resolvedConfigFromStream,
  seedLabelFilterForModel,
  sortRecentFirst,
  stagedLabelSeed,
  submitLabelFilterButtonText,
  type ResolvedCvConfig,
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

function resolvedConfig(partial: Partial<ResolvedCvConfig> = {}): ResolvedCvConfig {
  return {
    confidenceThreshold: 0.4,
    inferenceFps: 5,
    model: 'yolo26n.pt',
    labelFilter: [],
    labelDenyFilter: [],
    detectionEnabled: true,
    tracking: { mode: 'OFF', engineId: '', capabilityLevel: 0, verifyEveryMillis: 2000, followFps: 15 },
    ...partial,
  };
}

function streamConfig(partial: Partial<StreamConfigResponse> = {}): StreamConfigResponse {
  return {
    model: 'yolo26n.pt',
    confidenceThreshold: 0.4,
    inferenceFps: 5,
    labelFilter: [],
    labelDenyFilter: [],
    detectionEnabled: true,
    tracking: {
      mode: 'OFF',
      engineId: '',
      verifyEveryMillis: 2000,
      followFps: 15,
      redetectIouPercent: 30,
      maxAgeFrames: 30,
      minHits: 3,
      capabilityLevel: 0,
      reupdateMaxGapMillis: 5000,
    },
    ...partial,
  };
}

function cvProfile(partial: Partial<CvProfile> = {}): CvProfile {
  return {
    id: 'profile-1',
    name: 'Balanced',
    description: 'Backend defaults.',
    builtIn: true,
    model: 'yolo26n.pt',
    confidenceThreshold: 0.4,
    inferenceFps: 5,
    labelFilter: [],
    labelDenyFilter: [],
    detectionEnabled: false,
    tracking: { mode: 'OFF', engineId: '', capabilityLevel: 0, verifyEveryMillis: 2000, followFps: 15 },
    eventRule: { labels: [], confidenceThreshold: 0.4, consecutiveToOpen: 1, absenceToCloseSeconds: 30 },
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
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

  describe('modelCostWord', () => {
    it('reads "slower" for an open-vocabulary model', () => {
      expect(modelCostWord(true)).toBe('slower');
    });

    it('reads "fast" for a closed-set model', () => {
      expect(modelCostWord(false)).toBe('fast');
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
    it('unions the filter, deny-list and observed labels, sorted', () => {
      expect(chipCandidates(['zebra'], [], ['car', 'apple'])).toEqual(['apple', 'car', 'zebra']);
    });

    it('deduplicates overlapping entries', () => {
      expect(chipCandidates(['car'], [], ['car', 'bus'])).toEqual(['bus', 'car']);
    });

    it('keeps a denied label in the checklist even when it is no longer observed — otherwise there is no way back to un-hide it', () => {
      expect(chipCandidates([], ['truck'], [])).toEqual(['truck']);
    });

    it('deduplicates a label that is both observed and denied', () => {
      expect(chipCandidates([], ['car'], ['car'])).toEqual(['car']);
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

  describe('sortRecentFirst', () => {
    it('promotes recently-observed candidates ahead of the rest, in their own recency order', () => {
      expect(sortRecentFirst(['airplane', 'bicycle', 'car', 'person'], ['person', 'car'])).toEqual([
        'person',
        'car',
        'airplane',
        'bicycle',
      ]);
    });

    it('is a no-op when nothing has been recently observed', () => {
      const candidates = ['airplane', 'bicycle', 'car'];
      expect(sortRecentFirst(candidates, [])).toBe(candidates);
    });

    it('ignores a recently-observed label that is not itself a candidate (filtered out by search)', () => {
      expect(sortRecentFirst(['airplane', 'bicycle'], ['car', 'airplane'])).toEqual(['airplane', 'bicycle']);
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
    it('carries confidence/fps/labelFilter/labelDenyFilter/detectionEnabled, never model', () => {
      const patch = buildHotKnobPatch(
        resolvedConfig({
          confidenceThreshold: 0.6,
          inferenceFps: 8,
          labelFilter: ['person'],
          labelDenyFilter: ['dog'],
          detectionEnabled: false,
        }),
      );
      expect(patch).toEqual({
        confidenceThreshold: 0.6,
        inferenceFps: 8,
        labelFilter: ['person'],
        labelDenyFilter: ['dog'],
        detectionEnabled: false,
      });
      expect(patch).not.toHaveProperty('model');
    });

    it('builds a model-only patch', () => {
      expect(buildModelChangePatch('yoloe-26s-seg-pf.pt')).toEqual({ model: 'yoloe-26s-seg-pf.pt' });
    });
  });

  // --- Resolved CV config (docs/plans/active/CV-SETTINGS-PLAN.md §3, wave W7) -------------------

  describe('resolvedConfigFromStream', () => {
    it('reduces a live StreamConfigResponse to a ResolvedCvConfig, tracking included', () => {
      const config = resolvedConfigFromStream(
        streamConfig({ confidenceThreshold: 0.7, tracking: { ...streamConfig().tracking, mode: 'FOLLOW', followFps: 20 } }),
      );
      expect(config.confidenceThreshold).toBe(0.7);
      expect(config.tracking).toEqual({ mode: 'FOLLOW', engineId: '', capabilityLevel: 0, verifyEveryMillis: 2000, followFps: 20 });
      // Session-only tracking fields (redetectIouPercent/maxAgeFrames/minHits/reupdateMaxGapMillis)
      // are not part of a profile's own tracking shape, and are dropped here.
      expect(config.tracking).not.toHaveProperty('redetectIouPercent');
    });
  });

  describe('resolvedConfigFromProfile', () => {
    it('reduces a CvProfile to a ResolvedCvConfig', () => {
      const config = resolvedConfigFromProfile(cvProfile({ model: 'orion12l.pt', inferenceFps: 10 }));
      expect(config.model).toBe('orion12l.pt');
      expect(config.inferenceFps).toBe(10);
      expect(config.tracking).toEqual({ mode: 'OFF', engineId: '', capabilityLevel: 0, verifyEveryMillis: 2000, followFps: 15 });
    });
  });

  describe('resolveCvConfig', () => {
    it('prefers the running stream config when both are available', () => {
      const config = resolveCvConfig(
        streamConfig({ model: 'from-stream.pt' }),
        { assetId: 'a-1', profile: cvProfile({ model: 'from-profile.pt' }), source: 'ASSET' },
      );
      expect(config?.model).toBe('from-stream.pt');
    });

    it('falls back to the effective profile before a stream exists', () => {
      const config = resolveCvConfig(undefined, {
        assetId: 'a-1',
        profile: cvProfile({ model: 'from-profile.pt' }),
        source: 'PLATFORM',
      });
      expect(config?.model).toBe('from-profile.pt');
    });

    it('is undefined when neither read is available — never a fabricated middle ground', () => {
      expect(resolveCvConfig(undefined, undefined)).toBeUndefined();
    });
  });

  describe('describeProfileSource', () => {
    it('names every binding scope plus PLATFORM in lowercase', () => {
      expect(describeProfileSource('ORGANIZATION')).toBe('organization');
      expect(describeProfileSource('CATEGORY')).toBe('category');
      expect(describeProfileSource('ASSET')).toBe('asset');
      expect(describeProfileSource('PLATFORM')).toBe('platform');
    });
  });

  describe('effectiveProfileLine', () => {
    it('names the profile and its source once resolved', () => {
      const effective: EffectiveCvProfile = { assetId: 'a-1', profile: cvProfile({ name: 'people-vehicles' }), source: 'ASSET' };
      expect(effectiveProfileLine('a-1', effective)).toBe('From profile "people-vehicles" (asset)');
    });

    it('reads "Platform defaults" when the stream has no asset at all', () => {
      expect(effectiveProfileLine(undefined, undefined)).toBe('Platform defaults');
    });

    it('reads "—" when an asset exists but the effective-profile read has not landed/failed', () => {
      expect(effectiveProfileLine('a-1', undefined)).toBe('—');
    });
  });

  describe('buildProfileRequestFromConfig / defaultAssetProfileName / defaultAssetProfileDescription', () => {
    it('carries every resolved field plus the given name/description', () => {
      const config = resolvedConfig({ model: 'orion12l.pt', labelFilter: ['person'] });
      const request = buildProfileRequestFromConfig(config, 'My profile', 'A description');
      expect(request).toEqual({
        name: 'My profile',
        description: 'A description',
        model: 'orion12l.pt',
        confidenceThreshold: 0.4,
        inferenceFps: 5,
        labelFilter: ['person'],
        labelDenyFilter: [],
        detectionEnabled: true,
        tracking: config.tracking,
      });
    });

    it('names a fresh asset profile honestly, after where it came from', () => {
      expect(defaultAssetProfileName('Rover 1')).toContain('Rover 1');
      expect(defaultAssetProfileDescription('Rover 1')).toContain('Rover 1');
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
    it('warns more urgently for the open-vocab model, in one short line', () => {
      expect(perfHint(true)).toMatch(/open-vocabulary/i);
      expect(perfHint(true)).toMatch(/slower/i);
      expect(perfHint(true).split('.').filter((s) => s.trim().length > 0).length).toBeLessThanOrEqual(1);
    });

    it('never claims the class filter reduces CPU cost', () => {
      expect(perfHint(false)).not.toMatch(/class filter reduces cpu/i);
      expect(perfHint(false)).toMatch(/inference rate and detection on\/off/i);
    });

    it('no longer claims the class filter "only trims what\'s shown" — corrected per CV-UX-RESEARCH §1.2/§4.3', () => {
      expect(perfHint(false)).not.toMatch(/only trims what.s shown/i);
    });

    it('does not fold in HIDDEN_CLASS_TRUTH — that sentence has exactly one home per surface (the Classes section), and this hint renders alongside it (docs/plans/done/CV-PANEL-SPLIT-PLAN.md P2 §2/§4)', () => {
      expect(perfHint(false)).not.toContain(HIDDEN_CLASS_TRUTH);
      expect(perfHint(true)).not.toContain(HIDDEN_CLASS_TRUTH);
    });
  });

  // HIDDEN_CLASS_TRUTH's own content is covered by `core/detections/detections-logic.spec.ts` —
  // this file only asserts `perfHint` never duplicates it (above). FIRST_HIDE_HINT is gone as of
  // wave W5 (docs/plans/done/CV-CLEAN-FEED-PLAN.md D-3) along with the allowlist-complement toggle
  // it explained — see `cv-control-panel-logic.ts`'s own "Staged class-filter selection" comment.

  describe('intentCardSentence', () => {
    it('names what a general-purpose closed-set model finds', () => {
      expect(intentCardSentence('general')).toMatch(/people/i);
    });

    it('names what a specialized model finds', () => {
      expect(intentCardSentence('specialized')).toMatch(/military/i);
    });

    it('names what the open-vocab model finds', () => {
      expect(intentCardSentence('open-vocab')).toMatch(/anything/i);
    });

    it('degrades to null (no fabricated description) for a kind this app does not recognize', () => {
      expect(intentCardSentence('mystery-kind')).toBeNull();
    });
  });

  describe('stagedLabelSeed', () => {
    it('returns the staged selection unchanged once one exists, ignoring the applied filter', () => {
      expect(stagedLabelSeed(['car'], ['person'])).toEqual(['car']);
    });

    it('returns [] as the staged selection when that is what was explicitly staged, not falling back to effective', () => {
      expect(stagedLabelSeed([], ['person'])).toEqual([]);
    });

    it('falls back to the applied filter when nothing is staged yet (null)', () => {
      expect(stagedLabelSeed(null, ['person', 'car'])).toEqual(['person', 'car']);
    });
  });

  describe('submitLabelFilterButtonText', () => {
    it('states the empty-selection consequence honestly rather than reading as a no-op', () => {
      expect(submitLabelFilterButtonText([])).toBe('Apply — show every class');
    });

    it('names the singular count', () => {
      expect(submitLabelFilterButtonText(['person'])).toBe('Apply 1 class');
    });

    it('names the plural count', () => {
      expect(submitLabelFilterButtonText(['person', 'car'])).toBe('Apply 2 classes');
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

    it('buildReleaseLockPatch returns the stream to ASSOCIATE with the release (W7 live find: FOLLOW without a lock has no track identities, stranding every re-lock gesture)', () => {
      expect(buildReleaseLockPatch()).toEqual({ tracking: { mode: 'ASSOCIATE', lock: { release: true } } });
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

  // --- Seen-now chips (docs/plans/done/CV-UX-RESEARCH.md §4.1, wave U4) --------------------------

  describe('recentObservedLabels', () => {
    it('collects distinct labels across results, capped', () => {
      const results = [detectionResult(['person', 'car']), detectionResult(['car', 'building'])];
      expect(recentObservedLabels(results, 2)).toEqual(['person', 'car']);
    });

    it('defaults to SEEN_NOW_CHIP_CAP when no cap is given', () => {
      const labels = Array.from({ length: SEEN_NOW_CHIP_CAP + 5 }, (_, i) => `label-${i}`);
      expect(recentObservedLabels([detectionResult(labels)])).toHaveLength(SEEN_NOW_CHIP_CAP);
    });

    it('returns [] for no results', () => {
      expect(recentObservedLabels([])).toEqual([]);
    });

    it('returns fewer than the cap when there simply aren\'t that many distinct labels', () => {
      expect(recentObservedLabels([detectionResult(['person'])], 8)).toEqual(['person']);
    });
  });

  // --- Classes on screen right now (docs/plans/done/CV-UX-RESEARCH.md §3's status line) ---------

  describe('classesOnScreenCount', () => {
    it('counts distinct labels in the most recent result alone', () => {
      expect(classesOnScreenCount(detectionResult(['person', 'car', 'person']), [])).toBe(2);
    });

    it('only counts labels that pass the filter', () => {
      expect(classesOnScreenCount(detectionResult(['person', 'car']), ['person'])).toBe(1);
    });

    it('is 0 for no result yet', () => {
      expect(classesOnScreenCount(undefined, [])).toBe(0);
    });
  });

  // --- Detection status (docs/plans/done/CV-PANEL-SPLIT-PLAN.md P2 §1, CV-UX-RESEARCH.md
  // §1.2/§3/§9.2) --------------------------------------------------------------------------------

  function rate(partial: Partial<DetectionRate> = {}): DetectionRate {
    return {
      windowSeconds: 30,
      sourceFps: 10,
      targetFps: 10,
      demandFps: 0,
      submittedFps: 9.9,
      submitted: 297,
      droppedInFlight: 0,
      droppedOutage: 0,
      missedDeadlines: 0,
      dropRatio: 0,
      transport: 'push',
      decodeMillisP50: 0,
      ...partial,
    };
  }

  describe('formatMeasuredRate', () => {
    it('formats a genuine positive reading, one decimal only when not whole', () => {
      expect(formatMeasuredRate(9.94)).toBe('9.9/s measured');
      expect(formatMeasuredRate(10)).toBe('10/s measured');
    });
  });

  describe('detectionStatus', () => {
    it('the operator\'s own off choice always wins, regardless of every other signal, with the plan\'s own exact wording', () => {
      const status = detectionStatus(false, true, 'RUNNING', rate(), 3);
      expect(status.kind).toBe('off');
      expect(status.text).toBe('Off — zero CPU. Video unaffected.');
    });

    it('reports waiting-to-start when enabled but no stream is running yet', () => {
      const status = detectionStatus(true, false, undefined, undefined, 0);
      expect(status.kind).toBe('waiting-to-start');
      expect(status.text).toMatch(/once a stream is running/i);
    });

    it('reports waiting-for-viewer as explicitly not a fault, per DetectionState\'s own contract', () => {
      const status = detectionStatus(true, true, 'IDLE_NO_VIEWERS', undefined, 0);
      expect(status.kind).toBe('waiting-for-viewer');
      expect(status.text).toMatch(/no cost while idle/i);
    });

    it('reports the measured rate and classes-on-screen count while running, matching the plan\'s own mockup format', () => {
      const status = detectionStatus(true, true, 'RUNNING', rate({ submittedFps: 9.9 }), 3);
      expect(status.kind).toBe('running');
      expect(status.text).toBe('9.9/s measured · 3 classes on screen');
    });

    it('running with exactly one class on screen is singular, not "1 classes"', () => {
      expect(detectionStatus(true, true, 'RUNNING', rate({ submittedFps: 5 }), 1).text).toContain('1 class on screen');
    });

    it('running with nothing on screen omits the classes clause entirely', () => {
      expect(detectionStatus(true, true, 'RUNNING', rate({ submittedFps: 5 }), 0).text).toBe('5/s measured');
    });

    it('running with no rate object yet (no sample has ever completed) never fabricates a number', () => {
      const status = detectionStatus(true, true, 'RUNNING', undefined, 0);
      expect(status.kind).toBe('running');
      expect(status.text).toContain('rate not yet measured');
      expect(status.text).not.toMatch(/\d/);
    });

    it('running with a rate object present but zero submitted in the window names a stall plainly, distinct from "not yet measured"', () => {
      const status = detectionStatus(true, true, 'RUNNING', rate({ submittedFps: 0, windowSeconds: 30 }), 0);
      expect(status.kind).toBe('stalled');
      expect(status.text).toBe('On — no detector passes in the last 30s.');
    });

    it('the stalled sentence names the actual window, never a hardcoded figure', () => {
      expect(detectionStatus(true, true, 'RUNNING', rate({ submittedFps: 0, windowSeconds: 10 }), 0).text).toContain('last 10s');
    });

    it('a negative submittedFps (defensive — should never occur on the wire) still reads as stalled, not a fabricated negative rate', () => {
      expect(detectionStatus(true, true, 'RUNNING', rate({ submittedFps: -1 }), 0).kind).toBe('stalled');
    });

    it('names a genuine sync gap rather than echoing the draft as confirmed "on"', () => {
      const status = detectionStatus(true, true, 'OFF', undefined, 0);
      expect(status.kind).toBe('unknown');
      expect(status.text).toMatch(/waiting for the server to confirm/i);
    });

    it('degrades to unknown, never a guess, when detectionState is absent (an old server)', () => {
      const status = detectionStatus(true, true, undefined, undefined, 0);
      expect(status.kind).toBe('unknown');
      expect(status.text).toMatch(/not reported/i);
    });
  });
});
