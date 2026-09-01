import { describe, expect, it } from 'vitest';
import type { CvCoverageRow, CvProfile } from '../../core/api/models';
import {
  bindingSummaryLabel,
  canDeleteProfile,
  canEditProfile,
  canForkProfile,
  coverageRowClearTarget,
  describeCoverageFilters,
  describeCoverageSource,
  draftFromProfile,
  draftToRequest,
  emptyProfileDraft,
  forkDraftFromProfile,
  forkedProfileName,
  formatLabelList,
  isModelMissingOnWorker,
  parseLabelList,
  primaryGroupId,
  sortProfilesForDisplay,
  summarizeProfileBindings,
  validateDraft,
} from './vision-profiles-logic';

function profile(partial: Partial<CvProfile> = {}): CvProfile {
  return {
    id: 'p1',
    name: 'people-vehicles',
    description: 'Default',
    builtIn: true,
    model: 'yolo26n.pt',
    confidenceThreshold: 0.35,
    inferenceFps: 2,
    labelFilter: [],
    labelDenyFilter: ['tree'],
    detectionEnabled: true,
    tracking: { mode: 'ASSOCIATE', engineId: '', capabilityLevel: 0, verifyEveryMillis: 2000, followFps: 15 },
    eventRule: { labels: ['person', 'car'], confidenceThreshold: 0.5, consecutiveToOpen: 3, absenceToCloseSeconds: 5 },
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...partial,
  };
}

function coverageRow(partial: Partial<CvCoverageRow> = {}): CvCoverageRow {
  return {
    assetId: 'a1',
    assetName: 'Asset 1',
    categoryId: 'drone',
    categoryName: 'Drone',
    profileId: 'p1',
    profileName: 'people-vehicles',
    source: 'ORGANIZATION',
    detectionEnabled: true,
    model: 'yolo26n.pt',
    labelFilter: [],
    labelDenyFilter: [],
    ...partial,
  };
}

describe('sortProfilesForDisplay', () => {
  it('puts built-in profiles first, alphabetical within each group', () => {
    const profiles = [
      profile({ id: '1', name: 'wide-search', builtIn: true }),
      profile({ id: '2', name: 'mast-cams', builtIn: false, groupId: 'g1' }),
      profile({ id: '3', name: 'military-vehicles', builtIn: true }),
      profile({ id: '4', name: 'apiary-cams', builtIn: false, groupId: 'g1' }),
    ];
    expect(sortProfilesForDisplay(profiles).map((p) => p.name)).toEqual([
      'military-vehicles',
      'wide-search',
      'apiary-cams',
      'mast-cams',
    ]);
  });
});

describe('row-level gates', () => {
  it('canEditProfile: only a non-built-in profile when the caller can manage', () => {
    expect(canEditProfile(profile({ builtIn: true }), true)).toBe(false);
    expect(canEditProfile(profile({ builtIn: false }), false)).toBe(false);
    expect(canEditProfile(profile({ builtIn: false }), true)).toBe(true);
  });

  it('canForkProfile: any profile, manager only', () => {
    expect(canForkProfile(profile({ builtIn: true }), true)).toBe(true);
    expect(canForkProfile(profile({ builtIn: false }), true)).toBe(true);
    expect(canForkProfile(profile({ builtIn: true }), false)).toBe(false);
  });

  it('canDeleteProfile mirrors canEditProfile', () => {
    expect(canDeleteProfile(profile({ builtIn: true }), true)).toBe(false);
    expect(canDeleteProfile(profile({ builtIn: false }), true)).toBe(true);
  });
});

describe('forkedProfileName', () => {
  it('names the first fork "Copy of X"', () => {
    expect(forkedProfileName('mast-cams', [])).toBe('Copy of mast-cams');
  });

  it('numbers subsequent forks against the taken set, case-insensitively', () => {
    expect(forkedProfileName('mast-cams', ['Copy of mast-cams'])).toBe('Copy of mast-cams (2)');
    expect(forkedProfileName('mast-cams', ['copy of mast-cams', 'Copy of mast-cams (2)'])).toBe('Copy of mast-cams (3)');
  });
});

describe('label list parsing', () => {
  it('splits on commas and newlines, trims, drops empties, dedupes', () => {
    expect(parseLabelList('person, car\n car ,, truck')).toEqual(['person', 'car', 'truck']);
  });

  it('round-trips through formatLabelList', () => {
    expect(formatLabelList(parseLabelList('a, b, c'))).toBe('a, b, c');
  });

  it('empty input yields an empty list', () => {
    expect(parseLabelList('   ')).toEqual([]);
  });
});

describe('draft construction', () => {
  it('emptyProfileDraft seeds the given default model with sourceId null', () => {
    const draft = emptyProfileDraft('yolo26n.pt');
    expect(draft.sourceId).toBeNull();
    expect(draft.model).toBe('yolo26n.pt');
    expect(draft.name).toBe('');
    expect(draft.existingEventRule).toBeNull();
  });

  it('draftFromProfile carries sourceId and formats the label lists', () => {
    const draft = draftFromProfile(profile({ labelFilter: ['person'], labelDenyFilter: ['tree', 'sky'] }));
    expect(draft.sourceId).toBe('p1');
    expect(draft.labelFilterText).toBe('person');
    expect(draft.labelDenyFilterText).toBe('tree, sky');
    expect(draft.existingEventRule).toEqual(profile().eventRule);
  });

  it('forkDraftFromProfile clears sourceId and de-dupes the name', () => {
    const draft = forkDraftFromProfile(profile({ name: 'mast-cams' }), ['Copy of mast-cams']);
    expect(draft.sourceId).toBeNull();
    expect(draft.name).toBe('Copy of mast-cams (2)');
  });
});

describe('validateDraft', () => {
  it('accepts a well-formed draft', () => {
    const draft = { ...emptyProfileDraft('yolo26n.pt'), name: 'mast-cams' };
    expect(validateDraft(draft)).toEqual([]);
  });

  it('requires a name and a model', () => {
    const errors = validateDraft({ ...emptyProfileDraft(''), name: '' });
    expect(errors).toContain('Name is required.');
    expect(errors).toContain('Choose a model.');
  });

  it('rejects an out-of-range confidence threshold and sub-1 fps', () => {
    const errors = validateDraft({
      ...emptyProfileDraft('yolo26n.pt'),
      name: 'x',
      confidenceThreshold: 1.5,
      inferenceFps: 0,
    });
    expect(errors).toContain('Confidence threshold must be between 0 and 1.');
    expect(errors).toContain('Inference rate must be at least 1 fps.');
  });

  it('only checks tracking fields when tracking mode is not OFF', () => {
    const offDraft = { ...emptyProfileDraft('yolo26n.pt'), name: 'x', trackingCapabilityLevel: -1 };
    expect(validateDraft(offDraft)).toEqual([]);

    const onDraft = { ...offDraft, trackingMode: 'ASSOCIATE' as const };
    expect(validateDraft(onDraft)).toContain('Tracking capability level cannot be negative.');
  });
});

describe('draftToRequest', () => {
  it('builds the exact CvProfileRequest shape, trimming name/description and parsing label text', () => {
    const draft = {
      ...emptyProfileDraft('yolo26n.pt'),
      name: '  mast-cams  ',
      description: '  low rate  ',
      labelFilterText: 'person, car',
      labelDenyFilterText: 'tree',
    };
    const request = draftToRequest(draft);
    expect(request.name).toBe('mast-cams');
    expect(request.description).toBe('low rate');
    expect(request.labelFilter).toEqual(['person', 'car']);
    expect(request.labelDenyFilter).toEqual(['tree']);
    expect(request.tracking).toEqual({
      mode: 'OFF',
      engineId: '',
      capabilityLevel: 0,
      verifyEveryMillis: 2000,
      followFps: 15,
    });
    expect((request as unknown as Record<string, unknown>)['eventRule']).toBeUndefined();
  });
});

describe('isModelMissingOnWorker', () => {
  it('only an explicit MISSING renders the warning', () => {
    expect(isModelMissingOnWorker({ availability: 'MISSING' })).toBe(true);
    expect(isModelMissingOnWorker({ availability: 'PRESENT' })).toBe(false);
    expect(isModelMissingOnWorker({ availability: undefined })).toBe(false);
  });
});

describe('coverage-derived binding summaries', () => {
  it('summarizeProfileBindings groups by profile and counts by source', () => {
    const rows = [
      coverageRow({ profileId: 'p1', source: 'ORGANIZATION' }),
      coverageRow({ profileId: 'p1', source: 'ASSET', assetId: 'a2' }),
      coverageRow({ profileId: 'p2', source: 'PLATFORM', assetId: 'a3' }),
    ];
    const summary = summarizeProfileBindings(rows);
    expect(summary.get('p1')).toEqual({ profileId: 'p1', assetCount: 2, bySource: { ORGANIZATION: 1, ASSET: 1 } });
    expect(summary.get('p2')).toEqual({ profileId: 'p2', assetCount: 1, bySource: { PLATFORM: 1 } });
    expect(summary.get('p3')).toBeUndefined();
  });

  it('bindingSummaryLabel reports the honest zero case', () => {
    expect(bindingSummaryLabel(undefined)).toBe('Not currently resolving for any asset');
    expect(bindingSummaryLabel({ profileId: 'p1', assetCount: 0, bySource: {} })).toBe(
      'Not currently resolving for any asset',
    );
  });

  it('bindingSummaryLabel is a bare count for a single source', () => {
    expect(bindingSummaryLabel({ profileId: 'p1', assetCount: 5, bySource: { ORGANIZATION: 5 } })).toBe('5 assets');
  });

  it('bindingSummaryLabel breaks down a mixed-source profile, largest first', () => {
    const label = bindingSummaryLabel({ profileId: 'p1', assetCount: 5, bySource: { CATEGORY: 3, ASSET: 2 } });
    expect(label).toBe('5 assets (3 category, 2 asset override)');
  });

  it('describeCoverageFilters summarizes both lists, or reads "—" for neither', () => {
    expect(describeCoverageFilters({ labelFilter: [], labelDenyFilter: [] })).toBe('—');
    expect(describeCoverageFilters({ labelFilter: ['person'], labelDenyFilter: [] })).toBe('1 allowed');
    expect(describeCoverageFilters({ labelFilter: [], labelDenyFilter: ['tree', 'sky'] })).toBe('2 denied');
    expect(describeCoverageFilters({ labelFilter: ['person'], labelDenyFilter: ['tree'] })).toBe('1 allowed, 1 denied');
  });

  it('describeCoverageSource covers every scope plus platform', () => {
    expect(describeCoverageSource('ORGANIZATION')).toBe('Organization default');
    expect(describeCoverageSource('CATEGORY')).toBe('Category default');
    expect(describeCoverageSource('ASSET')).toBe('Asset override');
    expect(describeCoverageSource('PLATFORM')).toBe('Platform default (unbound)');
  });

  it('coverageRowClearTarget targets CATEGORY/ASSET rows and nothing else', () => {
    expect(coverageRowClearTarget(coverageRow({ source: 'CATEGORY', categoryId: 'drone' }))).toEqual({
      scopeKind: 'CATEGORY',
      scopeId: 'drone',
    });
    expect(coverageRowClearTarget(coverageRow({ source: 'ASSET', assetId: 'a1' }))).toEqual({
      scopeKind: 'ASSET',
      scopeId: 'a1',
    });
    expect(coverageRowClearTarget(coverageRow({ source: 'ORGANIZATION' }))).toBeNull();
    expect(coverageRowClearTarget(coverageRow({ source: 'PLATFORM' }))).toBeNull();
  });
});

describe('primaryGroupId', () => {
  it('reads the first membership only', () => {
    expect(primaryGroupId([{ groupId: 'g1', groupName: 'Org', role: 'ADMIN' }])).toBe('g1');
    expect(primaryGroupId([])).toBeNull();
  });
});
