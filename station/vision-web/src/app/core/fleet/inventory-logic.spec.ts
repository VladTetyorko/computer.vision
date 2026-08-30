import { describe, expect, it } from 'vitest';
import {
  effectiveInventoryStateChip,
  inventoryExportFilename,
  isInventoryTabVisible,
  parseInventoryTab,
  visibleInventoryTabs,
} from './inventory-logic';

describe('parseInventoryTab', () => {
  it('defaults to vehicles for a missing/blank/unrecognised value', () => {
    expect(parseInventoryTab(null)).toBe('vehicles');
    expect(parseInventoryTab(undefined)).toBe('vehicles');
    expect(parseInventoryTab('')).toBe('vehicles');
    expect(parseInventoryTab('bogus')).toBe('vehicles');
  });

  it('recognises every real tab', () => {
    expect(parseInventoryTab('vehicles')).toBe('vehicles');
    expect(parseInventoryTab('equipment')).toBe('equipment');
    expect(parseInventoryTab('links')).toBe('links');
    expect(parseInventoryTab('categories')).toBe('categories');
  });
});

describe('visibleInventoryTabs', () => {
  it('gives a pilot only vehicles/equipment', () => {
    expect(visibleInventoryTabs(false)).toEqual(['vehicles', 'equipment']);
  });

  it('gives a manager/admin all four tabs', () => {
    expect(visibleInventoryTabs(true)).toEqual(['vehicles', 'equipment', 'links', 'categories']);
  });
});

describe('isInventoryTabVisible', () => {
  it('hides links/categories from a pilot', () => {
    expect(isInventoryTabVisible('links', false)).toBe(false);
    expect(isInventoryTabVisible('categories', false)).toBe(false);
  });

  it('shows every tab to a manager', () => {
    expect(isInventoryTabVisible('links', true)).toBe(true);
    expect(isInventoryTabVisible('categories', true)).toBe(true);
  });

  it('shows vehicles/equipment to everyone', () => {
    expect(isInventoryTabVisible('vehicles', false)).toBe(true);
    expect(isInventoryTabVisible('equipment', false)).toBe(true);
  });
});

describe('effectiveInventoryStateChip', () => {
  it('archived wins over any inventoryState', () => {
    const chip = effectiveInventoryStateChip({ lifecycle: 'DELETED', archived: true, inventoryState: 'IN_FIELD' });
    expect(chip).toEqual({ kind: 'archived', label: 'Archived', tone: 'muted', live: false });
  });

  it('deactivated wins over any inventoryState', () => {
    const chip = effectiveInventoryStateChip({ lifecycle: 'DEACTIVATED', archived: false, inventoryState: 'MAINTENANCE' });
    expect(chip).toEqual({ kind: 'deactivated', label: 'Deactivated', tone: 'muted', live: false });
  });

  it.each([
    ['IN_STOCK', 'in-stock', 'In stock', 'muted', false],
    ['ISSUED', 'issued', 'Issued', 'ok', false],
    ['IN_FIELD', 'in-field', 'In field', 'ok', true],
    ['MAINTENANCE', 'maintenance', 'Maintenance', 'warn', false],
    ['RETIRED', 'retired', 'Retired', 'muted', false],
  ] as const)('renders %s as kind %s', (inventoryState, kind, label, tone, live) => {
    const chip = effectiveInventoryStateChip({ lifecycle: 'ACTIVE', archived: false, inventoryState });
    expect(chip).toEqual({ kind, label, tone, live });
  });

  it('degrades an unfetched inventoryState to an honest unknown chip, never a fabricated In stock', () => {
    const chip = effectiveInventoryStateChip({ lifecycle: 'ACTIVE', archived: false });
    expect(chip).toEqual({ kind: 'unknown', label: '—', tone: 'muted', live: false });
  });
});

describe('inventoryExportFilename', () => {
  it('formats as inventory-YYYY-MM-DD.csv', () => {
    const nowMs = Date.parse('2026-08-29T14:03:00Z');
    expect(inventoryExportFilename(nowMs)).toBe('inventory-2026-08-29.csv');
  });
});
