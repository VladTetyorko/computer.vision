import { describe, expect, it } from 'vitest';
import type { InventoryState } from '../api/models';
import {
  FLY_WHILE_GROUNDED_REASON,
  RETIRE_WHILE_HELD_REASON,
  effectiveInventoryStateChip,
  inventoryActor,
  inventoryExportFilename,
  isInventoryTabVisible,
  parseInventoryTab,
  shortIdLabel,
  vehicleRowActions,
  visibleInventoryTabs,
  type InventoryActionRow,
  type InventoryActor,
  type VehicleRowActions,
  type VehicleVerb,
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

describe('inventoryActor', () => {
  it('maps the three capabilities this page gates on, and tolerates a session that has not loaded', () => {
    expect(inventoryActor(['MANAGE_FLEET', 'COMMAND_FLIGHT'], 'u-1')).toEqual({
      canManageFleet: true,
      canManageOrg: false,
      canCommandFlight: true,
      userId: 'u-1',
    });
    expect(inventoryActor(null, undefined)).toEqual({
      canManageFleet: false,
      canManageOrg: false,
      canCommandFlight: false,
      userId: undefined,
    });
  });
});

describe('shortIdLabel', () => {
  it('truncates a UUID to its first segment', () => {
    expect(shortIdLabel('3f2a91c4-1d2e-4b7a-9c8d-0e1f2a3b4c5d')).toBe('3f2a91c4…');
  });

  it('returns a value with nothing to truncate verbatim, never padded into a fake UUID shape', () => {
    expect(shortIdLabel('bob')).toBe('bob');
    expect(shortIdLabel('')).toBe('');
  });
});

// --- The verb matrix (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.2) --------------------------
//
// One `it` per printed cell of column 1 ("mayManageFleet") and column 4 ("anyone in scope"), plus
// the `fly` verb column 2 grants to any COMMAND_FLIGHT holder. Columns 2/3's own D4/D5 verbs
// (a pilot's Report issue, a custodian's Return) are asserted *absent* — they land with wave W2.

const VIEWER: InventoryActor = { canManageFleet: false, canManageOrg: false, canCommandFlight: false };
const FLEET_MANAGER: InventoryActor = { canManageFleet: true, canManageOrg: true, canCommandFlight: false, userId: 'u-mgr' };
const PILOT: InventoryActor = { canManageFleet: false, canManageOrg: false, canCommandFlight: true, userId: 'u-bob' };

function actionRow(partial: Partial<InventoryActionRow> = {}): InventoryActionRow {
  return { lifecycle: 'ACTIVE', archived: false, streaming: false, ...partial };
}

function inState(state: InventoryState, partial: Partial<InventoryActionRow> = {}): InventoryActionRow {
  return actionRow({ inventoryState: state, ...partial });
}

/** Every verb the matrix would actually render, in a stable order — what a kebab shows. */
function shown(actions: VehicleRowActions): VehicleVerb[] {
  return (Object.keys(actions) as VehicleVerb[]).filter((verb) => actions[verb].shown);
}

describe('vehicleRowActions — column 1 (mayManageFleet)', () => {
  it('IN_STOCK: Issue to… · Ground… · Retire… · Archive (+ Open)', () => {
    expect(shown(vehicleRowActions(inState('IN_STOCK'), FLEET_MANAGER)).sort()).toEqual(
      ['archive', 'ground', 'issue', 'open', 'retire'].sort(),
    );
  });

  it('ISSUED: Return to stock · Ground… · Retire disabled with a reason (+ Open)', () => {
    const actions = vehicleRowActions(inState('ISSUED', { custodianId: 'u-bob' }), FLEET_MANAGER);
    expect(shown(actions).sort()).toEqual(['ground', 'open', 'retire', 'return'].sort());
    expect(actions.retire).toEqual({ shown: true, disabled: true, reason: RETIRE_WHILE_HELD_REASON });
    expect(actions.return.disabled).toBe(false);
  });

  it('IN_FIELD: Ground… only — neither Return nor Retire while a usage is open (+ Open)', () => {
    const actions = vehicleRowActions(inState('IN_FIELD'), FLEET_MANAGER);
    expect(shown(actions).sort()).toEqual(['ground', 'open'].sort());
  });

  it('MAINTENANCE: Release · Retire… · Archive (+ Open)', () => {
    expect(shown(vehicleRowActions(inState('MAINTENANCE'), FLEET_MANAGER)).sort()).toEqual(
      ['archive', 'open', 'release', 'retire'].sort(),
    );
  });

  it('RETIRED: Archive (+ Open)', () => {
    expect(shown(vehicleRowActions(inState('RETIRED'), FLEET_MANAGER)).sort()).toEqual(['archive', 'open'].sort());
  });

  it('ARCHIVED: Restore (+ Open)', () => {
    const archived = actionRow({ lifecycle: 'DELETED', archived: true, inventoryState: 'IN_STOCK' });
    expect(shown(vehicleRowActions(archived, FLEET_MANAGER)).sort()).toEqual(['open', 'restore'].sort());
  });

  it('DEACTIVATED (no row of its own in §5.2) is treated exactly like ARCHIVED', () => {
    const deactivated = actionRow({ lifecycle: 'DEACTIVATED', inventoryState: 'IN_STOCK' });
    expect(shown(vehicleRowActions(deactivated, FLEET_MANAGER)).sort()).toEqual(['open', 'restore'].sort());
  });

  it('an inventoryState that was never fetched offers no mutating verb at all, only Open', () => {
    expect(shown(vehicleRowActions(actionRow(), FLEET_MANAGER))).toEqual(['open']);
  });
});

describe('vehicleRowActions — column 4 (anyone in scope)', () => {
  it.each(['IN_STOCK', 'ISSUED', 'IN_FIELD', 'MAINTENANCE', 'RETIRED'] as const)(
    '%s: a session with no capability at all may only Open it',
    (state) => {
      expect(shown(vehicleRowActions(inState(state), VIEWER))).toEqual(['open']);
    },
  );

  it('an archived row is still openable by anyone who can see it', () => {
    const archived = actionRow({ lifecycle: 'DELETED', archived: true });
    expect(shown(vehicleRowActions(archived, VIEWER))).toEqual(['open']);
  });

  it.each(['IN_STOCK', 'ISSUED', 'IN_FIELD'] as const)('%s adds Watch live while it is streaming', (state) => {
    expect(shown(vehicleRowActions(inState(state, { streaming: true }), VIEWER)).sort()).toEqual(['open', 'watchLive'].sort());
  });

  it.each(['MAINTENANCE', 'RETIRED'] as const)('%s offers no Watch live even when streaming', (state) => {
    expect(shown(vehicleRowActions(inState(state, { streaming: true }), VIEWER))).toEqual(['open']);
  });

  it('never offers the D4/D5 verbs W2 has not shipped yet — a pilot gets no Report issue and no Return', () => {
    const mine = inState('ISSUED', { custodianId: PILOT.userId });
    const actions = vehicleRowActions(mine, PILOT);
    expect(actions.return.shown).toBe(false);
    expect(actions.ground.shown).toBe(false);
  });
});

describe('vehicleRowActions — Fly (COMMAND_FLIGHT, §5.2 column 2)', () => {
  it.each(['IN_STOCK', 'ISSUED', 'IN_FIELD'] as const)('%s offers Fly to a COMMAND_FLIGHT holder', (state) => {
    expect(vehicleRowActions(inState(state), PILOT).fly).toEqual({ shown: true, disabled: false });
  });

  it('MAINTENANCE renders Fly disabled with its reason, rather than hiding it', () => {
    expect(vehicleRowActions(inState('MAINTENANCE'), PILOT).fly).toEqual({
      shown: true,
      disabled: true,
      reason: FLY_WHILE_GROUNDED_REASON,
    });
  });

  it.each(['RETIRED'] as const)('%s hides Fly outright', (state) => {
    expect(vehicleRowActions(inState(state), PILOT).fly.shown).toBe(false);
  });

  it('hides Fly from a session without COMMAND_FLIGHT, in every state', () => {
    for (const state of ['IN_STOCK', 'ISSUED', 'IN_FIELD', 'MAINTENANCE', 'RETIRED'] as const) {
      expect(vehicleRowActions(inState(state), VIEWER).fly.shown).toBe(false);
      expect(vehicleRowActions(inState(state), FLEET_MANAGER).fly.shown).toBe(false);
    }
  });
});

describe('inventoryExportFilename', () => {
  it('formats as inventory-YYYY-MM-DD.csv', () => {
    const nowMs = Date.parse('2026-08-29T14:03:00Z');
    expect(inventoryExportFilename(nowMs)).toBe('inventory-2026-08-29.csv');
  });
});
