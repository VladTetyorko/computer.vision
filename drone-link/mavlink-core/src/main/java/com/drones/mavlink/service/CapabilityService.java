package com.drones.mavlink.service;

import com.drones.mavlink.PeerId;
import com.drones.mavlink.codec.FrameSink;
import com.drones.mavlink.codec.MavFrame;
import com.drones.mavlink.config.MavlinkCoreSettings;
import com.drones.mavlink.session.CorrelationKeys;
import com.drones.mavlink.session.Correlator;
import com.drones.mavlink.session.MatchKey;

import io.dronefleet.mavlink.common.AutopilotVersion;
import io.dronefleet.mavlink.common.CommandLong;
import io.dronefleet.mavlink.common.MavCmd;
import io.dronefleet.mavlink.common.MavProtocolCapability;
import io.dronefleet.mavlink.util.EnumValue;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;

/**
 * "What is this aircraft?" — one exchange: {@code MAV_CMD_REQUEST_MESSAGE(AUTOPILOT_VERSION)} out,
 * the {@code AUTOPILOT_VERSION} message itself back (ONBOARDING-PLAN §3 step PROBE).
 *
 * <h2>Why this is not a {@link CommandService} call</h2>
 * It <i>sends</i> a {@code COMMAND_LONG}, so reaching for {@link MessageIntervalService#requestMessage}
 * looks right — but that awaits the {@code COMMAND_ACK}, which only tells us the request was
 * accepted, not what the answer is. The reply this exchange actually wants is a different message on
 * a different key, so it drives {@link RequestResponse} directly. The {@code COMMAND_ACK} the
 * vehicle also sends finds no waiter and is dropped by the correlator, exactly as an uncorrelated
 * frame should be.
 *
 * <h2>Silence is an answer</h2>
 * A vehicle that never implements {@code AUTOPILOT_VERSION} answers nothing at all — no denial, no
 * ack of any use. That is {@link CapabilityReport.Status#NO_REPLY}, a value rather than a failure,
 * because "this airframe cannot tell us what it is" is a fact the onboarding flow is built to
 * report honestly rather than a fault to propagate.
 */
public final class CapabilityService {

    /** {@code MAV_CMD_REQUEST_MESSAGE}'s {@code param1} — which message id to send back. */
    private static final float REQUEST_AUTOPILOT_VERSION = CorrelationKeys.AUTOPILOT_VERSION_MESSAGE_ID;

    /** {@code flight_sw_version} packs {@code major.minor.patch.type} into one big-endian uint32. */
    private static final int VERSION_MAJOR_SHIFT = 24;
    private static final int VERSION_MINOR_SHIFT = 16;
    private static final int VERSION_PATCH_SHIFT = 8;
    private static final long VERSION_BYTE_MASK = 0xFFL;

    private static final long UINT32_MASK = 0xFFFFFFFFL;

    private final RequestResponse requestResponse;
    private final Duration timeout;
    private final int retries;

    public CapabilityService(FrameSink sink, Correlator correlator, Duration timeout, int retries) {
        this.requestResponse = new RequestResponse(correlator, sink);
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive, got " + timeout);
        }
        this.timeout = timeout;
        if (retries < 0) {
            throw new IllegalArgumentException("retries must be >= 0, got " + retries);
        }
        this.retries = retries;
    }

    /**
     * Reuses the <i>command</i> timing rather than the parameter timing: what goes on the wire is a
     * {@code COMMAND_LONG}, and how long a vehicle takes to act on one is the number already tuned
     * for that.
     */
    public CapabilityService(FrameSink sink, Correlator correlator, MavlinkCoreSettings settings) {
        this(sink, correlator, Objects.requireNonNull(settings, "settings").commandTimeout(), settings.commandRetries());
    }

    /** Asks {@code target} to identify itself. Never fails for silence — that completes as {@link CapabilityReport.Status#NO_REPLY}. */
    public CompletableFuture<CapabilityReport> requestAutopilotVersion(PeerId target) {
        Objects.requireNonNull(target, "target");
        MatchKey key = CorrelationKeys.forAutopilotVersion(target.system());
        RequestResponse.AttemptPayload payload = attempt -> CommandLong.builder()
                .targetSystem(target.system().value())
                .targetComponent(target.component().value())
                .command(MavCmd.MAV_CMD_REQUEST_MESSAGE)
                .confirmation(attempt)
                .param1(REQUEST_AUTOPILOT_VERSION)
                .param2(0).param3(0).param4(0).param5(0).param6(0).param7(0)
                .build();
        // Any AUTOPILOT_VERSION from this system is the answer -- there is only one per vehicle, so
        // unlike a parameter read there is nothing further to verify before accepting the reply.
        RequestResponse.ReplyClassifier classifier = reply -> null;
        return requestResponse.exchange(target, key, timeout, retries, payload, classifier)
                .thenApply(CapabilityService::decode)
                .exceptionallyCompose(CapabilityService::recoverFromTimeout);
    }

    private static CapabilityReport decode(MavFrame reply) {
        AutopilotVersion version = reply.as(AutopilotVersion.class);
        long packed = version.flightSwVersion();
        String firmwareVersion = (packed >>> VERSION_MAJOR_SHIFT & VERSION_BYTE_MASK) + "."
                + (packed >>> VERSION_MINOR_SHIFT & VERSION_BYTE_MASK) + "."
                + (packed >>> VERSION_PATCH_SHIFT & VERSION_BYTE_MASK);
        return new CapabilityReport(CapabilityReport.Status.OK, firmwareVersion,
                maturityOf(packed & VERSION_BYTE_MASK), capabilitiesOf(version),
                version.boardVersion(), version.vendorId(), version.productId(), version.uid(), version);
    }

    /** The {@code FIRMWARE_VERSION_TYPE} values, which the dialect does not generate as an enum. */
    private static CapabilityReport.Maturity maturityOf(long versionType) {
        if (versionType == 0) {
            return CapabilityReport.Maturity.DEV;
        }
        if (versionType == 64) {
            return CapabilityReport.Maturity.ALPHA;
        }
        if (versionType == 128) {
            return CapabilityReport.Maturity.BETA;
        }
        if (versionType == 192) {
            return CapabilityReport.Maturity.RC;
        }
        if (versionType == 255) {
            return CapabilityReport.Maturity.OFFICIAL;
        }
        return CapabilityReport.Maturity.UNKNOWN;
    }

    /**
     * Expands the {@code capabilities} bitmask into the flags it actually sets. Bits the dialect has
     * no constant for are dropped rather than guessed at — a caller reading
     * {@code report.supports(X)} gets a definite answer for every {@code X} it can name.
     */
    private static Set<MavProtocolCapability> capabilitiesOf(AutopilotVersion version) {
        long bits = version.capabilities().value() & UINT32_MASK;
        Set<MavProtocolCapability> flags = EnumSet.noneOf(MavProtocolCapability.class);
        for (MavProtocolCapability capability : MavProtocolCapability.values()) {
            long bit = EnumValue.of(capability).value() & UINT32_MASK;
            if (bit != 0 && (bits & bit) == bit) {
                flags.add(capability);
            }
        }
        return flags;
    }

    private static CompletableFuture<CapabilityReport> recoverFromTimeout(Throwable error) {
        Throwable cause = (error instanceof CompletionException && error.getCause() != null) ? error.getCause() : error;
        if (cause instanceof TimeoutException) {
            return CompletableFuture.completedFuture(new CapabilityReport(
                    CapabilityReport.Status.NO_REPLY, null, CapabilityReport.Maturity.UNKNOWN,
                    Set.of(), 0, 0, 0, null, null));
        }
        return CompletableFuture.failedFuture(error);
    }
}
