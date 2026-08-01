import { describe, expect, it } from 'vitest';
import { sampleStatusLabel, validateAnnotations } from './sample-editor-logic';
import type { Annotation } from '../../core/api/models';

function annotation(overrides: Partial<Annotation> = {}): Annotation {
  return { label: 'building', source: 'OPERATOR', box: { x: 0.1, y: 0.1, width: 0.2, height: 0.2 }, ...overrides };
}

describe('validateAnnotations', () => {
  it('is valid for an empty annotation set regardless of classes', () => {
    expect(validateAnnotations([], [])).toEqual({ valid: true, invalidLabels: [], hasDegenerateBox: false });
  });

  it('is valid when every label is in the vocabulary and every box has positive size', () => {
    const result = validateAnnotations([annotation({ label: 'building' }), annotation({ label: 'tower' })], [
      'building',
      'tower',
    ]);
    expect(result.valid).toBe(true);
    expect(result.invalidLabels).toEqual([]);
  });

  it('collects every distinct out-of-vocabulary label', () => {
    const result = validateAnnotations(
      [annotation({ label: 'tank' }), annotation({ label: 'tank' }), annotation({ label: 'plane' })],
      ['building'],
    );
    expect(result.valid).toBe(false);
    expect(result.invalidLabels).toEqual(['tank', 'plane']);
  });

  it('flags a degenerate (zero-size) box as invalid even with a valid label', () => {
    const result = validateAnnotations([annotation({ box: { x: 0.1, y: 0.1, width: 0, height: 0.2 } })], ['building']);
    expect(result.valid).toBe(false);
    expect(result.hasDegenerateBox).toBe(true);
  });
});

describe('sampleStatusLabel', () => {
  it('labels every status', () => {
    expect(sampleStatusLabel('PENDING')).toBe('Pending review');
    expect(sampleStatusLabel('LABELED')).toBe('Labeled');
    expect(sampleStatusLabel('DISCARDED')).toBe('Discarded');
  });
});
