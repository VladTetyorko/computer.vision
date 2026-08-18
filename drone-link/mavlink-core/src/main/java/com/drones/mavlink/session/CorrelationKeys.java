package com.drones.mavlink.session;

import com.drones.mavlink.SysId;
import com.drones.mavlink.codec.MavFrame;

import io.dronefleet.mavlink.common.AutopilotVersion;
import io.dronefleet.mavlink.common.CommandAck;
import io.dronefleet.mavlink.common.MissionAck;
import io.dronefleet.mavlink.common.MissionCount;
import io.dronefleet.mavlink.common.MissionRequest;
import io.dronefleet.mavlink.common.MissionRequestInt;
import io.dronefleet.mavlink.common.ParamValue;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The one place a correlated reply's {@link MatchKey} is defined — for <b>both</b> directions
 * (MISSIONS-PLAN.md <b>D6</b>). A requester asks for its key through a named factory here; the
 * {@link DefaultCorrelator} derives an arriving frame's key from the compiled-in table here. Two
 * sides of the same convention living in one file is the whole point: a key built one way and
 * matched another is a silent hang, not a compile error.
 *
 * <h2>Why a table, not a fifth {@code instanceof}</h2>
 * {@code DefaultCorrelator.extractKey} began as a single {@code COMMAND_ACK} branch with a
 * documented "revisit when a second case arrives" note. Three waves now need seven types at once
 * (parameters, capability, and MISSIONS' four transfer messages), so the branch chain is the wrong
 * shape. Adding a correlated message type is now a row in {@link #TABLE} plus a factory — never an
 * edit to {@code DefaultCorrelator}, which is closed for modification, and never a change to the
 * API.md-frozen {@link Correlator} seam. Deliberately <i>not</i> a runtime plugin registry: nothing
 * needs to register a key extractor at runtime, and one would trade a compile-time guarantee for a
 * startup-ordering bug.
 *
 * <h2>The 16-character problem</h2>
 * {@link MatchKey#discriminator()} is a {@code long}, but {@code PARAM_VALUE} correlates by
 * {@code param_id} — up to 16 characters, which does not fit in 64 bits. So the discriminator
 * <b>routes</b> (a 64-bit hash of the normalised name) and the requester <b>verifies</b> (exact
 * string compare against {@code paramId()} on arrival — see {@code ParameterService}). A collision
 * is therefore not a correctness bug, only a wasted wakeup on a wrong frame that the requester
 * rejects and keeps waiting through. Hashing without that verification step would be a real defect;
 * the two halves are a pair and neither is optional.
 */
public final class CorrelationKeys {

    /** {@code COMMAND_ACK} — discriminator is the acked command id. */
    public static final int COMMAND_ACK_MESSAGE_ID = 77;

    /** {@code PARAM_VALUE} — discriminator is {@link #paramDiscriminator(String)}, not the param index. */
    public static final int PARAM_VALUE_MESSAGE_ID = 22;

    /** {@code AUTOPILOT_VERSION} — one per vehicle, so no discriminator is needed. */
    public static final int AUTOPILOT_VERSION_MESSAGE_ID = 148;

    /** {@code MISSION_REQUEST} (MISSIONS D6) — discriminator is the requested item seq. */
    public static final int MISSION_REQUEST_MESSAGE_ID = 40;

    /** {@code MISSION_COUNT} (MISSIONS D6) — one per download, so no discriminator. */
    public static final int MISSION_COUNT_MESSAGE_ID = 44;

    /** {@code MISSION_ACK} (MISSIONS D6) — one per transfer, so no discriminator. */
    public static final int MISSION_ACK_MESSAGE_ID = 47;

    /** {@code MISSION_REQUEST_INT} (MISSIONS D6) — discriminator is the requested item seq. */
    public static final int MISSION_REQUEST_INT_MESSAGE_ID = 51;

    /** The discriminator for reply types the protocol matches on {@code (system, messageId)} alone. */
    public static final long NO_DISCRIMINATOR = 0L;

    /** MAVLink's {@code param_id} field is {@code char[16]}, un-terminated when exactly 16 characters. */
    private static final int PARAM_ID_MAX_CHARS = 16;

    /** FNV-1a 64-bit offset basis / prime — a fixed, documented function, so both directions agree forever. */
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    /**
     * Message class → key. Exact-class lookup, not {@code instanceof}: every one of these payload
     * types is a {@code final} generated class, so subtype dispatch would buy nothing and cost a
     * linear scan on the RX thread's hot path.
     */
    private static final Map<Class<?>, Function<MavFrame, MatchKey>> TABLE = Map.of(
            CommandAck.class,
            frame -> new MatchKey(frame.header().system(), COMMAND_ACK_MESSAGE_ID,
                    frame.as(CommandAck.class).command().value()),
            ParamValue.class,
            frame -> new MatchKey(frame.header().system(), PARAM_VALUE_MESSAGE_ID,
                    paramDiscriminator(frame.as(ParamValue.class).paramId())),
            AutopilotVersion.class,
            frame -> new MatchKey(frame.header().system(), AUTOPILOT_VERSION_MESSAGE_ID, NO_DISCRIMINATOR),
            MissionRequest.class,
            frame -> new MatchKey(frame.header().system(), MISSION_REQUEST_MESSAGE_ID,
                    frame.as(MissionRequest.class).seq()),
            MissionRequestInt.class,
            frame -> new MatchKey(frame.header().system(), MISSION_REQUEST_INT_MESSAGE_ID,
                    frame.as(MissionRequestInt.class).seq()),
            MissionCount.class,
            frame -> new MatchKey(frame.header().system(), MISSION_COUNT_MESSAGE_ID, NO_DISCRIMINATOR),
            MissionAck.class,
            frame -> new MatchKey(frame.header().system(), MISSION_ACK_MESSAGE_ID, NO_DISCRIMINATOR));

    private CorrelationKeys() {
    }

    /**
     * The key {@code frame} would satisfy a waiter under, or {@code null} if this message type is
     * not a correlated reply at all (the overwhelmingly common case — telemetry streams reach
     * subscribers via {@link Dispatcher}, never through the correlator).
     */
    static MatchKey keyFor(MavFrame frame) {
        Function<MavFrame, MatchKey> extractor = TABLE.get(frame.payload().getClass());
        return extractor == null ? null : extractor.apply(frame);
    }

    /**
     * The key a {@code COMMAND_LONG}/{@code COMMAND_INT} awaits.
     *
     * @param system the vehicle the ack will originate from — never our own sysid, and never the
     *               ack's {@code targetSystem}, which is a wire extension field and unreliable
     */
    public static MatchKey forCommandAck(SysId system, int commandId) {
        return new MatchKey(system, COMMAND_ACK_MESSAGE_ID, commandId);
    }

    /** The key a {@code PARAM_REQUEST_READ}/{@code PARAM_SET} for {@code paramId} awaits. */
    public static MatchKey forParamValue(SysId system, String paramId) {
        return new MatchKey(system, PARAM_VALUE_MESSAGE_ID, paramDiscriminator(paramId));
    }

    /** The key a {@code MAV_CMD_REQUEST_MESSAGE(AUTOPILOT_VERSION)} awaits for the message itself. */
    public static MatchKey forAutopilotVersion(SysId system) {
        return new MatchKey(system, AUTOPILOT_VERSION_MESSAGE_ID, NO_DISCRIMINATOR);
    }

    /**
     * A {@code param_id} reduced to what the wire can actually carry and compared on: at most
     * {@value #PARAM_ID_MAX_CHARS} characters, with the {@code NUL} padding a shorter name is sent
     * with removed. Applied on both sides so {@code "SR2_EXTRA2"} and the {@code "SR2_EXTRA2\0\0…"}
     * that comes back off the wire are the same name.
     */
    public static String normalizeParamId(String paramId) {
        Objects.requireNonNull(paramId, "paramId");
        int end = paramId.indexOf('\0');
        String terminated = end < 0 ? paramId : paramId.substring(0, end);
        return terminated.length() <= PARAM_ID_MAX_CHARS ? terminated : terminated.substring(0, PARAM_ID_MAX_CHARS);
    }

    /**
     * FNV-1a over {@link #normalizeParamId}'s bytes. FNV-1a specifically because it is fully
     * specified by two constants that can never drift with a JDK upgrade — unlike
     * {@link String#hashCode()}, which is only 32 bits, or {@link Objects#hash}, which is
     * explicitly not a stable contract. Stability matters here because the value is a wire-matching
     * convention shared between the requester and the RX path.
     */
    public static long paramDiscriminator(String paramId) {
        String name = normalizeParamId(paramId);
        long hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < name.length(); i++) {
            hash ^= name.charAt(i) & 0xFF;
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
