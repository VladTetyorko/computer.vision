package com.drones.vision.api.security;

/**
 * The framework-free mirror of {@code vision.crew.*} (docs/plans/active/CREW-CONTROL-PLAN.md
 * &sect;3.6) that {@link SeatAccess} actually consumes — vision-api may not depend on Spring's
 * {@code @ConfigurationProperties} machinery, so {@code vision-app}'s wiring builds one of these
 * from the Spring-bound {@code VisionCrewProperties} record and hands it across as a plain bean, the
 * same bridge-properties idiom already used elsewhere at this module boundary.
 *
 * @param enabled the master switch ({@code vision.crew.enabled}, default {@code false}); {@code
 *                false} makes {@link SeatAccess} a pass-through (&sect;3.8): no verb is guarded, no
 *                409/403 is reachable, and every read reports both seats free
 * @param ttlMs   seat lifetime without a renewal ({@code vision.crew.seat-ttl-ms}), served verbatim
 *                as {@code SeatsResponse.ttlMs} so a caller can derive its own renewal cadence
 */
public record SeatAccessSettings(boolean enabled, long ttlMs) {
}
