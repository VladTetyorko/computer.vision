import type { AssignmentRole } from '../../core/api/models';
import type { VehicleRowActions, VehicleVerb } from '../../core/fleet/inventory-logic';
import type { VehicleRow } from './vehicles-logic';

/**
 * Pure, Angular-free logic behind the "My vehicles" cards (docs/plans/active/INVENTORY-REWORK-PLAN.md
 * §5.4, wave W5) — the persona view a session without `MANAGE_FLEET`, scoped to `ASSIGNED_ASSETS` (a
 * pilot or crew member), gets instead of the manager's table (`InventoryFacade#showsManagerView`).
 *
 * Cards read the *same* `VehicleRow`/`VehicleRowActions` the manager's table and drawer already read
 * (`vehicles-logic.ts`, `core/fleet/inventory-logic.ts#vehicleRowActions`) — this module adds no
 * second row model, only the card-specific prose lines and the card's own verb-rendering/primary
 * rule, which differs from the drawer's (see {@link myVehiclePrimaryVerb}).
 */

// --- Prose lines (§5.4's card anatomy) ------------------------------------------------------------

/**
 * The custody line, in prose — never a fact-grid row, since a pilot's card has no fact grid:
 * `"With you · since 3h ago"` when the acting user is the current custodian, `"With <name> · since
 * …"` otherwise, `"In stock"` (+ `" at <location>"` when one is recorded) for a stocked asset, and
 * `"In the field"` for one out on an open usage. Anything else (maintenance, retired, archived,
 * unknown) falls back to the row's own state-chip label — the same honest word the table's chip
 * already shows, never a fabricated custody sentence for a state this line doesn't otherwise cover.
 *
 * `custodianName` is, in practice, always populated whenever `custodianId` is
 * (`vehicles-logic.ts#custodianLabel` resolves to a real name or a short id label, never leaves it
 * unset) — the `'someone'` fallback below exists only for that field's optional type, not a case this
 * build expects to hit.
 */
export function myVehicleCustodyLine(row: VehicleRow, actorUserId: string | undefined): string {
  if (row.custodianId) {
    const holder = row.custodianId === actorUserId ? 'you' : row.custodianName ?? 'someone';
    return `With ${holder} · since ${row.sinceLabel}`;
  }
  switch (row.stateChip.kind) {
    case 'in-stock':
      return row.location ? `In stock at ${row.location}` : 'In stock';
    case 'in-field':
      return 'In the field';
    default:
      return row.stateChip.label;
  }
}

/** The meta line — `"Last flown 7h ago · 15h 04m total"` — straight off the row's own already-rendered labels; `'—'` rides through {@link VehicleRow.hours} unchanged for a never-flown vehicle. */
export function myVehicleMetaLine(row: VehicleRow): string {
  return `Last flown ${row.lastFlownLabel} · ${row.hours} total`;
}

// --- Card verbs (§5.4's actions row) ---------------------------------------------------------------

const HIDDEN: VehicleRowActions[VehicleVerb] = { shown: false, disabled: false };

/**
 * Card verbs, one authority decision layered on top of {@link vehicleRowActions}'s own answer
 * (`InventoryFacade#actionsFor`) — never a second one. "Crew never gets Fly"
 * (INVENTORY-REWORK-PLAN.md §5.4): a `CREW` assignment is about working the camera, not flying, and
 * the shared matrix has no way to know a session's per-asset `AssignmentRole` (it only reads
 * capabilities, which a CREW-assigned *pilot-role account* may still hold) — so this is the one place
 * that fact reaches a verb decision. Every other verb the matrix grants passes through unchanged.
 */
export function myVehicleActions(actions: VehicleRowActions, assignmentRole: AssignmentRole | undefined): VehicleRowActions {
  if (assignmentRole !== 'CREW' || !actions.fly.shown) {
    return actions;
  }
  return { ...actions, fly: HIDDEN };
}

/**
 * Left-to-right order the actions row renders shown verbs in — `open` excluded (it is the card
 * title's own link, never a button, §5.4). **The one list W2 (D4/D5) extends** the moment it adds
 * `report`/`return` gates to `vehicleRowActions`: appending the new verb here, and its label to
 * {@link MY_VEHICLE_VERB_LABELS}, is the whole change — the template iterates this list generically
 * and never names a verb itself.
 */
export const MY_VEHICLE_VERB_ORDER: readonly VehicleVerb[] = ['fly', 'watchLive', 'return'];

/** Display label for one verb's button — the "one label map" {@link MY_VEHICLE_VERB_ORDER}'s own doc comment refers to. */
export const MY_VEHICLE_VERB_LABELS: Readonly<Partial<Record<VehicleVerb, string>>> = {
  fly: 'Fly',
  watchLive: 'Watch live',
  return: 'Return',
};

/** Every verb this card renders as a button, in display order — `!shown` excluded; a `disabled` one still renders, as a ghost with its reason (the same poka-yoke rule the drawer/table use). */
export function myVehicleVerbList(actions: VehicleRowActions): readonly VehicleVerb[] {
  return MY_VEHICLE_VERB_ORDER.filter((verb) => actions[verb].shown);
}

/**
 * The card's one primary button (§5.4's own diagram: `[Fly]` is the only bracketed verb) — **Fly**
 * when it is both shown and currently usable, else **Watch live** when shown, else no primary at all
 * (every shown verb then renders as a ghost). Deliberately its own rule, not
 * `core/fleet/inventory-logic.ts#primaryVehicleVerb` (which ranks Return above Fly — right for a
 * manager weighing whether to take a vehicle back, wrong for a pilot's own launch-pad card, where
 * flying it is always the headline action when it's possible at all).
 *
 * This is also how a `CREW`-assigned card ends up primary-`watchLive` with no separate "Open crew
 * seat" verb: {@link myVehicleActions} has already hidden `fly` for it by the time this runs, so the
 * fallback below is reached the same way it would be for any other session with no `fly` — see this
 * module's own file doc / the facade's `myRoleFor` doc for why "Open crew seat" itself isn't wired up
 * this wave (no client-readable `vision.crew.enabled` signal).
 */
export function myVehiclePrimaryVerb(actions: VehicleRowActions): VehicleVerb | undefined {
  if (actions.fly.shown && !actions.fly.disabled) {
    return 'fly';
  }
  return actions.watchLive.shown ? 'watchLive' : undefined;
}
