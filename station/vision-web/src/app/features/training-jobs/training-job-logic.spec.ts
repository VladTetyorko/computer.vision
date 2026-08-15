import { describe, expect, it } from 'vitest';
import {
  failureMessage,
  formatMetric,
  hasReportedProgress,
  isTerminalJobState,
  jobProgressPercent,
  jobStateLabel,
  producedModelId,
} from './training-job-logic';

describe('isTerminalJobState', () => {
  it('is false only while RUNNING', () => {
    expect(isTerminalJobState('RUNNING')).toBe(false);
    expect(isTerminalJobState('SUCCEEDED')).toBe(true);
    expect(isTerminalJobState('FAILED')).toBe(true);
  });
});

describe('jobProgressPercent', () => {
  it('is 0% for a null job (not yet loaded)', () => {
    expect(jobProgressPercent(null)).toBe(0);
  });

  it('is 0% before the first progress message (totalEpochs still 0)', () => {
    expect(jobProgressPercent({ epoch: 0, totalEpochs: 0 })).toBe(0);
  });

  it('computes the plain epoch/totalEpochs percentage', () => {
    expect(jobProgressPercent({ epoch: 5, totalEpochs: 50 })).toBe(10);
    expect(jobProgressPercent({ epoch: 25, totalEpochs: 50 })).toBe(50);
  });

  it('clamps to 100% for a stale epoch overshooting totalEpochs', () => {
    expect(jobProgressPercent({ epoch: 60, totalEpochs: 50 })).toBe(100);
  });
});

describe('hasReportedProgress', () => {
  it('is false for a null job or before any epoch has reported', () => {
    expect(hasReportedProgress(null)).toBe(false);
    expect(hasReportedProgress({ epoch: 0 })).toBe(false);
  });

  it('is true once at least one epoch has reported', () => {
    expect(hasReportedProgress({ epoch: 1 })).toBe(true);
  });
});

describe('jobStateLabel', () => {
  it('renders one human word per state', () => {
    expect(jobStateLabel('RUNNING')).toBe('Training…');
    expect(jobStateLabel('SUCCEEDED')).toBe('Trained');
    expect(jobStateLabel('FAILED')).toBe('Failed');
  });
});

describe('producedModelId', () => {
  it('is null for a null job or any non-SUCCEEDED state', () => {
    expect(producedModelId(null)).toBeNull();
    expect(producedModelId({ state: 'RUNNING', message: 'my-model' })).toBeNull();
    expect(producedModelId({ state: 'FAILED', message: 'my-model' })).toBeNull();
  });

  it('is the trimmed message on SUCCEEDED', () => {
    expect(producedModelId({ state: 'SUCCEEDED', message: ' my-model-v2 ' })).toBe('my-model-v2');
  });

  it('is null for a blank/whitespace-only message on SUCCEEDED', () => {
    expect(producedModelId({ state: 'SUCCEEDED', message: '   ' })).toBeNull();
    expect(producedModelId({ state: 'SUCCEEDED', message: '' })).toBeNull();
  });
});

describe('failureMessage', () => {
  it('is null for a null job or any non-FAILED state', () => {
    expect(failureMessage(null)).toBeNull();
    expect(failureMessage({ state: 'RUNNING', message: 'boom' })).toBeNull();
    expect(failureMessage({ state: 'SUCCEEDED', message: 'boom' })).toBeNull();
  });

  it('is the trimmed message on FAILED', () => {
    expect(failureMessage({ state: 'FAILED', message: ' dataset not found on training host ' })).toBe(
      'dataset not found on training host',
    );
  });

  it('falls back to a plain sentence for a blank message on FAILED', () => {
    expect(failureMessage({ state: 'FAILED', message: '' })).toBe('Training failed for an unknown reason.');
    expect(failureMessage({ state: 'FAILED', message: '   ' })).toBe('Training failed for an unknown reason.');
  });
});

describe('formatMetric', () => {
  it('renders 3 decimal places', () => {
    expect(formatMetric(0.45231)).toBe('0.452');
    expect(formatMetric(0.8)).toBe('0.800');
    expect(formatMetric(0)).toBe('0.000');
  });
});
