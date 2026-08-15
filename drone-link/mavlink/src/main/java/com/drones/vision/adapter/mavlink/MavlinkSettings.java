package com.drones.vision.adapter.mavlink;

import java.time.Duration;
import java.util.Objects;

/**
 * Framework-free, compact-constructor-validated tunables for this module
 * (docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3's config-extraction rule, §2.2's {@code vision.mavlink}/
 * {@code vision.rc} rows) — every value this module's classes today bake in as a {@code private
 * static final} constant (bind host, silence window, timeouts, transmit cadence/defaults, scanner
 * poll/timeout budgets), plus the four {@code vision.rc} values {@link MavlinkManualControlSender}
 * used to read from the {@code VISION_RC_OVERRIDE_HZ}/{@code VISION_RC_RELEASE_FRAMES} environment
 * variables — the one place in the repo that bypassed Spring config entirely. Every default below
 * is byte-identical to the literal or env-var default it replaces.
 *
 * <p>{@code vision-app}'s own {@code @ConfigurationProperties} record constructs one of these from
 * {@code vision.mavlink.*}/{@code vision.rc.*} (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave F2)
 * and threads it into {@link MavlinkTelemetrySource}/{@link MavlinkFeedTransmitter}/{@link
 * MavlinkHeartbeatScanner}'s constructors, and (docs/plans/active/MAVLINK-CORE-PLAN.md W4) into
 * every {@link MavlinkGateway} {@link MavlinkTelemetrySource} creates.
 *
 * <p><b>Public, not package-private (docs/plans/active/LAYERING-REFACTOR-PLAN.md wave F2):</b> {@code vision-app}
 * (a different module/package) must be able to construct one of these — mapped from {@code
 * VisionMavlinkProperties}/{@code VisionRcProperties} — and name {@link Scan}/{@link Transmit}/
 * {@link Rc} to pass to this module's constructors, which a package-private outer record would make
 * impossible (a member type's accessibility is bounded by its enclosing type's, JLS 6.6.1).
 */
public record MavlinkSettings(
        String bindHost,
        Duration silenceWindow,
        int maxUnclaimedVehicles,
        Duration closeJoinTimeout,
        Duration ackTimeout,
        Scan scan,
        Transmit transmit,
        Rc rc) {

    public MavlinkSettings {
        Objects.requireNonNull(bindHost, "bindHost must not be null");
        if (bindHost.isBlank()) {
            throw new IllegalArgumentException("bindHost must not be blank");
        }
        Objects.requireNonNull(silenceWindow, "silenceWindow must not be null");
        if (maxUnclaimedVehicles <= 0) {
            throw new IllegalArgumentException("maxUnclaimedVehicles must be > 0: " + maxUnclaimedVehicles);
        }
        Objects.requireNonNull(closeJoinTimeout, "closeJoinTimeout must not be null");
        Objects.requireNonNull(ackTimeout, "ackTimeout must not be null");
        Objects.requireNonNull(scan, "scan must not be null");
        Objects.requireNonNull(transmit, "transmit must not be null");
        Objects.requireNonNull(rc, "rc must not be null");
    }

    /** Reproduces every literal this module's classes hardcode today. */
    public static MavlinkSettings defaults() {
        return new MavlinkSettings(
                "0.0.0.0",
                Duration.ofSeconds(30),
                32,
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                Scan.defaults(),
                Transmit.defaults(),
                Rc.defaults());
    }

    /**
     * Copy of this settings object with just {@link #silenceWindow()} replaced — a test/tuning
     * convenience mirroring {@code GrpcCvSettings.withDetectWidth}/{@code withJpegQuality}; used by
     * {@link MavlinkTelemetrySource}'s package-private short-silence-window test constructor.
     */
    public MavlinkSettings withSilenceWindow(Duration newSilenceWindow) {
        return new MavlinkSettings(bindHost, newSilenceWindow, maxUnclaimedVehicles, closeJoinTimeout, ackTimeout,
                scan, transmit, rc);
    }

    /** {@code MavlinkHeartbeatScanner}'s hub-poll/self-bind-timeout budgets. */
    public record Scan(int activeHubPollCount, Duration activeHubMinPollInterval,
                Duration selfBindMinReadTimeout, Duration selfBindMaxReadTimeout) {

        public Scan {
            if (activeHubPollCount <= 0) {
                throw new IllegalArgumentException("activeHubPollCount must be > 0: " + activeHubPollCount);
            }
            Objects.requireNonNull(activeHubMinPollInterval, "activeHubMinPollInterval must not be null");
            Objects.requireNonNull(selfBindMinReadTimeout, "selfBindMinReadTimeout must not be null");
            Objects.requireNonNull(selfBindMaxReadTimeout, "selfBindMaxReadTimeout must not be null");
            if (selfBindMinReadTimeout.compareTo(selfBindMaxReadTimeout) > 0) {
                throw new IllegalArgumentException(
                        "selfBindMinReadTimeout must be <= selfBindMaxReadTimeout: "
                                + selfBindMinReadTimeout + " > " + selfBindMaxReadTimeout);
            }
        }

        public static Scan defaults() {
            return new Scan(5, Duration.ofMillis(20), Duration.ofMillis(20), Duration.ofMillis(200));
        }
    }

    /** {@code MavlinkFeedTransmitter}'s synthetic-flight cadence/defaults. */
    public record Transmit(Duration tick, Duration heartbeatPeriod, double defaultSpeedMps,
                     double defaultPositionRateHz, double defaultFailsafeBatteryPercent, int defaultSysid) {

        public Transmit {
            Objects.requireNonNull(tick, "tick must not be null");
            Objects.requireNonNull(heartbeatPeriod, "heartbeatPeriod must not be null");
            if (defaultSpeedMps <= 0) {
                throw new IllegalArgumentException("defaultSpeedMps must be > 0: " + defaultSpeedMps);
            }
            if (defaultPositionRateHz <= 0) {
                throw new IllegalArgumentException("defaultPositionRateHz must be > 0: " + defaultPositionRateHz);
            }
            if (defaultSysid < 1 || defaultSysid > 255) {
                throw new IllegalArgumentException("defaultSysid must be in [1,255]: " + defaultSysid);
            }
        }

        public static Transmit defaults() {
            return new Transmit(Duration.ofMillis(50), Duration.ofSeconds(1), 12.0, 5.0, 15.0, 1);
        }
    }

    /**
     * {@link MavlinkManualControlSender}'s {@code RC_CHANNELS_OVERRIDE} cadence
     * (docs/plans/done/RC-CONTROL-PHASE1-PLAN.md §3) — replaces the {@code VISION_RC_OVERRIDE_HZ}/{@code
     * VISION_RC_RELEASE_FRAMES} environment variables it used to read directly.
     */
    public record Rc(int overrideHz, int minOverrideHz, int maxOverrideHz, int releaseFrames) {

        public Rc {
            if (minOverrideHz <= 0 || maxOverrideHz < minOverrideHz) {
                throw new IllegalArgumentException(
                        "minOverrideHz/maxOverrideHz out of order: " + minOverrideHz + "/" + maxOverrideHz);
            }
            if (overrideHz <= 0) {
                throw new IllegalArgumentException("overrideHz must be > 0: " + overrideHz);
            }
            if (releaseFrames <= 0) {
                throw new IllegalArgumentException("releaseFrames must be > 0: " + releaseFrames);
            }
        }

        public static Rc defaults() {
            return new Rc(33, 10, 50, 3);
        }

        /** Clamps {@link #overrideHz()} to {@code [minOverrideHz, maxOverrideHz]}, mirroring the env-var-era clamping. */
        int clampedOverrideHz() {
            return Math.max(minOverrideHz, Math.min(maxOverrideHz, overrideHz));
        }
    }
}
