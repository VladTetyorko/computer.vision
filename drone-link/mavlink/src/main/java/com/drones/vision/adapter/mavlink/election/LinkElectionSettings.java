package com.drones.vision.adapter.mavlink.election;

import java.time.Duration;
import java.util.Objects;

/**
 * Tunables for {@link LinkGroup}'s election state machine (LINK-PAIRING-PLAN.md §3.4/§4 row L3,
 * docs/conclusions/link-research/05-multi-link-arbitration.md §3) — which sysid a link belongs to
 * and which link "wins" for a given vehicle is project policy, not a protocol fact, so this record
 * lives in the driving adapter rather than {@code mavlink-core} (mirroring {@code MavlinkSettings}'s
 * own framework-free, compact-constructor-validated shape one package up).
 *
 * <p>{@code vision-app}'s {@code VisionLinksProperties} (bound from {@code vision.links.*}) maps
 * onto one of these; {@code MavlinkSettings#linkElection()} carries it into every {@code
 * MavlinkGateway} the same way {@link MavlinkSettings.LinkStatus} and the rest already do.
 *
 * @param softTimeout how long since a link last delivered a frame from a sysid before that link
 *                    stops being eligible to hold or win the ACTIVE seat for it — an active link
 *                    past this bound is demoted immediately if a healthy alternative exists;
 *                    default {@value #DEFAULT_SOFT_TIMEOUT}
 * @param hardTimeout how long since a link last delivered a frame before it is dropped from
 *                    consideration entirely — an active link with <b>no</b> healthy alternative is
 *                    kept (failsafe honesty: a stale link beats no link) until this bound, after
 *                    which the group has no ACTIVE link at all; must be {@code >= softTimeout};
 *                    default {@value #DEFAULT_HARD_TIMEOUT}
 * @param dwellWindow how long a recovered higher-priority link must stay the best eligible
 *                    candidate, continuously, before it displaces a healthy active link — the
 *                    "no flapping on one missed heartbeat" guardrail; default {@value
 *                    #DEFAULT_DWELL_WINDOW}
 */
public record LinkElectionSettings(Duration softTimeout, Duration hardTimeout, Duration dwellWindow) {

    static final String DEFAULT_SOFT_TIMEOUT = "3s";
    static final String DEFAULT_HARD_TIMEOUT = "10s";
    static final String DEFAULT_DWELL_WINDOW = "5s";
    private static final Duration DEFAULT_SOFT_TIMEOUT_DURATION = Duration.ofSeconds(3);
    private static final Duration DEFAULT_HARD_TIMEOUT_DURATION = Duration.ofSeconds(10);
    private static final Duration DEFAULT_DWELL_WINDOW_DURATION = Duration.ofSeconds(5);

    public LinkElectionSettings {
        Objects.requireNonNull(softTimeout, "softTimeout must not be null");
        Objects.requireNonNull(hardTimeout, "hardTimeout must not be null");
        Objects.requireNonNull(dwellWindow, "dwellWindow must not be null");
        if (softTimeout.isZero() || softTimeout.isNegative()) {
            throw new IllegalArgumentException("softTimeout must be positive: " + softTimeout);
        }
        if (hardTimeout.compareTo(softTimeout) < 0) {
            throw new IllegalArgumentException(
                    "hardTimeout must be >= softTimeout: " + hardTimeout + " < " + softTimeout);
        }
        if (dwellWindow.isNegative()) {
            throw new IllegalArgumentException("dwellWindow must not be negative: " + dwellWindow);
        }
    }

    public static LinkElectionSettings defaults() {
        return new LinkElectionSettings(
                DEFAULT_SOFT_TIMEOUT_DURATION, DEFAULT_HARD_TIMEOUT_DURATION, DEFAULT_DWELL_WINDOW_DURATION);
    }
}
