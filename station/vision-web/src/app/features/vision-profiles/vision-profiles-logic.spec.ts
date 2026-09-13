import { describe, expect, it } from 'vitest';
import type { CvCoverageRow, CvProfile } from '../../core/api/models';
import {
  applyIntentToDraft,
  bindingSummaryLabel,
  canDeleteProfile,
  canEditProfile,
  canForkProfile,
  coverageRowClearTarget,
  CV_DETECTION_POLICY_ATTRIBUTE_KEY,
  describeCoverageFilters,
  describeCoverageSource,
  describeDetectionCardState,
  describeIntent,
  describeOptionalModel,
  describeOptionalNumber,
  detectionSelectValue,
  DETECTION_SELECT_INHERIT,
  DETECTION_SELECT_OFF,
  DETECTION_SELECT_ON,
  draftFromProfile,
  draftToRequest,
  emptyProfileDraft,
  engineSelectValue,
  ENGINE_SELECT_DEPLOYMENT_DEFAULT,
  ENGINE_SELECT_INHERIT,
  forkDraftFromProfile,
  forkedProfileName,
  formatLabelList,
  isDetectionAlways,
  isModelMissingOnWorker,
  parseDetectionSelectValue,
  parseEngineSelectValue,
  parseLabelList,
  parseTrackingModeSelectValue,
  primaryGroupId,
  saveOutcomeMessage,
  sortProfilesForDisplay,
  summarizeProfileBindings,
  trackingModeSelectValue,
  TRACKING_MODE_SELECT_INHERIT,
  validateDraft,
  withDetectionPolicy,
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
    sources: {},
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

describe('profile-card display (wave W7 — an unset knob reads "Inherited", never a fabricated value)', () => {
  it('describeOptionalModel', () => {
    expect(describeOptionalModel('yolo26n.pt')).toBe('yolo26n.pt');
    expect(describeOptionalModel(undefined)).toBe('Inherited');
    expect(describeOptionalModel('')).toBe('Inherited');
  });

  it('describeOptionalNumber', () => {
    expect(describeOptionalNumber(0.35)).toBe('0.35');
    expect(describeOptionalNumber(0)).toBe('0');
    expect(describeOptionalNumber(undefined)).toBe('Inherited');
  });

  it('describeDetectionCardState', () => {
    expect(describeDetectionCardState(true)).toBe('ON');
    expect(describeDetectionCardState(false)).toBe('OFF');
    expect(describeDetectionCardState(undefined)).toBe('INHERITED');
  });
});

describe('tri-state <select> encodings (wave W7, decision E22)', () => {
  it('trackingModeSelectValue/parseTrackingModeSelectValue round-trip, undefined <-> the inherit sentinel', () => {
    expect(trackingModeSelectValue(undefined)).toBe(TRACKING_MODE_SELECT_INHERIT);
    expect(trackingModeSelectValue('FOLLOW')).toBe('FOLLOW');
    expect(parseTrackingModeSelectValue(TRACKING_MODE_SELECT_INHERIT)).toBeUndefined();
    expect(parseTrackingModeSelectValue('ASSOCIATE')).toBe('ASSOCIATE');
  });

  it('engineSelectValue/parseEngineSelectValue distinguish inherit from the real, explicit "" default', () => {
    expect(engineSelectValue(undefined)).toBe(ENGINE_SELECT_INHERIT);
    expect(engineSelectValue('')).toBe(ENGINE_SELECT_DEPLOYMENT_DEFAULT);
    expect(engineSelectValue('bytetrack')).toBe('bytetrack');
    expect(parseEngineSelectValue(ENGINE_SELECT_INHERIT)).toBeUndefined();
    expect(parseEngineSelectValue(ENGINE_SELECT_DEPLOYMENT_DEFAULT)).toBe('');
    expect(parseEngineSelectValue('bytetrack')).toBe('bytetrack');
  });

  it('detectionSelectValue/parseDetectionSelectValue cover Inherit/On/Off', () => {
    expect(detectionSelectValue(undefined)).toBe(DETECTION_SELECT_INHERIT);
    expect(detectionSelectValue(true)).toBe(DETECTION_SELECT_ON);
    expect(detectionSelectValue(false)).toBe(DETECTION_SELECT_OFF);
    expect(parseDetectionSelectValue(DETECTION_SELECT_INHERIT)).toBeUndefined();
    expect(parseDetectionSelectValue(DETECTION_SELECT_ON)).toBe(true);
    expect(parseDetectionSelectValue(DETECTION_SELECT_OFF)).toBe(false);
  });
});

describe('draft construction', () => {
  it('emptyProfileDraft starts every knob unset/inherited — a safe no-op patch, decision E22', () => {
    const draft = emptyProfileDraft();
    expect(draft.sourceId).toBeNull();
    expect(draft.name).toBe('');
    expect(draft.model).toBe('');
    expect(draft.confidenceThreshold).toBeUndefined();
    expect(draft.inferenceFps).toBeUndefined();
    expect(draft.labelFilterInherit).toBe(true);
    expect(draft.labelDenyFilterInherit).toBe(true);
    expect(draft.detectionEnabled).toBeUndefined();
    expect(draft.trackingMode).toBeUndefined();
    expect(draft.trackingEngineId).toBeUndefined();
    expect(draft.trackingCapabilityLevel).toBeUndefined();
    expect(draft.trackingVerifyEveryMillis).toBeUndefined();
    expect(draft.trackingFollowFps).toBeUndefined();
    expect(draft.existingEventRule).toBeNull();
    expect(draft.intent).toBe('');
  });

  it('draftFromProfile carries sourceId and formats the label lists', () => {
    const draft = draftFromProfile(profile({ labelFilter: ['person'], labelDenyFilter: ['tree', 'sky'] }));
    expect(draft.sourceId).toBe('p1');
    expect(draft.labelFilterText).toBe('person');
    expect(draft.labelFilterInherit).toBe(false);
    expect(draft.labelDenyFilterText).toBe('tree, sky');
    expect(draft.labelDenyFilterInherit).toBe(false);
    expect(draft.existingEventRule).toEqual(profile().eventRule);
  });

  it('draftFromProfile round-trips a fully-specified profile with every knob explicit', () => {
    const draft = draftFromProfile(profile());
    expect(draft.model).toBe('yolo26n.pt');
    expect(draft.confidenceThreshold).toBe(0.35);
    expect(draft.inferenceFps).toBe(2);
    expect(draft.detectionEnabled).toBe(true);
    expect(draft.trackingMode).toBe('ASSOCIATE');
    expect(draft.trackingEngineId).toBe('');
    expect(draft.trackingCapabilityLevel).toBe(0);
    expect(draft.trackingVerifyEveryMillis).toBe(2000);
    expect(draft.trackingFollowFps).toBe(15);
  });

  it('draftFromProfile shows every unset knob as inherited, never a fabricated concrete value', () => {
    const draft = draftFromProfile(
      profile({
        model: undefined,
        confidenceThreshold: undefined,
        inferenceFps: undefined,
        labelFilter: undefined,
        labelDenyFilter: undefined,
        detectionEnabled: undefined,
        tracking: undefined,
        eventRule: undefined,
      }),
    );
    expect(draft.model).toBe('');
    expect(draft.confidenceThreshold).toBeUndefined();
    expect(draft.inferenceFps).toBeUndefined();
    expect(draft.labelFilterText).toBe('');
    expect(draft.labelFilterInherit).toBe(true);
    expect(draft.labelDenyFilterText).toBe('');
    expect(draft.labelDenyFilterInherit).toBe(true);
    expect(draft.detectionEnabled).toBeUndefined();
    expect(draft.trackingMode).toBeUndefined();
    expect(draft.trackingEngineId).toBeUndefined();
    expect(draft.trackingCapabilityLevel).toBeUndefined();
    expect(draft.trackingVerifyEveryMillis).toBeUndefined();
    expect(draft.trackingFollowFps).toBeUndefined();
    expect(draft.existingEventRule).toBeNull();
  });

  it('draftFromProfile round-trips a tracking group that sets only ONE of its five knobs', () => {
    const draft = draftFromProfile(profile({ tracking: { mode: 'FOLLOW' } }));
    expect(draft.trackingMode).toBe('FOLLOW');
    expect(draft.trackingEngineId).toBeUndefined();
    expect(draft.trackingCapabilityLevel).toBeUndefined();
    expect(draft.trackingVerifyEveryMillis).toBeUndefined();
    expect(draft.trackingFollowFps).toBeUndefined();
  });

  it('draftFromProfile never infers an intent from the loaded profile — provenance is not the point here', () => {
    const draft = draftFromProfile(profile({ sources: { model: 'INTENT', labelFilter: 'INTENT' } }));
    expect(draft.intent).toBe('');
  });

  it('forkDraftFromProfile clears sourceId and de-dupes the name, and also starts with no intent', () => {
    const draft = forkDraftFromProfile(profile({ name: 'mast-cams' }), ['Copy of mast-cams']);
    expect(draft.sourceId).toBeNull();
    expect(draft.name).toBe('Copy of mast-cams (2)');
    expect(draft.intent).toBe('');
  });
});

describe('applyIntentToDraft', () => {
  it('blanks a still-seeded model on the "no intent" → "an intent" transition', () => {
    const draft = { ...emptyProfileDraft(), model: 'yolo26n.pt' };
    const next = applyIntentToDraft(draft, 'VEHICLES');
    expect(next.intent).toBe('VEHICLES');
    expect(next.model).toBe('');
  });

  it('leaves an already-blank model blank across an intent → a different intent transition', () => {
    const draft = applyIntentToDraft(emptyProfileDraft(), 'PEOPLE');
    const next = applyIntentToDraft(draft, 'CUSTOM');
    expect(next.model).toBe('');
  });

  it('never re-blanks a model the operator explicitly chose after picking an intent', () => {
    const withIntent = applyIntentToDraft(emptyProfileDraft(), 'PEOPLE');
    const overridden = { ...withIntent, model: 'yolo26n-seg.pt' };
    const nextIntent = applyIntentToDraft(overridden, 'VEHICLES');
    expect(nextIntent.model).toBe('yolo26n-seg.pt');
  });

  it('reverting to no intent leaves model exactly as it is (blank or not)', () => {
    const withIntent = applyIntentToDraft(emptyProfileDraft(), 'PEOPLE');
    const reverted = applyIntentToDraft(withIntent, '');
    expect(reverted.intent).toBe('');
    expect(reverted.model).toBe('');
  });
});

describe('validateDraft', () => {
  it('accepts a well-formed, fully-inherited draft — a no-op patch is valid', () => {
    const draft = { ...emptyProfileDraft(), name: 'mast-cams' };
    expect(validateDraft(draft)).toEqual([]);
  });

  it('requires a name; a blank/unset model is always valid (decision E22 — no model requirement any more)', () => {
    const errors = validateDraft({ ...emptyProfileDraft(), name: '' });
    expect(errors).toContain('Name is required.');
    expect(errors).not.toContain('Choose a model.');
  });

  it('rejects an out-of-range confidence threshold and sub-1 fps when they are SET', () => {
    const errors = validateDraft({
      ...emptyProfileDraft(),
      name: 'x',
      confidenceThreshold: 1.5,
      inferenceFps: 0,
    });
    expect(errors).toContain('Confidence threshold must be between 0 and 1.');
    expect(errors).toContain('Inference rate must be at least 1 fps.');
  });

  it('an unset confidence threshold / inference fps is never validated — nothing to be wrong with inherit', () => {
    const draft = { ...emptyProfileDraft(), name: 'x' };
    expect(validateDraft(draft)).toEqual([]);
  });

  it('only checks tracking fields when tracking mode is set and not OFF', () => {
    const inheritedMode = { ...emptyProfileDraft(), name: 'x', trackingCapabilityLevel: -1 };
    expect(validateDraft(inheritedMode)).toEqual([]);

    const offDraft = { ...inheritedMode, trackingMode: 'OFF' as const };
    expect(validateDraft(offDraft)).toEqual([]);

    const onDraft = { ...inheritedMode, trackingMode: 'ASSOCIATE' as const };
    expect(validateDraft(onDraft)).toContain('Tracking capability level cannot be negative.');
  });

  it('only checks an unset tracking sub-knob when it is actually set', () => {
    const draft = { ...emptyProfileDraft(), name: 'x', trackingMode: 'FOLLOW' as const };
    expect(validateDraft(draft)).toEqual([]);
  });

  it('verify/follow-rate checks only apply in FOLLOW mode', () => {
    const associate = {
      ...emptyProfileDraft(),
      name: 'x',
      trackingMode: 'ASSOCIATE' as const,
      trackingVerifyEveryMillis: -1,
      trackingFollowFps: -1,
    };
    expect(validateDraft(associate)).toEqual([]);

    const follow = { ...associate, trackingMode: 'FOLLOW' as const };
    expect(validateDraft(follow)).toContain('Verify interval cannot be negative.');
    expect(validateDraft(follow)).toContain('Follow rate cannot be negative.');
  });

  it('a blank model is valid while an intent is chosen — the fold resolves it later', () => {
    const draft = applyIntentToDraft({ ...emptyProfileDraft(), name: 'x' }, 'VEHICLES');
    expect(draft.model).toBe('');
    expect(validateDraft(draft)).not.toContain('Choose a model.');
  });

  it('CUSTOM intent needs at least one class in the label filter — inherited counts as none', () => {
    const inherited = { ...emptyProfileDraft(), name: 'x', intent: 'CUSTOM' as const };
    expect(validateDraft(inherited)).toContain('Custom intent needs at least one class in the label filter.');

    const explicitEmpty = { ...inherited, labelFilterInherit: false, labelFilterText: '' };
    expect(validateDraft(explicitEmpty)).toContain('Custom intent needs at least one class in the label filter.');

    const withClasses = { ...inherited, labelFilterInherit: false, labelFilterText: 'person' };
    expect(validateDraft(withClasses)).not.toContain('Custom intent needs at least one class in the label filter.');
  });
});

describe('draftToRequest', () => {
  it('trims name/description and omits every unset knob — a fully-inherited draft sends almost nothing', () => {
    const draft = { ...emptyProfileDraft(), name: '  mast-cams  ', description: '  low rate  ' };
    const request = draftToRequest(draft);
    expect(request.name).toBe('mast-cams');
    expect(request.description).toBe('low rate');
    expect(request.model).toBeUndefined();
    expect(request.confidenceThreshold).toBeUndefined();
    expect(request.inferenceFps).toBeUndefined();
    expect(request.labelFilter).toBeUndefined();
    expect(request.labelDenyFilter).toBeUndefined();
    expect(request.detectionEnabled).toBeUndefined();
    expect(request.tracking).toBeUndefined();
    expect((request as unknown as Record<string, unknown>)['eventRule']).toBeUndefined();
  });

  it('sends parsed label lists only when their own inherit flag is off, regardless of leftover text', () => {
    const draft = {
      ...emptyProfileDraft(),
      name: 'x',
      labelFilterText: 'person, car',
      labelFilterInherit: false,
      labelDenyFilterText: 'tree',
      labelDenyFilterInherit: true,
    };
    const request = draftToRequest(draft);
    expect(request.labelFilter).toEqual(['person', 'car']);
    expect(request.labelDenyFilter).toBeUndefined();
  });

  it('an explicitly-emptied, non-inherited label filter still sends the real "keep everything" []', () => {
    const draft = { ...emptyProfileDraft(), name: 'x', labelFilterInherit: false, labelFilterText: '' };
    expect(draftToRequest(draft).labelFilter).toEqual([]);
  });

  it('builds a tracking patch carrying only the knobs the draft actually sets', () => {
    const draft = { ...emptyProfileDraft(), name: 'x', trackingMode: 'ASSOCIATE' as const };
    expect(draftToRequest(draft).tracking).toEqual({ mode: 'ASSOCIATE' });
  });

  it('a fully-specified tracking group round-trips all five knobs', () => {
    const draft = {
      ...emptyProfileDraft(),
      name: 'x',
      trackingMode: 'FOLLOW' as const,
      trackingEngineId: '',
      trackingCapabilityLevel: 0,
      trackingVerifyEveryMillis: 2000,
      trackingFollowFps: 15,
    };
    expect(draftToRequest(draft).tracking).toEqual({
      mode: 'FOLLOW',
      engineId: '',
      capabilityLevel: 0,
      verifyEveryMillis: 2000,
      followFps: 15,
    });
  });

  it('omits intent entirely when the draft has none — "skip intent resolution" per CvProfileRequest#intent', () => {
    const request = draftToRequest({ ...emptyProfileDraft(), name: 'x' });
    expect(request.intent).toBeUndefined();
  });

  it('carries the chosen intent, and the blanked model that comes with it, straight through', () => {
    const draft = applyIntentToDraft({ ...emptyProfileDraft(), name: 'x' }, 'VEHICLES');
    const request = draftToRequest(draft);
    expect(request.intent).toBe('VEHICLES');
    expect(request.model).toBeUndefined();
  });
});

describe('saveOutcomeMessage', () => {
  it('is the plain saved/created message when no intent was chosen', () => {
    expect(saveOutcomeMessage(profile({ name: 'mast-cams' }), '', true)).toBe('"mast-cams" saved.');
    expect(saveOutcomeMessage(profile({ name: 'mast-cams' }), '', false)).toBe('"mast-cams" created.');
  });

  it('is the plain message when an intent was chosen but sources reports nothing deferred', () => {
    expect(saveOutcomeMessage(profile({ name: 'mast-cams', sources: {} }), 'VEHICLES', true)).toBe('"mast-cams" saved.');
  });

  it('names the one knob left to the intent — present-tense, not "resolved" (nothing concrete happened yet)', () => {
    const saved = profile({ name: 'mast-cams', sources: { model: 'INTENT' } });
    expect(saveOutcomeMessage(saved, 'VEHICLES', true)).toBe('"mast-cams" saved — model left to your Vehicles intent.');
  });

  it('names both knobs left to the intent', () => {
    const saved = profile({ name: 'mast-cams', sources: { model: 'INTENT', labelFilter: 'INTENT' } });
    expect(saveOutcomeMessage(saved, 'PEOPLE', false)).toBe(
      '"mast-cams" created — model and label filter left to your People intent.',
    );
  });
});

describe('describeIntent', () => {
  it('labels every intent', () => {
    expect(describeIntent('PEOPLE')).toBe('People');
    expect(describeIntent('VEHICLES')).toBe('Vehicles');
    expect(describeIntent('EVERYTHING')).toBe('Everything');
    expect(describeIntent('CUSTOM')).toBe('Custom');
  });
});

describe('detection policy (D7)', () => {
  it('isDetectionAlways reads only the exact, case-insensitive, trimmed "always" value', () => {
    expect(isDetectionAlways({ [CV_DETECTION_POLICY_ATTRIBUTE_KEY]: 'always' })).toBe(true);
    expect(isDetectionAlways({ [CV_DETECTION_POLICY_ATTRIBUTE_KEY]: ' ALWAYS ' })).toBe(true);
    expect(isDetectionAlways({ [CV_DETECTION_POLICY_ATTRIBUTE_KEY]: 'on-view' })).toBe(false);
    expect(isDetectionAlways({ [CV_DETECTION_POLICY_ATTRIBUTE_KEY]: 'garbage' })).toBe(false);
    expect(isDetectionAlways({})).toBe(false);
  });

  it('withDetectionPolicy merges the canonical lowercase value in, never replacing the rest of the map', () => {
    const attributes = { registrationNumber: 'N123', [CV_DETECTION_POLICY_ATTRIBUTE_KEY]: 'on-view' };
    expect(withDetectionPolicy(attributes, true)).toEqual({
      registrationNumber: 'N123',
      [CV_DETECTION_POLICY_ATTRIBUTE_KEY]: 'always',
    });
    expect(withDetectionPolicy(attributes, false)).toEqual({
      registrationNumber: 'N123',
      [CV_DETECTION_POLICY_ATTRIBUTE_KEY]: 'on-view',
    });
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
