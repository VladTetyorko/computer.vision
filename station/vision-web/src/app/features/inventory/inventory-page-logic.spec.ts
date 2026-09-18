import { describe, expect, it } from 'vitest';
import type { InventoryStateChipKind } from '../../core/fleet/inventory-logic';
import {
  INVENTORY_VIEWS,
  INVENTORY_VIEW_ALL,
  defaultInventoryView,
  filterRowsByInventoryView,
  inventoryViewTiles,
  parseInventoryView,
  rowMatchesInventoryView,
  serializeInventoryView,
  toggleInventoryView,
  type InventoryViewRow,
} from './inventory-page-logic';

function row(kind: InventoryStateChipKind, partial: Partial<InventoryViewRow> = {}): InventoryViewRow {
  return { stateChip: { kind }, ...partial };
}

describe('rowMatchesInventoryView', () => {
  it('matches the four state views against the row\'s own effective-state chip', () => {
    expect(rowMatchesInventoryView(row('in-field'), 'in-field')).toBe(true);
    expect(rowMatchesInventoryView(row('issued'), 'issued')).toBe(true);
    expect(rowMatchesInventoryView(row('in-stock'), 'in-stock')).toBe(true);
    expect(rowMatchesInventoryView(row('maintenance'), 'maintenance')).toBe(true);
    expect(rowMatchesInventoryView(row('in-stock'), 'in-field')).toBe(false);
  });

  it('counts a grounded vehicle as needing attention', () => {
    expect(rowMatchesInventoryView(row('maintenance'), 'needs-attention')).toBe(true);
  });

  it('counts a NO_GO verdict as needing attention even while it sits in stock', () => {
    expect(rowMatchesInventoryView(row('in-stock', { readinessVerdict: 'NO_GO' }), 'needs-attention')).toBe(true);
  });

  it('counts any readiness blocker as needing attention — "never probed" included', () => {
    expect(rowMatchesInventoryView(row('in-stock', { readinessVerdict: 'UNKNOWN', readinessCause: 'Map position' }), 'needs-attention')).toBe(true);
  });

  it('does not count a row whose readiness was never fetched — absence of evidence is not a blocker', () => {
    expect(rowMatchesInventoryView(row('in-stock'), 'needs-attention')).toBe(false);
    expect(rowMatchesInventoryView(row('in-field', { readinessVerdict: 'GO' }), 'needs-attention')).toBe(false);
  });

  it('never counts an archived or retired row toward a state view', () => {
    for (const view of INVENTORY_VIEWS) {
      expect(rowMatchesInventoryView(row('archived'), view)).toBe(false);
      expect(rowMatchesInventoryView(row('retired'), view)).toBe(false);
    }
  });
});

describe('filterRowsByInventoryView', () => {
  const rows = [row('in-stock'), row('in-field'), row('maintenance'), row('in-stock', { readinessVerdict: 'NO_GO' })];

  it('hands back the same array for All, without copying', () => {
    expect(filterRowsByInventoryView(rows, null)).toBe(rows);
  });

  it('narrows to one view', () => {
    expect(filterRowsByInventoryView(rows, 'in-stock')).toHaveLength(2);
    expect(filterRowsByInventoryView(rows, 'needs-attention')).toHaveLength(2);
  });
});

describe('inventoryViewTiles', () => {
  const rows = [
    row('in-stock'),
    row('in-stock', { readinessVerdict: 'NO_GO', readinessCause: 'Battery' }),
    row('in-field'),
    row('maintenance'),
    row('issued'),
  ];

  it('produces the five views in the printed order', () => {
    expect(inventoryViewTiles(rows).map((tile) => tile.view)).toEqual([...INVENTORY_VIEWS]);
  });

  it('counts exactly what selecting the view would show', () => {
    for (const tile of inventoryViewTiles(rows)) {
      expect(tile.count).toBe(filterRowsByInventoryView(rows, tile.view).length);
    }
  });

  it('colours only the two views that mean something is wrong, and only while they hold rows', () => {
    const tiles = inventoryViewTiles(rows);
    expect(tiles.find((t) => t.view === 'needs-attention')?.tone).toBe('danger');
    expect(tiles.find((t) => t.view === 'maintenance')?.tone).toBe('warn');
    expect(tiles.find((t) => t.view === 'in-field')?.tone).toBe('ok');
    expect(tiles.find((t) => t.view === 'in-stock')?.tone).toBe('default');
    expect(inventoryViewTiles([]).every((tile) => tile.tone === 'default')).toBe(true);
  });

  it('pulses the live dot only while something is actually airborne', () => {
    expect(inventoryViewTiles(rows).find((t) => t.view === 'in-field')?.live).toBe(true);
    expect(inventoryViewTiles([row('in-stock')]).find((t) => t.view === 'in-field')?.live).toBe(false);
  });

  it('reads all-zero for an empty fleet rather than throwing', () => {
    expect(inventoryViewTiles([]).map((tile) => tile.count)).toEqual([0, 0, 0, 0, 0]);
  });
});

describe('defaultInventoryView', () => {
  it('opens on Needs attention while anything is in it, else All', () => {
    expect(defaultInventoryView(2)).toBe('needs-attention');
    expect(defaultInventoryView(0)).toBeNull();
  });
});

describe('parseInventoryView / serializeInventoryView', () => {
  it('round-trips every view and explicit All', () => {
    for (const view of INVENTORY_VIEWS) {
      expect(parseInventoryView(serializeInventoryView(view))).toBe(view);
    }
    expect(serializeInventoryView(null)).toBe(INVENTORY_VIEW_ALL);
    expect(parseInventoryView(INVENTORY_VIEW_ALL)).toBeNull();
  });

  it('keeps "explicitly All" distinct from "never chosen"', () => {
    expect(parseInventoryView(INVENTORY_VIEW_ALL)).toBeNull();
    expect(parseInventoryView(null)).toBeUndefined();
    expect(parseInventoryView(undefined)).toBeUndefined();
  });

  it('reads a stale or hand-edited value as never chosen', () => {
    expect(parseInventoryView('needs_attention')).toBeUndefined();
    expect(parseInventoryView('')).toBeUndefined();
  });
});

describe('toggleInventoryView', () => {
  it('selects a different view and deselects the current one', () => {
    expect(toggleInventoryView(null, 'issued')).toBe('issued');
    expect(toggleInventoryView('issued', 'in-stock')).toBe('in-stock');
    expect(toggleInventoryView('issued', 'issued')).toBeNull();
  });
});
