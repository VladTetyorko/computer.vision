package com.drones.vision.warehouse.domain.model;

/**
 * Where an {@link Asset} sits in the warehouse-to-field lifecycle (docs/plans/active/
 * WAREHOUSE-UX-PLAN.md &sect;3.4).
 *
 * <p>Only {@link #IN_STOCK}, {@link #MAINTENANCE} and {@link #RETIRED} are ever stored on
 * {@link Asset#inventoryState()} — {@link #storable()} says which. {@link #ISSUED} and
 * {@link #IN_FIELD} are <b>derived</b>, computed on read by {@link InventoryStates#effective} from
 * an asset's {@link Custody} and whether it has an open usage; persisting them would let the
 * stored value and the live fact (who holds it, whether it is currently flying) drift apart.
 */
public enum InventoryState {

    /** In the warehouse, held by nobody. */
    IN_STOCK,

    /** Held by a custodian, not currently flying — derived, never stored. */
    ISSUED,

    /** Has an open usage right now — derived, never stored, and takes priority over {@link
     * #ISSUED} when both would otherwise apply, since a currently-flying aircraft is the more
     * current fact. */
    IN_FIELD,

    /** Grounded: at least one open, flight-blocking {@link MaintenanceRecord} exists. */
    MAINTENANCE,

    /** Withdrawn from the fleet for good. Not deleted — history is kept. */
    RETIRED;

    /**
     * Whether this value may be persisted on {@link Asset#inventoryState()}.
     *
     * @return {@code true} for {@link #IN_STOCK}, {@link #MAINTENANCE} and {@link #RETIRED};
     *         {@code false} for the derived {@link #ISSUED}/{@link #IN_FIELD}
     */
    public boolean storable() {
        return this == IN_STOCK || this == MAINTENANCE || this == RETIRED;
    }
}
