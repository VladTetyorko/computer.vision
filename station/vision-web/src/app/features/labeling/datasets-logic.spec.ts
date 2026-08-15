import { describe, expect, it } from 'vitest';
import { canSubmitDataset, parseClassesInput } from './datasets-logic';

describe('parseClassesInput', () => {
  it('splits on commas and trims', () => {
    expect(parseClassesInput('building, tower ,  crane')).toEqual(['building', 'tower', 'crane']);
  });

  it('splits on newlines too, for a pasted multi-line list', () => {
    expect(parseClassesInput('building\ntower\r\ncrane')).toEqual(['building', 'tower', 'crane']);
  });

  it('drops blank entries from trailing/repeated separators', () => {
    expect(parseClassesInput('building,, tower, ,')).toEqual(['building', 'tower']);
  });

  it('dedupes, keeping the first occurrence — order carries the YOLO class index', () => {
    expect(parseClassesInput('tower, building, tower')).toEqual(['tower', 'building']);
  });

  it('returns an empty array for blank input', () => {
    expect(parseClassesInput('   ')).toEqual([]);
    expect(parseClassesInput('')).toEqual([]);
  });
});

describe('canSubmitDataset', () => {
  it('requires a non-blank name and at least one class', () => {
    expect(canSubmitDataset('Buildings', ['building'], false)).toBe(true);
    expect(canSubmitDataset('  ', ['building'], false)).toBe(false);
    expect(canSubmitDataset('Buildings', [], false)).toBe(false);
  });

  it('is false while submitting', () => {
    expect(canSubmitDataset('Buildings', ['building'], true)).toBe(false);
  });
});
