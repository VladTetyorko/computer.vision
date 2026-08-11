package com.drones.vision.platform;

/**
 * What happened to an auditable thing.
 *
 * <p>Deliberately limited to changes a user made deliberately. Streaming start/stop already flows
 * through {@link Event}/{@code EventPublisherPort} as operational telemetry; the audit trail is
 * the narrower, permanent record of <em>who altered the fleet</em>, which is the question asked
 * after the fact — "where did that drone go?"
 */
public enum AuditAction {

    /** The thing was brought into existence. */
    CREATED,

    /** Descriptive fields were edited. */
    UPDATED,

    /** Withdrawn from service, reversibly. */
    DEACTIVATED,

    /** Put back into service. */
    ACTIVATED,

    /** Soft-deleted: hidden and refused streaming, but retained. */
    DELETED,

    /** Brought back from a soft delete. */
    RESTORED
}
