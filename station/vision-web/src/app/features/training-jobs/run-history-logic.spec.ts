import { describe, expect, it } from 'vitest';
import {
  formatRunMetric,
  hasProducedModel,
  runDatasetLabel,
  runProgressLabel,
  runStateLabel,
  sortRunsNewestFirst,
} from './run-history-logic';
import type { TrainingRun } from '../../core/api/models';

function run(overrides: Partial<TrainingRun> = {}): TrainingRun {
  return {
    runId: 'run-1',
    datasetId: 'dataset-1',
    datasetName: 'Buildings — site A',
    baseModel: 'yolov8n',
    epochs: 50,
    state: 'SUCCEEDED',
    epoch: 50,
    totalEpochs: 50,
    loss: 0.123,
    map50: 0.876,
    outputModelId: 'model-1',
    startedAt: '2026-08-20T12:00:00Z',
    finishedAt: '2026-08-20T13:00:00Z',
    startedBy: 'alice',
    ...overrides,
  };
}

describe('sortRunsNewestFirst', () => {
  it('orders by startedAt descending', () => {
    const older = run({ runId: 'a', startedAt: '2026-08-01T00:00:00Z' });
    const newer = run({ runId: 'b', startedAt: '2026-08-20T00:00:00Z' });
    const middle = run({ runId: 'c', startedAt: '2026-08-10T00:00:00Z' });
    expect(sortRunsNewestFirst([older, newer, middle]).map((r) => r.runId)).toEqual(['b', 'c', 'a']);
  });

  it('does not mutate the input array', () => {
    const list = [run({ runId: 'a', startedAt: '2026-08-01T00:00:00Z' }), run({ runId: 'b', startedAt: '2026-08-20T00:00:00Z' })];
    const original = [...list];
    sortRunsNewestFirst(list);
    expect(list).toEqual(original);
  });
});

describe('runStateLabel', () => {
  it('renders one human word per state', () => {
    expect(runStateLabel('RUNNING')).toBe('Training…');
    expect(runStateLabel('SUCCEEDED')).toBe('Trained');
    expect(runStateLabel('FAILED')).toBe('Failed');
  });
});

describe('runProgressLabel', () => {
  it('renders "epoch / totalEpochs"', () => {
    expect(runProgressLabel({ epoch: 5, totalEpochs: 50 })).toBe('5 / 50');
    expect(runProgressLabel({ epoch: 0, totalEpochs: 0 })).toBe('0 / 0');
  });
});

describe('formatRunMetric', () => {
  it('renders a fixed 3-decimal reading for a reported value', () => {
    expect(formatRunMetric(0.45231)).toBe('0.452');
    expect(formatRunMetric(0)).toBe('0.000');
  });

  it('renders "—" for null (not yet reported)', () => {
    expect(formatRunMetric(null)).toBe('—');
  });
});

describe('runDatasetLabel', () => {
  it('uses the dataset name when present', () => {
    expect(runDatasetLabel({ datasetName: 'Buildings — site A', datasetId: 'abcdefgh-1234' })).toBe('Buildings — site A');
  });

  it('falls back to a short id prefix for a blank name', () => {
    expect(runDatasetLabel({ datasetName: '   ', datasetId: 'abcdefgh-1234' })).toBe('abcdefgh');
    expect(runDatasetLabel({ datasetName: '', datasetId: 'abcdefgh-1234' })).toBe('abcdefgh');
  });
});

describe('hasProducedModel', () => {
  it('is true for a non-blank outputModelId', () => {
    expect(hasProducedModel({ outputModelId: 'model-1' })).toBe(true);
  });

  it('is false for null or a blank/whitespace-only outputModelId', () => {
    expect(hasProducedModel({ outputModelId: null })).toBe(false);
    expect(hasProducedModel({ outputModelId: '' })).toBe(false);
    expect(hasProducedModel({ outputModelId: '   ' })).toBe(false);
  });
});
