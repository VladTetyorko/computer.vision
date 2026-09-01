import { describe, expect, it } from 'vitest';
import type { Category, CategoryCounts } from '../../core/api/models';
import { buildCategoryRows, canCreateCategory, isValidCategoryId, isValidCategoryName, searchCategoryRows } from './categories-logic';

function category(partial: Partial<Category> = {}): Category {
  return { slug: 'drone', name: 'Drone', attributeHints: [], connected: true, ...partial };
}

function counts(partial: Partial<CategoryCounts> = {}): CategoryCounts {
  return {
    categoryId: 'drone',
    categoryName: 'Drone',
    total: 0,
    active: 0,
    deactivated: 0,
    deleted: 0,
    streaming: 0,
    inStock: 0,
    issued: 0,
    inField: 0,
    maintenance: 0,
    retired: 0,
    ...partial,
  };
}

describe('buildCategoryRows', () => {
  it('joins a category to its matching counts by slug', () => {
    const rows = buildCategoryRows(
      [category({ slug: 'drone', name: 'Drone' })],
      [counts({ categoryId: 'drone', total: 5, active: 4, deactivated: 1, streaming: 2 })],
    );
    expect(rows).toEqual([{ slug: 'drone', name: 'Drone', parent: undefined, connected: true, total: 5, active: 4, deactivated: 1, streaming: 2 }]);
  });

  it('gives a defined category with no matching counts row all-zero counts, not omitted', () => {
    const rows = buildCategoryRows([category({ slug: 'robot', name: 'Robot' })], []);
    expect(rows).toEqual([{ slug: 'robot', name: 'Robot', parent: undefined, connected: true, total: 0, active: 0, deactivated: 0, streaming: 0 }]);
  });

  it('carries the connected flag through from the defined category, not just its counts', () => {
    const rows = buildCategoryRows([category({ slug: 'battery-case', name: 'Battery case', connected: false })], []);
    expect(rows[0].connected).toBe(false);
  });

  it('carries a counts row for an unknown slug through under its own name, not silently dropped', () => {
    const rows = buildCategoryRows([], [counts({ categoryId: 'ghost', categoryName: 'Ghost', total: 3 })]);
    expect(rows).toEqual([{ slug: 'ghost', name: 'Ghost', parent: undefined, connected: true, total: 3, active: 0, deactivated: 0, streaming: 0 }]);
  });

  it('carries the parent slug through when present', () => {
    const rows = buildCategoryRows([category({ slug: 'quad', name: 'Quadcopter', parent: 'drone' })], []);
    expect(rows[0].parent).toBe('drone');
  });

  it('sorts by name, case-insensitive', () => {
    const rows = buildCategoryRows(
      [category({ slug: 'z', name: 'zebra' }), category({ slug: 'a', name: 'Alpha' })],
      [],
    );
    expect(rows.map((r) => r.slug)).toEqual(['a', 'z']);
  });
});

describe('searchCategoryRows', () => {
  const rows = buildCategoryRows(
    [category({ slug: 'drone', name: 'Drone' }), category({ slug: 'ip-camera', name: 'IP Camera' })],
    [],
  );

  it('returns every row for a blank query', () => {
    expect(searchCategoryRows(rows, '')).toEqual(rows);
  });

  it('matches by name, case-insensitive', () => {
    expect(searchCategoryRows(rows, 'camera').map((r) => r.slug)).toEqual(['ip-camera']);
  });

  it('matches by slug', () => {
    expect(searchCategoryRows(rows, 'drone').map((r) => r.slug)).toEqual(['drone']);
  });

  it('matches nothing for an unrelated query', () => {
    expect(searchCategoryRows(rows, 'nope')).toEqual([]);
  });
});

describe('isValidCategoryId', () => {
  it('accepts a plain kebab-case slug', () => {
    expect(isValidCategoryId('drone')).toBe(true);
    expect(isValidCategoryId('ip-camera')).toBe(true);
    expect(isValidCategoryId('battery-case-2')).toBe(true);
  });

  it('rejects uppercase, spaces, and empty', () => {
    expect(isValidCategoryId('Drone')).toBe(false);
    expect(isValidCategoryId('ip camera')).toBe(false);
    expect(isValidCategoryId('')).toBe(false);
    expect(isValidCategoryId('   ')).toBe(false);
  });

  it('rejects a leading/trailing/doubled hyphen', () => {
    expect(isValidCategoryId('-drone')).toBe(false);
    expect(isValidCategoryId('drone-')).toBe(false);
    expect(isValidCategoryId('ip--camera')).toBe(false);
  });
});

describe('isValidCategoryName', () => {
  it('accepts any non-blank string', () => {
    expect(isValidCategoryName('Drone')).toBe(true);
  });

  it('rejects blank/whitespace-only', () => {
    expect(isValidCategoryName('')).toBe(false);
    expect(isValidCategoryName('   ')).toBe(false);
  });
});

describe('canCreateCategory', () => {
  it('requires both a valid id and a valid name', () => {
    expect(canCreateCategory('drone', 'Drone')).toBe(true);
    expect(canCreateCategory('', 'Drone')).toBe(false);
    expect(canCreateCategory('drone', '')).toBe(false);
    expect(canCreateCategory('Drone Two', 'Drone Two')).toBe(false);
  });
});
