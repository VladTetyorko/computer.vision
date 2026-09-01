import { describe, expect, it } from 'vitest';
import {
  canAdministerRegistry,
  canPromoteModel,
  catalogSource,
  findLiveModel,
  formatMap50,
  hasProvenance,
  isMissingOnWorker,
  metricsKindLabel,
  modelKey,
  resolvePromoteVersion,
  rollbackConfirmMessage,
  runtimeLabel,
  sortModelsForDisplay,
  statusChipVariant,
  statusLabel,
} from './models-logic';
import type { CvModel } from '../../core/api/models';

function model(overrides: Partial<CvModel> = {}): CvModel {
  return {
    id: 'yolo26n.pt',
    displayName: 'People & vehicles',
    kind: 'general',
    openVocab: false,
    defaultLabelFilter: [],
    ...overrides,
  };
}

describe('modelKey', () => {
  it('composes id and version', () => {
    expect(modelKey({ id: 'yolo26n.pt', version: 'v2' })).toBe('yolo26n.pt@v2');
  });

  it('falls back to an empty version segment when absent', () => {
    expect(modelKey({ id: 'yolo26n.pt', version: undefined })).toBe('yolo26n.pt@');
  });
});

describe('canAdministerRegistry', () => {
  it('is true only for ADMIN', () => {
    expect(canAdministerRegistry('ADMIN')).toBe(true);
    expect(canAdministerRegistry('MANAGER')).toBe(false);
    expect(canAdministerRegistry('PILOT')).toBe(false);
  });

  it('is false for a missing role', () => {
    expect(canAdministerRegistry(undefined)).toBe(false);
    expect(canAdministerRegistry(null)).toBe(false);
  });
});

describe('canPromoteModel', () => {
  it('is true for a non-live model, an administrator, and no in-flight promotion', () => {
    expect(canPromoteModel(model({ status: 'DRAFT' }), true, null)).toBe(true);
  });

  it('is false for a non-administrator', () => {
    expect(canPromoteModel(model({ status: 'DRAFT' }), false, null)).toBe(false);
  });

  it('is false for the already-live model — nothing to promote it to', () => {
    expect(canPromoteModel(model({ status: 'LIVE' }), true, null)).toBe(false);
  });

  it('is false while any row is being promoted, even a different one', () => {
    expect(canPromoteModel(model({ status: 'DRAFT' }), true, 'some-other-model.pt@v1')).toBe(false);
  });
});

describe('resolvePromoteVersion', () => {
  it('falls back to "latest" for an absent version', () => {
    expect(resolvePromoteVersion(model({ version: undefined }))).toBe('latest');
  });

  it('falls back to "latest" for a blank/whitespace-only version', () => {
    expect(resolvePromoteVersion(model({ version: '' }))).toBe('latest');
    expect(resolvePromoteVersion(model({ version: '   ' }))).toBe('latest');
  });

  it('sends a real, non-blank version verbatim, trimmed', () => {
    expect(resolvePromoteVersion(model({ version: ' v2 ' }))).toBe('v2');
  });
});

describe('sortModelsForDisplay', () => {
  it('orders LIVE, then CANDIDATE, then DRAFT, then RETIRED', () => {
    const models = [
      model({ id: 'a', status: 'RETIRED' }),
      model({ id: 'b', status: 'LIVE' }),
      model({ id: 'c', status: 'DRAFT' }),
      model({ id: 'd', status: 'CANDIDATE' }),
    ];
    expect(sortModelsForDisplay(models).map((m) => m.id)).toEqual(['b', 'd', 'c', 'a']);
  });

  it('breaks ties within a status alphabetically by id', () => {
    const models = [model({ id: 'zeta', status: 'DRAFT' }), model({ id: 'alpha', status: 'DRAFT' })];
    expect(sortModelsForDisplay(models).map((m) => m.id)).toEqual(['alpha', 'zeta']);
  });

  it('breaks a same-id tie by version', () => {
    const models = [
      model({ id: 'yolo26n.pt', version: 'v2', status: 'RETIRED' }),
      model({ id: 'yolo26n.pt', version: 'v1', status: 'RETIRED' }),
    ];
    expect(sortModelsForDisplay(models).map((m) => m.version)).toEqual(['v1', 'v2']);
  });

  it('places a status-less row last, alongside RETIRED', () => {
    const models = [model({ id: 'a', status: undefined }), model({ id: 'b', status: 'DRAFT' })];
    expect(sortModelsForDisplay(models).map((m) => m.id)).toEqual(['b', 'a']);
  });

  it('does not mutate its input', () => {
    const models = [model({ id: 'b', status: 'DRAFT' }), model({ id: 'a', status: 'DRAFT' })];
    const copy = [...models];
    sortModelsForDisplay(models);
    expect(models).toEqual(copy);
  });
});

describe('statusLabel', () => {
  it('renders one human word per status', () => {
    expect(statusLabel('LIVE')).toBe('Live');
    expect(statusLabel('CANDIDATE')).toBe('Candidate');
    expect(statusLabel('DRAFT')).toBe('Draft');
    expect(statusLabel('RETIRED')).toBe('Retired');
  });

  it('reads a missing status as Unknown, never blank', () => {
    expect(statusLabel(undefined)).toBe('Unknown');
  });
});

describe('statusChipVariant', () => {
  it('is ok for LIVE and accent for CANDIDATE', () => {
    expect(statusChipVariant('LIVE')).toBe('ok');
    expect(statusChipVariant('CANDIDATE')).toBe('accent');
  });

  it('is plain (neutral) for DRAFT/RETIRED/missing — neither is a failure', () => {
    expect(statusChipVariant('DRAFT')).toBe('');
    expect(statusChipVariant('RETIRED')).toBe('');
    expect(statusChipVariant(undefined)).toBe('');
  });
});

describe('runtimeLabel', () => {
  it('renders the two real runtimes', () => {
    expect(runtimeLabel('PYTORCH')).toBe('PyTorch');
    expect(runtimeLabel('OPENVINO')).toBe('OpenVINO');
  });

  it('renders a dash for an absent runtime, never a guess', () => {
    expect(runtimeLabel(undefined)).toBe('—');
  });
});

describe('isMissingOnWorker', () => {
  it('is true only for the MISSING wire value', () => {
    expect(isMissingOnWorker({ availability: 'MISSING' })).toBe(true);
    expect(isMissingOnWorker({ availability: 'PRESENT' })).toBe(false);
    expect(isMissingOnWorker({ availability: undefined })).toBe(false);
  });
});

describe('metricsKindLabel', () => {
  it('labels a training-time metric honestly, never as an evaluation', () => {
    expect(metricsKindLabel('TRAINING')).toBe('Training mAP50');
  });

  it('labels every other/absent kind without claiming a source', () => {
    expect(metricsKindLabel('HELDOUT')).toBe('Held-out mAP50');
    expect(metricsKindLabel(undefined)).toBe('mAP50 (unlabelled)');
  });
});

describe('formatMap50', () => {
  it('renders 3 decimal places for a real number', () => {
    expect(formatMap50(0.45231)).toBe('0.452');
    expect(formatMap50(0)).toBe('0.000');
  });

  it('renders a dash for a missing figure, never a fabricated 0.000', () => {
    expect(formatMap50(null)).toBe('—');
    expect(formatMap50(undefined)).toBe('—');
  });
});

describe('hasProvenance', () => {
  it('is false for an absent provenance object', () => {
    expect(hasProvenance(undefined)).toBe(false);
  });

  it('is false when every field is null (ModelProvenance.none())', () => {
    expect(
      hasProvenance({ datasetId: null, trainingRunId: null, baseModel: null, epochs: null, trainedAt: null }),
    ).toBe(false);
  });

  it('is true once a dataset or run is named', () => {
    expect(
      hasProvenance({ datasetId: 'd-1', trainingRunId: null, baseModel: null, epochs: null, trainedAt: null }),
    ).toBe(true);
    expect(
      hasProvenance({ datasetId: null, trainingRunId: 'r-1', baseModel: null, epochs: null, trainedAt: null }),
    ).toBe(true);
  });
});

describe('catalogSource', () => {
  it('is registry when any row is tagged registry', () => {
    expect(catalogSource([model({ source: 'config' }), model({ source: 'registry' })])).toBe('registry');
  });

  it('is config when every row is config or unlabelled', () => {
    expect(catalogSource([model({ source: 'config' }), model({ source: undefined })])).toBe('config');
  });

  it('is config for an empty roster — never fabricates a registry claim', () => {
    expect(catalogSource([])).toBe('config');
  });
});

describe('findLiveModel', () => {
  it('finds the one LIVE row', () => {
    const models = [model({ id: 'a', status: 'DRAFT' }), model({ id: 'b', status: 'LIVE' })];
    expect(findLiveModel(models)?.id).toBe('b');
  });

  it('is undefined when nothing is live', () => {
    expect(findLiveModel([model({ status: 'DRAFT' })])).toBeUndefined();
  });
});

describe('rollbackConfirmMessage', () => {
  it('names the model that will be retired, with its version', () => {
    expect(rollbackConfirmMessage(model({ id: 'yolo26n.pt', version: 'v2', status: 'LIVE' }))).toBe(
      'Roll back the live model? "yolo26n.pt" (v2) will be retired, and whichever model it most ' +
        'recently replaced will become live again.',
    );
  });

  it('omits the version parenthetical when none is reported', () => {
    expect(rollbackConfirmMessage(model({ id: 'yolo26n.pt', version: undefined, status: 'LIVE' }))).toBe(
      'Roll back the live model? "yolo26n.pt" will be retired, and whichever model it most recently ' +
        'replaced will become live again.',
    );
  });

  it('degrades honestly when nothing is currently live', () => {
    expect(rollbackConfirmMessage(undefined)).toBe(
      'Roll back the live model? This restores whichever model was most recently promoted over.',
    );
  });
});
