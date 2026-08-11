package com.drones.vision.kernel;

/**
 * Where a registered thing sits in its life: in service, withdrawn, or removed.
 *
 * <p>Nothing here destroys data. {@link #DELETED} is a <em>soft</em> delete — the record stays,
 * its recorded usage and telemetry stay, and every reference to it stays resolvable. That matters
 * for more than tidiness: an {@link Asset} must always hold at least one device, so hard-deleting
 * a device would either break that invariant or force a cascade that silently destroys flight
 * history the user never agreed to lose. Marking instead of destroying makes the awkward cases
 * disappear — the last source of an asset can be removed without the asset becoming invalid.
 *
 * <p>Deleted things are hidden from normal listings and refuse to stream, so they behave as
 * deleted from the outside while remaining recoverable and auditable. Every transition between
 * these states is written to the audit trail ({@link com.drones.vision.platform.AuditTrailPort}).
 *
 * <p>Permitted transitions:
 * <pre>
 *   ACTIVE      ⇄ DEACTIVATED     deactivate / activate
 *   ACTIVE      → DELETED         delete
 *   DEACTIVATED → DELETED         delete
 *   DELETED     → DEACTIVATED     restore (never straight back on the air)
 * </pre>
 */
public enum LifecycleState {

    /** In service: may stream, and counts towards its asset's readiness. */
    ACTIVE,

    /** Withdrawn from service but fully visible; streaming is refused until reactivated. */
    DEACTIVATED,

    /**
     * Removed as far as users are concerned: hidden from listings and refused streaming, but
     * retained in storage so history stays intact and the removal can be undone.
     */
    DELETED
}
