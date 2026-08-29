import { describe, expect, it } from 'vitest';
import { pluralize } from './text-logic';

describe('pluralize', () => {
  it('keeps the singular at exactly one', () => {
    expect(pluralize(1, 'entry', 'entries')).toBe('1 entry');
  });

  it('pluralises zero and many', () => {
    expect(pluralize(0, 'frame')).toBe('0 frames');
    expect(pluralize(214, 'frame')).toBe('214 frames');
  });

  it('takes an explicit irregular plural rather than a regular "+s" guess', () => {
    expect(pluralize(2, 'entry', 'entries')).toBe('2 entries');
    expect(pluralize(0, 'entry', 'entries')).toBe('0 entries');
  });

  it('defaults to a regular "+s" plural with no explicit one supplied', () => {
    expect(pluralize(3, 'point')).toBe('3 points');
  });
});
