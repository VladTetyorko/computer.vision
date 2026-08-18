package com.drones.vision.adapter.mavlink;

import java.time.Duration;
import java.util.List;
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
        Rc rc,
        Inventory inventory,
        Onboarding onboarding) {

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
        Objects.requireNonNull(inventory, "inventory must not be null");
        Objects.requireNonNull(onboarding, "onboarding must not be null");
    }

    /**
     * Back-compat overload for callers built before {@link #inventory()} existed — namely {@code
     * vision-app}'s {@code TelemetryWiring#toMavlinkSettings} (out of this wave's file scope, per
     * docs/plans/active/DRONE-ONBOARDING-PLAN.md O1's brief: {@code drone-link/mavlink/**} only).
     * Defaults {@link #inventory()} to {@link Inventory#defaults()}, exactly like every other field
     * this record has ever added.
     */
    public MavlinkSettings(String bindHost, Duration silenceWindow, int maxUnclaimedVehicles,
                            Duration closeJoinTimeout, Duration ackTimeout, Scan scan, Transmit transmit, Rc rc) {
        this(bindHost, silenceWindow, maxUnclaimedVehicles, closeJoinTimeout, ackTimeout, scan, transmit, rc,
                Inventory.defaults(), Onboarding.defaults());
    }

    /**
     * Back-compat overload for callers built before {@link #onboarding()} existed (wave O4), on the
     * same principle as the one above it.
     */
    public MavlinkSettings(String bindHost, Duration silenceWindow, int maxUnclaimedVehicles,
                            Duration closeJoinTimeout, Duration ackTimeout, Scan scan, Transmit transmit, Rc rc,
                            Inventory inventory) {
        this(bindHost, silenceWindow, maxUnclaimedVehicles, closeJoinTimeout, ackTimeout, scan, transmit, rc,
                inventory, Onboarding.defaults());
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
                Rc.defaults(),
                Inventory.defaults(),
                Onboarding.defaults());
    }

    /**
     * Copy of this settings object with just {@link #silenceWindow()} replaced — a test/tuning
     * convenience mirroring {@code GrpcCvSettings.withDetectWidth}/{@code withJpegQuality}; used by
     * {@link MavlinkTelemetrySource}'s package-private short-silence-window test constructor.
     */
    public MavlinkSettings withSilenceWindow(Duration newSilenceWindow) {
        return new MavlinkSettings(bindHost, newSilenceWindow, maxUnclaimedVehicles, closeJoinTimeout, ackTimeout,
                scan, transmit, rc, inventory, onboarding);
    }

    /**
     * Copy of this settings object with just {@link #inventory()} replaced — same test/tuning
     * convenience as {@link #withSilenceWindow}; lets a test run {@code MavlinkMessageInventory}'s
     * rolling window over a few seconds instead of the production default of ten.
     */
    public MavlinkSettings withInventory(Inventory newInventory) {
        return new MavlinkSettings(bindHost, silenceWindow, maxUnclaimedVehicles, closeJoinTimeout, ackTimeout,
                scan, transmit, rc, newInventory, onboarding);
    }

    /**
     * Copy of this settings object with just {@link #onboarding()} replaced — same test/tuning
     * convenience as {@link #withInventory}.
     */
    public MavlinkSettings withOnboarding(Onboarding newOnboarding) {
        return new MavlinkSettings(bindHost, silenceWindow, maxUnclaimedVehicles, closeJoinTimeout, ackTimeout,
                scan, transmit, rc, inventory, newOnboarding);
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

    /**
     * {@code MavlinkMessageInventory}'s rolling-window message/byte-rate accounting
     * (docs/plans/active/DRONE-ONBOARDING-PLAN.md O1 — stage 1 of the onboarding probe pipeline,
     * consumed later by O4's {@code VehicleConfigPort.probe}).
     *
     * @param window                       how far back a {@code count}/{@code hz} figure looks;
     *                                     default matches this plan's own frozen
     *                                     {@code vision.onboarding.probe.inventory-window} default
     *                                     (§8.1) so O5's later wiring changes no observed behaviour.
     * @param bucketWidth                  the granularity of the rolling window's ring buffer —
     *                                     one counter per {@code bucketWidth} slice, aged out once
     *                                     it falls outside {@code window}. A width of one second
     *                                     matches MAVLink's own rate vocabulary (stream rates are
     *                                     requested in whole/half Hz via {@code
     *                                     MAV_CMD_SET_MESSAGE_INTERVAL}) and keeps each counter's
     *                                     memory at exactly {@code window/bucketWidth} longs
     *                                     regardless of how fast a peer actually sends.
     * @param maxTrackedPeers              bounds the number of distinct system ids this inventory
     *                                     will track per gateway (LRU-evicted), the same threat
     *                                     model as {@code mavlink-core}'s own {@code
     *                                     MavlinkCoreSettings#maxResyncBuffers()} (default 64) —
     *                                     an unbounded number of distinct source system ids on one
     *                                     link must not grow this module's memory without bound.
     * @param maxTrackedMessageTypesPerPeer bounds the number of distinct message ids tracked per
     *                                     peer (LRU-evicted); 128 comfortably covers every message
     *                                     type a real ArduPilot/PX4 vehicle sends across the common
     *                                     + ardupilotmega dialects (a few dozen) while still
     *                                     bounding a flood of forged message ids from a hostile or
     *                                     malfunctioning sender.
     */
    public record Inventory(Duration window, Duration bucketWidth, int maxTrackedPeers,
                             int maxTrackedMessageTypesPerPeer) {

        public Inventory {
            Objects.requireNonNull(window, "window must not be null");
            if (window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("window must be positive: " + window);
            }
            Objects.requireNonNull(bucketWidth, "bucketWidth must not be null");
            if (bucketWidth.isZero() || bucketWidth.isNegative()) {
                throw new IllegalArgumentException("bucketWidth must be positive: " + bucketWidth);
            }
            if (bucketWidth.compareTo(window) > 0) {
                throw new IllegalArgumentException(
                        "bucketWidth must be <= window: " + bucketWidth + " > " + window);
            }
            if (maxTrackedPeers <= 0) {
                throw new IllegalArgumentException("maxTrackedPeers must be > 0: " + maxTrackedPeers);
            }
            if (maxTrackedMessageTypesPerPeer <= 0) {
                throw new IllegalArgumentException(
                        "maxTrackedMessageTypesPerPeer must be > 0: " + maxTrackedMessageTypesPerPeer);
            }
        }

        public static Inventory defaults() {
            return new Inventory(Duration.ofSeconds(10), Duration.ofSeconds(1), 64, 128);
        }
    }

    /**
     * {@code MavlinkVehicleConfigurator}'s probe budgets and the parameter set a probe reads
     * (docs/plans/active/DRONE-ONBOARDING-PLAN.md wave O4), plus wave O8's automatic on-connect
     * Mechanism A: the message set requested via {@code MAV_CMD_SET_MESSAGE_INTERVAL} the instant a
     * {@code MavlinkGateway} learns a peer, and the flag that gates it.
     *
     * <p>{@link #probeParameters()} is configuration rather than a constant because <b>which</b>
     * parameters are worth reading is a fleet-and-firmware decision, not a protocol fact: the
     * default below is ArduPilot's stream-rate and identity block — the set the readiness table's
     * own {@code requiredParameterName} rows are drawn from — and an operator flying PX4 or a
     * differently-tuned airframe should be able to change it without a rebuild. Names longer than
     * MAVLink's 16-character {@code param_id} are rejected at construction rather than silently
     * truncated into a *different* parameter.
     *
     * @param requestMessagesOnConnect gates wave O8's Mechanism A entirely — <b>default {@code
     *                                 false}</b> (plan §6.2 rule 3: "Mechanism A on connect is the
     *                                 one borderline case" of "nothing automatic from discovery").
     *                                 {@link MavlinkGateway} only constructs its {@code
     *                                 MavlinkConnectRemediator} when this is {@code true} — with it
     *                                 {@code false} no such object exists, so no command can be sent,
     *                                 not merely "isn't."
     * @param onConnectMessageRequests the message set Mechanism A asks for, in wire {@code
     *                                 messageId}/interval pairs — configuration, not a lookup into
     *                                 {@code vision-flight}'s requirement table (this adapter stays
     *                                 free of that coupling; see {@code MavlinkConnectRemediator}'s
     *                                 own javadoc)
     */
    public record Onboarding(List<String> probeParameters, Duration capabilityTimeout, int capabilityRetries,
                              Duration parameterTimeout, int parameterRetries,
                              boolean requestMessagesOnConnect, List<MessageRequest> onConnectMessageRequests) {

        /** MAVLink's own {@code param_id} field width — a name longer than this cannot be addressed at all. */
        private static final int PARAM_ID_MAX_CHARS = 16;

        public Onboarding {
            Objects.requireNonNull(probeParameters, "probeParameters must not be null");
            probeParameters = List.copyOf(probeParameters);
            for (String name : probeParameters) {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("probeParameters must not contain a blank name");
                }
                if (name.length() > PARAM_ID_MAX_CHARS) {
                    throw new IllegalArgumentException(
                            "probeParameters entry \"" + name + "\" exceeds MAVLink's " + PARAM_ID_MAX_CHARS
                                    + "-character param_id field");
                }
            }
            Objects.requireNonNull(capabilityTimeout, "capabilityTimeout must not be null");
            if (capabilityTimeout.isZero() || capabilityTimeout.isNegative()) {
                throw new IllegalArgumentException("capabilityTimeout must be positive: " + capabilityTimeout);
            }
            if (capabilityRetries < 0) {
                throw new IllegalArgumentException("capabilityRetries must be >= 0: " + capabilityRetries);
            }
            Objects.requireNonNull(parameterTimeout, "parameterTimeout must not be null");
            if (parameterTimeout.isZero() || parameterTimeout.isNegative()) {
                throw new IllegalArgumentException("parameterTimeout must be positive: " + parameterTimeout);
            }
            if (parameterRetries < 0) {
                throw new IllegalArgumentException("parameterRetries must be >= 0: " + parameterRetries);
            }
            Objects.requireNonNull(onConnectMessageRequests, "onConnectMessageRequests must not be null");
            onConnectMessageRequests = List.copyOf(onConnectMessageRequests);
        }

        /**
         * Back-compat overload for callers built before {@link #requestMessagesOnConnect()}/
         * {@link #onConnectMessageRequests()} existed (wave O8), on the same principle as {@link
         * MavlinkSettings}'s own back-compat constructors. Defaults the flag to {@code false} (this
         * wave's own guardrail) and the message set to {@link #defaultOnConnectMessageRequests()} —
         * so flipping the flag alone, later, on a settings object built this way still does something
         * sensible, exactly like {@link #probeParameters()}'s default is meaningful the moment a
         * caller starts probing.
         */
        public Onboarding(List<String> probeParameters, Duration capabilityTimeout, int capabilityRetries,
                           Duration parameterTimeout, int parameterRetries) {
            this(probeParameters, capabilityTimeout, capabilityRetries, parameterTimeout, parameterRetries,
                    false, defaultOnConnectMessageRequests());
        }

        /**
         * Twenty ArduPilot parameters — identity, airframe, battery, failsafe, GPS/EKF, geofence and
         * the telemetry link itself. Timeouts follow the MAVLink parameter-protocol page's own advice
         * (~1 s, retried a few times), widened once for {@code AUTOPILOT_VERSION} because a vehicle
         * assembles that message from several subsystems.
         *
         * <p><b>Every name below was verified present on ArduPilot Copter 4.7.0</b> — the firmware
         * {@code infra/sitl} ships — by reading it off a live instance, not from documentation. That
         * mattered: the plan's original list named {@code SYSID_THISMAV}, {@code FS_BATT_ENABLE} and
         * {@code GPS_TYPE}, all of which 4.7 has renamed ({@code MAV_SYSID}, {@code BATT_FS_LOW_ACT},
         * {@code GPS1_TYPE}), and six {@code SR2_*} stream-rate parameters that <b>no longer exist at
         * all</b> — see {@code MavlinkSitlOnboardingIntegrationTest} and this module's MODULE.md for
         * what that costs. An unknown name is not an error the protocol can report; it is simply
         * silence, so a stale list degrades into a slow probe that quietly reads less than it claims.
         * That is the whole reason this is configuration: parameter names are firmware-version state,
         * and no default compiled in today stays true for every airframe a fleet will fly.
         *
         * <p>{@link #requestMessagesOnConnect()} defaults {@code false} — this wave's guardrail — but
         * {@link #onConnectMessageRequests()} is still populated with a real, firmware-verified
         * default, on the same reasoning as {@link #probeParameters()}: an operator who later flips
         * the flag (O5's future {@code vision.onboarding.remediate.message-interval.enabled} property)
         * should get sensible behaviour without also having to invent a message list from scratch.
         */
        public static Onboarding defaults() {
            return new Onboarding(
                    List.of("MAV_SYSID", "MAV_OPTIONS", "SERIAL0_PROTOCOL",
                            "FRAME_CLASS", "FRAME_TYPE",
                            "BATT_CAPACITY", "BATT_MONITOR", "BATT_LOW_VOLT", "BATT_CRT_VOLT", "BATT_ARM_VOLT",
                            "BATT_FS_LOW_ACT", "FS_GCS_ENABLE", "FS_THR_ENABLE", "FS_OPTIONS",
                            "GPS1_TYPE", "GPS_AUTO_SWITCH", "AHRS_EKF_TYPE", "EK3_ENABLE",
                            "FENCE_ENABLE", "FENCE_ALT_MAX"),
                    Duration.ofSeconds(3), 2,
                    Duration.ofSeconds(1), 2,
                    false, defaultOnConnectMessageRequests());
        }

        /**
         * Nine message types spanning attitude, position, RC, servo output, airspeed/groundspeed,
         * GPS, IMU and system time — one per stream group a fleet operator would actually look for,
         * so a single group being silently ignored cannot hide behind the others. Wire {@code
         * messageId}s and 2 Hz interval are the same nine {@code MavlinkSitlOnboardingIntegrationTest}
         * (wave O4) proved ArduPilot Copter 4.7 accepts {@code MAV_CMD_SET_MESSAGE_INTERVAL} for —
         * that test drove them at 5 Hz successfully, so 2 Hz leaves headroom for a slower telemetry
         * radio than SITL's loopback link while still being enough to answer "is this aircraft
         * streaming what we need."
         */
        private static List<MessageRequest> defaultOnConnectMessageRequests() {
            Duration interval = Duration.ofMillis(500);
            return List.of(
                    new MessageRequest(1, interval),   // SYS_STATUS
                    new MessageRequest(30, interval),  // ATTITUDE
                    new MessageRequest(33, interval),  // GLOBAL_POSITION_INT
                    new MessageRequest(65, interval),  // RC_CHANNELS
                    new MessageRequest(36, interval),  // SERVO_OUTPUT_RAW
                    new MessageRequest(74, interval),  // VFR_HUD
                    new MessageRequest(24, interval),  // GPS_RAW_INT
                    new MessageRequest(116, interval), // SCALED_IMU2
                    new MessageRequest(2, interval));  // SYSTEM_TIME
        }

        /**
         * One {@code MAV_CMD_SET_MESSAGE_INTERVAL} request: a wire message id (not a name — the
         * platform does not carry a name→id table of its own outside {@code
         * MavlinkVehicleConfigurator}'s best-effort dialect lookup, and the command itself is
         * addressed by id) and the interval to request it at. {@code interval} follows {@link
         * com.drones.mavlink.service.MessageIntervalService#setMessageInterval}'s own contract:
         * negative is rejected here for the same reason it is rejected there, and {@link
         * Duration#ZERO} is legal — it means "resume this message's default/recommended rate" ({@code
         * MessageIntervalService#DEFAULT_RATE}), not "never".
         */
        public record MessageRequest(int messageId, Duration interval) {

            public MessageRequest {
                if (messageId < 0) {
                    throw new IllegalArgumentException("messageId must be >= 0: " + messageId);
                }
                Objects.requireNonNull(interval, "interval must not be null");
                if (interval.isNegative()) {
                    throw new IllegalArgumentException("interval must not be negative: " + interval);
                }
            }
        }
    }
}
