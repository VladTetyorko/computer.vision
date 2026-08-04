import { describe, expect, it } from 'vitest';
import { BUILT_IN_PROFILES } from '../../core/settings/settings-store';
import { computeDeltaFromDefaults } from './detection-settings-logic';

describe('computeDeltaFromDefaults', () => {
  const defaults = BUILT_IN_PROFILES[0];
  const label = (id: string) => (id === defaults.model ? 'General' : id);
  const atDefaults = {
    confidenceThreshold: defaults.confidenceThreshold,
    inferenceFps: defaults.inferenceFps,
    model: defaults.model,
    labelFilter: [],
    detectionEnabled: true,
  };

  it('reports nothing when the effective values match the defaults exactly', () => {
    expect(computeDeltaFromDefaults(atDefaults, defaults, label)).toEqual([]);
  });

  it('reports a confidence-only change', () => {
    expect(computeDeltaFromDefaults({ ...atDefaults, confidenceThreshold: 0.8 }, defaults, label)).toEqual([
      `confidence ${defaults.confidenceThreshold} → 0.8`,
    ]);
  });

  it('reports an fps-only change', () => {
    expect(computeDeltaFromDefaults({ ...atDefaults, inferenceFps: 20 }, defaults, label)).toEqual([
      `inference ${defaults.inferenceFps} → 20 fps`,
    ]);
  });

  it('reports a model change resolved through the label lookup, not the raw id', () => {
    expect(computeDeltaFromDefaults({ ...atDefaults, model: 'orion12l.pt' }, defaults, label)).toEqual([
      'model General → orion12l.pt',
    ]);
  });

  it('reports every changed field together, in confidence/fps/model order', () => {
    expect(
      computeDeltaFromDefaults(
        { ...atDefaults, confidenceThreshold: 0.1, inferenceFps: 1, model: 'orion12l.pt' },
        defaults,
        label,
      ),
    ).toEqual([
      `confidence ${defaults.confidenceThreshold} → 0.1`,
      `inference ${defaults.inferenceFps} → 1 fps`,
      'model General → orion12l.pt',
    ]);
  });

  it('ignores labelFilter/detectionEnabled — this diff is confidence/fps/model only', () => {
    expect(
      computeDeltaFromDefaults({ ...atDefaults, labelFilter: ['person'], detectionEnabled: false }, defaults, label),
    ).toEqual([]);
  });
});
