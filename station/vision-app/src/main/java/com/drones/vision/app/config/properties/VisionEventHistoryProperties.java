package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Durable platform-event history policy ({@code vision.events.history.*},
 * docs/plans/active/ALWAYS-ON-FLOW-PLAN.md wave B3).
 *
 * <p>Its own root, not nested under {@code vision.live.*}: {@code vision.live.enabled} decides
 * whether an {@code Event} reaches a connected SSE client right now; this decides whether it also
 * gets a durable row behind {@code GET /api/system/events} so a client that was not connected — or
 * reconnects after a drop — can still see it. The two are independent: history works with live
 * updates on or off, and turning history off does not touch the live feed.
 *
 * @param enabled   whether {@code PersistingEventPublisher} records events at all. <b>Defaults
 *                  off</b>, matching this repo's opt-in guardrail: with it false, {@code
 *                  eventPublisherPort} is wired exactly as before this wave and {@code GET
 *                  /api/system/events} always returns an empty list rather than erroring — the
 *                  endpoint exists either way, only its content depends on this flag. Turned on in
 *                  {@code docker-compose.yml}, the same split {@code vision.telemetry.always-on}
 *                  established
 * @param retention the row-count cap applied on every write
 */
@ConfigurationProperties(prefix = "vision.events.history")
public record VisionEventHistoryProperties(@DefaultValue("false") boolean enabled, Retention retention) {

    public VisionEventHistoryProperties {
        if (retention == null) {
            retention = Retention.defaults();
        }
    }

    /**
     * @param maxRows total rows kept across every event type once persisted, oldest pruned first;
     *                default {@value #DEFAULT_MAX_ROWS}. A single table-wide cap rather than a
     *                per-type/per-stream one: most persisted event types (device/battery/link/
     *                geofence/divergence) carry no {@code streamId} to group by at all, so a
     *                per-key cap would leave the majority of rows uncapped — see {@code
     *                JpaEventHistory}'s own javadoc. At one row per edge-triggered occurrence
     *                (never per detection — see {@code PersistingEventPublisher}), this default is
     *                generous for a single-deployment fleet without being unbounded
     */
    public record Retention(@DefaultValue(Retention.DEFAULT_MAX_ROWS) int maxRows) {

        static final String DEFAULT_MAX_ROWS = "20000";
        static final int DEFAULT_MAX_ROWS_INT = 20_000;

        public Retention {
            if (maxRows <= 0) {
                throw new IllegalArgumentException("vision.events.history.retention.max-rows must be positive");
            }
        }

        static Retention defaults() {
            return new Retention(DEFAULT_MAX_ROWS_INT);
        }
    }
}
