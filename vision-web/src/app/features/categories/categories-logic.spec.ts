import { describe, expect, it } from 'vitest';
import type { Category, CategoryCounts } from '../../core/api/models';
import { buildCategoryRows, searchCategoryRows } from './categories-logic';

function category(partial: Partial<Category> = {}): Category {
  return { slug: 'drone', name: 'Drone', attributeHints: [], ...partial };
}

function counts(partial: Partial<CategoryCounts> = {}): CategoryCounts {
  return { categoryId: 'drone', categoryName: 'Drone', total: 0, active: 0, deactivated: 0, deleted: 0, streaming: 0, ...partial };
}

describe('buildCategoryRows', () => {
  it('joins a category to its matching counts by slug', () => {
    const rows = buildCategoryRows(
      [category({ slug: 'drone', name: 'Drone' })],
      [counts({ categoryId: 'drone', total: 5, active: 4, deactivated: 1, streaming: 2 })],
    );
    expect(rows).toEqual([{ slug: 'drone', name: 'Drone', parent: undefined, total: 5, active: 4, deactivated: 1, streaming: 2 }]);
  });

  it('gives a defined category with no matching counts row all-zero counts, not omitted', () => {
    const rows = buildCategoryRows([category({ slug: 'robot', name: 'Robot' })], []);
    expect(rows).toEqual([{ slug: 'robot', name: 'Robot', parent: undefined, total: 0, active: 0, deactivated: 0, streaming: 0 }]);
  });

  it('carries a counts row for an unknown slug through under its own name, not silently dropped', () => {
    const rows = buildCategoryRows([], [counts({ categoryId: 'ghost', categoryName: 'Ghost', total: 3 })]);
    expect(rows).toEqual([{ slug: 'ghost', name: 'Ghost', parent: undefined, total: 3, active: 0, deactivated: 0, streaming: 0 }]);
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
