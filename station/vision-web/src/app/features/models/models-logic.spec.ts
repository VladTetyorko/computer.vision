import { describe, expect, it } from 'vitest';
import { canPromoteModel, resolvePromoteVersion, sortModelsForDisplay } from './models-logic';
import type { RegisteredModel } from '../../core/api/models';

function model(overrides: Partial<RegisteredModel> = {}): RegisteredModel {
  return { id: 'yolo26n.pt', version: '', active: false, ...overrides };
}

describe('canPromoteModel', () => {
  it('is true for a non-active model, a manager, and no in-flight promotion', () => {
    expect(canPromoteModel(model({ active: false }), true, null)).toBe(true);
  });

  it('is false for a non-manager', () => {
    expect(canPromoteModel(model({ active: false }), false, null)).toBe(false);
  });

  it('is false for the already-active model — nothing to promote it to', () => {
    expect(canPromoteModel(model({ active: true }), true, null)).toBe(false);
  });

  it('is false while any row is being promoted, even a different one', () => {
    expect(canPromoteModel(model({ active: false }), true, 'some-other-model.pt')).toBe(false);
  });
});

describe('resolvePromoteVersion', () => {
  it('falls back to "latest" for the registry\'s routine blank version', () => {
    expect(resolvePromoteVersion(model({ version: '' }))).toBe('latest');
  });

  it('falls back to "latest" for a whitespace-only version', () => {
    expect(resolvePromoteVersion(model({ version: '   ' }))).toBe('latest');
  });

  it('sends a real, non-blank version verbatim, trimmed', () => {
    expect(resolvePromoteVersion(model({ version: ' v2 ' }))).toBe('v2');
  });
});

describe('sortModelsForDisplay', () => {
  it('places the active model first', () => {
    const models = [model({ id: 'b.pt', active: false }), model({ id: 'a.pt', active: true })];
    expect(sortModelsForDisplay(models).map((m) => m.id)).toEqual(['a.pt', 'b.pt']);
  });

  it('orders the rest alphabetically by id', () => {
    const models = [model({ id: 'zeta.pt' }), model({ id: 'alpha.pt' }), model({ id: 'mid.pt' })];
    expect(sortModelsForDisplay(models).map((m) => m.id)).toEqual(['alpha.pt', 'mid.pt', 'zeta.pt']);
  });

  it('is stable with no active model at all', () => {
    const models = [model({ id: 'b.pt' }), model({ id: 'a.pt' })];
    expect(sortModelsForDisplay(models).map((m) => m.id)).toEqual(['a.pt', 'b.pt']);
  });

  it('does not mutate its input', () => {
    const models = [model({ id: 'b.pt' }), model({ id: 'a.pt' })];
    const copy = [...models];
    sortModelsForDisplay(models);
    expect(models).toEqual(copy);
  });
});
