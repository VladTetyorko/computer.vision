package com.drones.mavlink.config;

import java.time.Duration;
import java.util.Objects;

/**
 * Every value the MAVLink spec itself declines to pin (heartbeat rate, peer timeout, command
 * retry policy, ...) plus the module's own internal bounds (resync buffer cap, dispatch queue
 * capacity). One framework-free record — no {@code System.getenv}, no {@code System.getProperty},
 * no Spring anywhere in this module. A host application binds this from its own configuration
 * source exactly as {@code vision-app} already does for the pre-existing {@code MavlinkSettings}.
 *
 * <p>W1 (transport + codec) only consumes {@link #maxResyncBuffers()}; every other field exists
 * now so W2 (session) and W3 (services) don't need a contract change to reach it.
 */
public record MavlinkCoreSettings(
        Duration heartbeatPeriod,
        Duration peerTimeout,
        Duration commandTimeout,
        int commandRetries,
        Duration commandDedupeWindow,
        Rc rc,
        Duration closeJoinTimeout,
        int maxResyncBuffers,
        int dispatchQueueCapacity,
        Mission mission,
        Ftp ftp,
        Parameter parameter) {

    public MavlinkCoreSettings {
        requirePositive(heartbeatPeriod, "heartbeatPeriod");
        requirePositive(peerTimeout, "peerTimeout");
        requirePositive(commandTimeout, "commandTimeout");
        if (commandRetries < 0) {
            throw new IllegalArgumentException("commandRetries must be >= 0, got " + commandRetries);
        }
        requirePositive(commandDedupeWindow, "commandDedupeWindow");
        Objects.requireNonNull(rc, "rc");
        requirePositive(closeJoinTimeout, "closeJoinTimeout");
        if (maxResyncBuffers < 1) {
            throw new IllegalArgumentException("maxResyncBuffers must be >= 1, got " + maxResyncBuffers);
        }
        if (dispatchQueueCapacity < 1) {
            throw new IllegalArgumentException("dispatchQueueCapacity must be >= 1, got " + dispatchQueueCapacity);
        }
        Objects.requireNonNull(mission, "mission");
        Objects.requireNonNull(ftp, "ftp");
        Objects.requireNonNull(parameter, "parameter");
    }

    /**
     * Every default byte-identical to today's {@code adapter-mavlink} literals where one already
     * existed (see this module's own MODULE.md for the source of each), plus the spec-pinned
     * Mission/FTP numbers (W6) and this module's own new bounds (resync/dispatch).
     */
    public static MavlinkCoreSettings defaults() {
        return new MavlinkCoreSettings(
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                2,
                Duration.ofSeconds(60),
                Rc.defaults(),
                Duration.ofSeconds(5),
                64,
                256,
                Mission.defaults(),
                Ftp.defaults(),
                Parameter.defaults());
    }

    public MavlinkCoreSettings withHeartbeatPeriod(Duration heartbeatPeriod) {
        return new MavlinkCoreSettings(heartbeatPeriod, peerTimeout, commandTimeout, commandRetries,
                commandDedupeWindow, rc, closeJoinTimeout, maxResyncBuffers, dispatchQueueCapacity, mission, ftp, parameter);
    }

    public MavlinkCoreSettings withPeerTimeout(Duration peerTimeout) {
        return new MavlinkCoreSettings(heartbeatPeriod, peerTimeout, commandTimeout, commandRetries,
                commandDedupeWindow, rc, closeJoinTimeout, maxResyncBuffers, dispatchQueueCapacity, mission, ftp, parameter);
    }

    public MavlinkCoreSettings withCommandTimeout(Duration commandTimeout) {
        return new MavlinkCoreSettings(heartbeatPeriod, peerTimeout, commandTimeout, commandRetries,
                commandDedupeWindow, rc, closeJoinTimeout, maxResyncBuffers, dispatchQueueCapacity, mission, ftp, parameter);
    }

    public MavlinkCoreSettings withCommandRetries(int commandRetries) {
        return new MavlinkCoreSettings(heartbeatPeriod, peerTimeout, commandTimeout, commandRetries,
                commandDedupeWindow, rc, closeJoinTimeout, maxResyncBuffers, dispatchQueueCapacity, mission, ftp, parameter);
    }

    public MavlinkCoreSettings withCommandDedupeWindow(Duration commandDedupeWindow) {
        return new MavlinkCoreSettings(heartbeatPeriod, peerTimeout, commandTimeout, commandRetries,
                commandDedupeWindow, rc, closeJoinTimeout, maxResyncBuffers, dispatchQueueCapacity, mission, ftp, parameter);
    }

    public MavlinkCoreSettings withRc(Rc rc) {
        return new MavlinkCoreSettings(heartbeatPeriod, peerTimeout, commandTimeout, commandRetries,
                commandDedupeWindow, rc, closeJoinTimeout, maxResyncBuffers, dispatchQueueCapacity, mission, ftp, parameter);
    }

    public MavlinkCoreSettings withCloseJoinTimeout(Duration closeJoinTimeout) {
        return new MavlinkCoreSettings(heartbeatPeriod, peerTimeout, commandTimeout, commandRetries,
                commandDedupeWindow, rc, closeJoinTimeout, maxResyncBuffers, dispatchQueueCapacity, mission, ftp, parameter);
    }

    public MavlinkCoreSettings withMaxResyncBuffers(int maxResyncBuffers) {
        return new MavlinkCoreSettings(heartbeatPeriod, peerTimeout, commandTimeout, commandRetries,
                commandDedupeWindow, rc, closeJoinTimeout, maxResyncBuffers, dispatchQueueCapacity, mission, ftp, parameter);
    }

    public MavlinkCoreSettings withDispatchQueueCapacity(int dispatchQueueCapacity) {
        return new MavlinkCoreSettings(heartbeatPeriod, peerTimeout, commandTimeout, commandRetries,
                commandDedupeWindow, rc, closeJoinTimeout, maxResyncBuffers, dispatchQueueCapacity, mission, ftp, parameter);
    }

    public MavlinkCoreSettings withMission(Mission mission) {
        return new MavlinkCoreSettings(heartbeatPeriod, peerTimeout, commandTimeout, commandRetries,
                commandDedupeWindow, rc, closeJoinTimeout, maxResyncBuffers, dispatchQueueCapacity, mission, ftp, parameter);
    }

    public MavlinkCoreSettings withParameter(Parameter parameter) {
        return new MavlinkCoreSettings(heartbeatPeriod, peerTimeout, commandTimeout, commandRetries,
                commandDedupeWindow, rc, closeJoinTimeout, maxResyncBuffers, dispatchQueueCapacity, mission, ftp, parameter);
    }

    public MavlinkCoreSettings withFtp(Ftp ftp) {
        return new MavlinkCoreSettings(heartbeatPeriod, peerTimeout, commandTimeout, commandRetries,
                commandDedupeWindow, rc, closeJoinTimeout, maxResyncBuffers, dispatchQueueCapacity, mission, ftp, parameter);
    }

    /**
     * Parameter read/write timing (ONBOARDING O2). The MAVLink parameter-protocol page is one of the
     * few that states its own numbers: a targeted {@code PARAM_REQUEST_READ} should be retried on a
     * ~1 s timeout, a small number of times — unlike a full parameter <i>download</i>, which this
     * module deliberately does not implement (see {@code ParameterService}'s own non-goal note).
     */
    public record Parameter(Duration timeout, int retries) {

        public Parameter {
            requirePositive(timeout, "timeout");
            if (retries < 0) {
                throw new IllegalArgumentException("retries must be >= 0, got " + retries);
            }
        }

        public static Parameter defaults() {
            return new Parameter(Duration.ofSeconds(1), 3);
        }
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive, got " + value);
        }
    }

    /**
     * The fixed-rate {@code RC_CHANNELS_OVERRIDE} relay's cadence — byte-identical defaults to
     * today's {@code MavlinkSettings.Rc} (adapter-mavlink).
     */
    public record Rc(int overrideHz, int minOverrideHz, int maxOverrideHz, int releaseFrames) {

        public Rc {
            if (minOverrideHz < 1) {
                throw new IllegalArgumentException("minOverrideHz must be >= 1, got " + minOverrideHz);
            }
            if (maxOverrideHz < minOverrideHz) {
                throw new IllegalArgumentException(
                        "maxOverrideHz must be >= minOverrideHz, got " + maxOverrideHz + " < " + minOverrideHz);
            }
            if (overrideHz < 1) {
                throw new IllegalArgumentException("overrideHz must be >= 1, got " + overrideHz);
            }
            if (releaseFrames < 1) {
                throw new IllegalArgumentException("releaseFrames must be >= 1, got " + releaseFrames);
            }
        }

        public static Rc defaults() {
            return new Rc(33, 10, 50, 3);
        }

        /** {@link #overrideHz} clamped into {@code [minOverrideHz, maxOverrideHz]}. */
        public int clampedOverrideHz() {
            return Math.max(minOverrideHz, Math.min(maxOverrideHz, overrideHz));
        }
    }

    /** Mission upload timing (W6) — the only service family the spec itself pins numbers for. */
    public record Mission(Duration timeout, Duration itemTimeout, int retries) {

        public Mission {
            requirePositive(timeout, "timeout");
            requirePositive(itemTimeout, "itemTimeout");
            if (retries < 0) {
                throw new IllegalArgumentException("retries must be >= 0, got " + retries);
            }
        }

        public static Mission defaults() {
            return new Mission(Duration.ofMillis(1500), Duration.ofMillis(250), 5);
        }
    }

    /** FTP session timing (W6) — spec-recommended GCS values. */
    public record Ftp(Duration timeout, int retries) {

        public Ftp {
            requirePositive(timeout, "timeout");
            if (retries < 0) {
                throw new IllegalArgumentException("retries must be >= 0, got " + retries);
            }
        }

        public static Ftp defaults() {
            return new Ftp(Duration.ofMillis(50), 6);
        }
    }
}
