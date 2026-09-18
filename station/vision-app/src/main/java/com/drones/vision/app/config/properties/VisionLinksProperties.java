package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for {@code adapter-mavlink}'s link-election state machine ({@code vision.links.*},
 * LINK-PAIRING-PLAN.md §3.4/§4 row L3) — which sysid a link belongs to and which link "wins" is
 * project policy, so it is a deployment tunable rather than a protocol constant (see {@code
 * com.drones.vision.adapter.mavlink.election.LinkElectionSettings}'s own javadoc).
 *
 * <p>Mapped by {@code wiring.TelemetryWiring#toMavlinkSettings} onto {@code
 * com.drones.vision.adapter.mavlink.election.LinkElectionSettings} — every {@code @DefaultValue}
 * below is byte-identical to {@code LinkElectionSettings.defaults()}'s own literal.
 *
 * @param softTimeout how long since a link last delivered a frame from a sysid before that link
 *                    stops being eligible to hold or win the ACTIVE seat for it; default 3s
 * @param hardTimeout how long since a link last delivered a frame before it is dropped from
 *                    consideration entirely (a stale link is kept, as the last-known ACTIVE link,
 *                    until this bound — CLAUDE.md rule 7, "a stale link beats no link"); must be
 *                    {@code >= softTimeout}; default 10s
 * @param dwellWindow how long a recovered higher-priority link must stay the best eligible
 *                    candidate, continuously, before it displaces a healthy active link — the
 *                    "no flapping on one missed heartbeat" guardrail; default 5s
 */
@ConfigurationProperties(prefix = "vision.links")
public record VisionLinksProperties(
        @DefaultValue("3s") Duration softTimeout,
        @DefaultValue("10s") Duration hardTimeout,
        @DefaultValue("5s") Duration dwellWindow) {

    public VisionLinksProperties {
        if (softTimeout == null || softTimeout.isZero() || softTimeout.isNegative()) {
            throw new IllegalArgumentException("softTimeout must be positive: " + softTimeout);
        }
        if (hardTimeout == null || hardTimeout.compareTo(softTimeout) < 0) {
            throw new IllegalArgumentException("hardTimeout must be >= softTimeout: " + hardTimeout);
        }
        if (dwellWindow == null || dwellWindow.isNegative()) {
            throw new IllegalArgumentException("dwellWindow must not be negative: " + dwellWindow);
        }
    }
}
