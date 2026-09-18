import { describe, expect, it } from 'vitest';
import type { VehicleRowActions, VehicleVerb, VerbAvailability } from '../../core/fleet/inventory-logic';
import {
  myVehicleActions,
  myVehicleCustodyLine,
  myVehicleMetaLine,
  myVehiclePrimaryVerb,
  myVehicleVerbList,
} from './my-vehicles-logic';
import type { VehicleRow } from './vehicles-logic';

const HIDDEN: VerbAvailability = { shown: false, disabled: false };
const AVAILABLE: VerbAvailability = { shown: true, disabled: false };

/** Every verb hidden by default — a test only sets the ones it cares about, mirroring `inventory-logic.spec.ts`'s own fixture style. */
function actions(overrides: Partial<VehicleRowActions> = {}): VehicleRowActions {
  const all: Record<VehicleVerb, VerbAvailability> = {
    issue: HIDDEN,
    return: HIDDEN,
    ground: HIDDEN,
    release: HIDDEN,
    retire: HIDDEN,
    archive: HIDDEN,
    restore: HIDDEN,
    fly: HIDDEN,
    watchLive: HIDDEN,
    open: AVAILABLE,
    ...overrides,
  };
  return all;
}

function row(partial: Partial<VehicleRow> = {}): VehicleRow {
  return {
    asset: {
      assetId: 'a-0',
      displayName: 'Backfire 2',
      category: 'drone',
      categoryName: 'Drone',
      owner: 'org',
      status: 'OFFLINE',
      attributes: {},
    },
    lifecycle: 'ACTIVE',
    archived: false,
    streaming: false,
    links: '—',
    stateChip: { kind: 'in-stock', label: 'In stock', tone: 'muted', live: false },
    sinceLabel: '—',
    simulated: false,
    readinessBlockers: [],
    firmware: '—',
    hours: '15h 04m',
    lastFlownLabel: '7h ago',
    ...partial,
  };
}

describe('myVehicleCustodyLine', () => {
  it('reads "With you" when the actor is the current custodian', () => {
    const line = myVehicleCustodyLine(
      row({ custodianId: 'u-1', custodianName: 'Bob', sinceLabel: '2h ago', stateChip: { kind: 'issued', label: 'Issued', tone: 'ok', live: false } }),
      'u-1',
    );
    expect(line).toBe('With you · since 2h ago');
  });

  it('names the custodian when it is somebody else', () => {
    const line = myVehicleCustodyLine(
      row({ custodianId: 'u-2', custodianName: 'Anna K.', sinceLabel: '2h ago', stateChip: { kind: 'issued', label: 'Issued', tone: 'ok', live: false } }),
      'u-1',
    );
    expect(line).toBe('With Anna K. · since 2h ago');
  });

  it('reads "In stock at <location>" when a location is recorded', () => {
    const line = myVehicleCustodyLine(row({ location: 'Shelf B' }), 'u-1');
    expect(line).toBe('In stock at Shelf B');
  });

  it('reads plain "In stock" with no location', () => {
    expect(myVehicleCustodyLine(row(), 'u-1')).toBe('In stock');
  });

  it('reads "In the field" for an open usage', () => {
    const line = myVehicleCustodyLine(row({ stateChip: { kind: 'in-field', label: 'In field', tone: 'ok', live: true } }), 'u-1');
    expect(line).toBe('In the field');
  });

  it('falls back to the chip label for a state this line does not otherwise name', () => {
    const line = myVehicleCustodyLine(row({ stateChip: { kind: 'maintenance', label: 'Maintenance', tone: 'warn', live: false } }), 'u-1');
    expect(line).toBe('Maintenance');
  });
});

describe('myVehicleMetaLine', () => {
  it('joins last-flown and hours, verbatim off the row', () => {
    expect(myVehicleMetaLine(row({ lastFlownLabel: '7h ago', hours: '15h 04m' }))).toBe('Last flown 7h ago · 15h 04m total');
  });

  it('never fabricates a value for an unflown vehicle', () => {
    expect(myVehicleMetaLine(row({ lastFlownLabel: 'Never flown', hours: '—' }))).toBe('Last flown Never flown · — total');
  });
});

describe('myVehicleActions', () => {
  it('hides Fly for a CREW-assigned row even when the matrix granted it', () => {
    const gated = myVehicleActions(actions({ fly: AVAILABLE, watchLive: AVAILABLE }), 'CREW');
    expect(gated.fly).toEqual(HIDDEN);
    expect(gated.watchLive).toEqual(AVAILABLE);
  });

  it('leaves every verb unchanged for a PILOT-assigned row', () => {
    const base = actions({ fly: AVAILABLE, watchLive: AVAILABLE });
    expect(myVehicleActions(base, 'PILOT')).toBe(base);
  });

  it('leaves every verb unchanged when the role is unknown (unassigned/not yet loaded)', () => {
    const base = actions({ watchLive: AVAILABLE });
    expect(myVehicleActions(base, undefined)).toBe(base);
  });

  it('is a no-op when Fly was never shown in the first place', () => {
    const base = actions({ watchLive: AVAILABLE });
    expect(myVehicleActions(base, 'CREW')).toBe(base);
  });
});

describe('myVehicleVerbList', () => {
  it('lists only shown verbs, in the fixed order, excluding open', () => {
    expect(myVehicleVerbList(actions({ fly: AVAILABLE, watchLive: AVAILABLE }))).toEqual(['fly', 'watchLive']);
  });

  it('includes a disabled verb — the card still renders it, as a ghost', () => {
    expect(myVehicleVerbList(actions({ fly: { shown: true, disabled: true, reason: 'Grounded' } }))).toEqual(['fly']);
  });

  it('is empty when nothing but open is shown', () => {
    expect(myVehicleVerbList(actions())).toEqual([]);
  });
});

describe('myVehiclePrimaryVerb', () => {
  it('picks Fly when shown and enabled', () => {
    expect(myVehiclePrimaryVerb(actions({ fly: AVAILABLE, watchLive: AVAILABLE }))).toBe('fly');
  });

  it('falls through to Watch live when Fly is disabled', () => {
    const disabledFly = { shown: true, disabled: true, reason: 'Grounded for maintenance — release it first' };
    expect(myVehiclePrimaryVerb(actions({ fly: disabledFly, watchLive: AVAILABLE }))).toBe('watchLive');
  });

  it('falls through to Watch live when Fly is not shown at all (e.g. hidden by the CREW gate)', () => {
    expect(myVehiclePrimaryVerb(actions({ watchLive: AVAILABLE }))).toBe('watchLive');
  });

  it('is undefined when neither Fly nor Watch live is shown', () => {
    expect(myVehiclePrimaryVerb(actions())).toBeUndefined();
  });
});
