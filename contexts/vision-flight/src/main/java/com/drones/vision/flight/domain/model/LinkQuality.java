package com.drones.vision.flight.domain.model;

import java.time.Instant;

/**
 * One {@code RADIO_STATUS} snapshot for a single link — mirrored from {@code mavlink-core}'s {@code
 * com.drones.mavlink.session.LinkQuality.Quality} (LINK-PAIRING-PLAN.md §3.4), minus its redundant
 * {@code linkId} field (already implied by the {@link LinkView} this is nested in). See {@link
 * LinkId}'s own javadoc for why this module keeps a local copy rather than importing that type
 * directly.
 *
 * <p>Every numeric field is nullable-boxed rather than a primitive default, matching the mirrored
 * type: "never heard" and "heard a genuine zero" must stay distinguishable.
 *
 * @param lastRadioStatusAt when this reading arrived
 * @param rssi              local received signal strength
 * @param remoteRssi        remote-reported received signal strength
 * @param noise             local noise floor
 * @param rxErrors          receive error count
 * @param fixed             {@code true} iff FEC is currently correcting anything
 */
public record LinkQuality(Instant lastRadioStatusAt, Integer rssi, Integer remoteRssi, Integer noise,
                           Integer rxErrors, Boolean fixed) {
}
