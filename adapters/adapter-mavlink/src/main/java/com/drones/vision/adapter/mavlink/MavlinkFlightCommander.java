package com.drones.vision.adapter.mavlink;

import com.drones.vision.domain.model.CommandResult;
import com.drones.vision.domain.model.Device;
import com.drones.vision.domain.port.out.FlightCommandPort;

import io.dronefleet.mavlink.MavlinkConnection;
import io.dronefleet.mavlink.common.CommandAck;
import io.dronefleet.mavlink.common.CommandLong;
import io.dronefleet.mavlink.common.MavCmd;
import io.dronefleet.mavlink.common.MavResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link FlightCommandPort} implementation sending {@code MAV_CMD_DO_SET_MODE} return-to-home
 * commands over MAVLink 2 UDP (docs/DRONE-INFRA-PLAN.md I-e, Stage 1 — "bring it home"). The
 * RX-only doctrine ends only here, deliberately: this class sends exactly one command, nothing
 * else — no generic command surface, no arm/disarm, no mode select beyond RTL (see later stages
 * of the plan and {@link FlightCommandPort}'s own javadoc).
 *
 * <h2>Resolving a vehicle to command</h2>
 * A device names a {@link MavlinkTelemetrySource} bind address ({@code udp://host:port}, plus an
 * optional pinned {@code sysid} option) — the exact same address its telemetry ingest uses. This
 * class shares {@code telemetrySource}'s own {@link MavlinkSocketHub} registry rather than
 * tracking anything of its own: {@link #supports(Device)} delegates straight to {@link
 * MavlinkTelemetrySource#supports(Device)} so the two can never disagree, and {@link
 * #returnToHome(Device)} resolves the device's <b>current claim</b> — sysid, firmware/mavType,
 * and last-seen UDP source address — via {@link MavlinkTelemetrySource#commandTarget}.
 *
 * <h2>Stage-1 reachability rule</h2>
 * A command can only be sent to an address this platform has actually received a datagram from —
 * there is no other way to know where "the aircraft" is on the network. A device that has never
 * been opened, or whose pinned sysid has never actually transmitted, has no known source address
 * and {@link #returnToHome(Device)} throws {@link IllegalArgumentException} rather than guess a
 * destination: <b>you cannot command what you cannot hear</b>. This is the same "no fake reads"
 * discipline the RX side already applies to telemetry fields — honestly extended to the one place
 * TX needs an address at all.
 *
 * <h2>Which firmwares are commandable</h2>
 * Only ArduPilot (and INAV, which masquerades as ArduPilot on the wire — see {@link FlightModes}
 * and docs/FC-INTEGRATIONS-PLAN.md's research-facts section) — {@link
 * FlightModes#customModeFor(int, int, String)} resolves the {@code custom_mode} number for
 * {@code "RTL"} from the vehicle's own tracked {@code mavType} (copter/plane/rover all have their
 * own RTL mode number). Betaflight ({@code autopilot} GENERIC) is explicitly <b>not</b>
 * commandable even though its own mode table happens to contain an entry named {@code "RTL"} —
 * per docs/DRONE-INFRA-PLAN.md I-e, Betaflight's own RC link does not process {@code
 * MAV_CMD_DO_SET_MODE} at all, so sending it one would be a silent no-op at best. A vehicle whose
 * firmware is unknown (no {@code HEARTBEAT} observed yet) or unrecognized (e.g. PX4 — out of
 * scope for Stage 1) is rejected the same way, all as {@link IllegalArgumentException}.
 *
 * <h2>Sending and awaiting acknowledgement</h2>
 * The command is a {@code COMMAND_LONG} carrying {@code MAV_CMD_DO_SET_MODE} (param1 = {@code
 * MAV_MODE_FLAG_CUSTOM_MODE_ENABLED}, param2 = the resolved custom mode — the standard MAVLink
 * "set mode" command shape), sent from this platform's own {@code sysid}/{@code compid}
 * ({@value #COMMANDER_SYSTEM_ID}/{@value #COMMANDER_COMPONENT_ID} — the conventional
 * ground-control-station system id and {@code MAV_COMP_ID_MISSIONPLANNER}, the closest standard
 * "flight-plan-generating GCS" component id) to the vehicle's autopilot component ({@value
 * #TARGET_COMPONENT_AUTOPILOT}, {@code MAV_COMP_ID_AUTOPILOT1}), reusing {@code
 * telemetrySource}'s own shared {@link MavlinkSocketHub#socket()} rather than opening a second
 * socket. The hub's read loop is the only reader of that socket, so this class never reads
 * directly — it registers a one-shot waiter via {@link MavlinkSocketHub#awaitAck} <em>before</em>
 * sending, so a very fast reply can never race the wait. It then blocks up to {@value
 * #ACK_TIMEOUT_MILLIS}ms for a matching {@code COMMAND_ACK}:
 * <ul>
 *   <li>{@code MAV_RESULT_ACCEPTED} → {@link CommandResult#ACCEPTED}.</li>
 *   <li>Any other result ({@code DENIED}/{@code UNSUPPORTED}/{@code FAILED}/etc.) → {@link
 *       IllegalStateException} naming the result — the aircraft explicitly refused.</li>
 *   <li>No ack within the timeout → {@link CommandResult#NO_ACK}. UDP is lossy in both
 *       directions; the command may still have landed. This is deliberately not an exception —
 *       see {@link CommandResult#NO_ACK}'s own javadoc.</li>
 * </ul>
 *
 * <p>Plain class with no framework dependency — instantiated directly by {@code vision-app}'s
 * wiring configuration, given the same {@link MavlinkTelemetrySource} instance used for real
 * telemetry ingest (same pattern as {@link MavlinkHeartbeatScanner}), so it resolves claims
 * against the gateway actually running, not a second one of its own.
 */
public final class MavlinkFlightCommander implements FlightCommandPort {

    private static final System.Logger LOG = System.getLogger(MavlinkFlightCommander.class.getName());

    /** {@code MAV_CMD_DO_SET_MODE}'s wire id (common dialect) — set system mode. */
    private static final int MAV_CMD_DO_SET_MODE = 176;
    /** {@code MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED}'s wire value — COMMAND_LONG param1 convention for DO_SET_MODE. */
    private static final float MODE_FLAG_CUSTOM_MODE_ENABLED = 1.0f;

    /** How long to wait for a {@code COMMAND_ACK} before reporting {@link CommandResult#NO_ACK}. */
    static final long ACK_TIMEOUT_MILLIS = 2_000L;

    /** MAVLink's conventional ground-control-station system id. */
    static final int COMMANDER_SYSTEM_ID = 255;
    /** {@code MAV_COMP_ID_MISSIONPLANNER} — "a component that can generate/supply a flight plan (GCS or developer API)". */
    static final int COMMANDER_COMPONENT_ID = 190;
    /** {@code MAV_COMP_ID_AUTOPILOT1} — the conventional {@code MAV_CMD_DO_SET_MODE} target component. */
    static final int TARGET_COMPONENT_AUTOPILOT = 1;

    private static final String FIRMWARE_ARDUPILOT = "ardupilot";
    /** {@link MavlinkTelemetryDecoder#firmwareLabel}'s label for {@code autopilot} GENERIC — Betaflight, in practice. */
    private static final String FIRMWARE_GENERIC = "generic";
    private static final String RTL_MODE_NAME = "RTL";

    private final MavlinkTelemetrySource telemetrySource;

    public MavlinkFlightCommander(MavlinkTelemetrySource telemetrySource) {
        this.telemetrySource = Objects.requireNonNull(telemetrySource, "telemetrySource must not be null");
    }

    @Override
    public boolean supports(Device device) {
        return telemetrySource.supports(device);
    }

    @Override
    public CommandResult returnToHome(Device device) {
        Objects.requireNonNull(device, "device must not be null");
        if (!supports(device)) {
            throw new IllegalArgumentException("MavlinkFlightCommander does not support device: " + device);
        }

        String bindKey = telemetrySource.bindKeyFor(device);
        MavlinkSocketHub.CommandTarget target = telemetrySource.commandTarget(bindKey, device.id());
        if (target == null || target.sourceAddress() == null) {
            throw new IllegalArgumentException("No MAVLink vehicle has ever been heard for device " + device.id()
                    + " -- you cannot command what you cannot hear (Stage 1 reachability rule); make sure its "
                    + "telemetry stream is open and the aircraft is actually transmitting");
        }

        int customMode = resolveReturnToHomeCustomMode(target);
        return sendReturnToHome(bindKey, target, customMode);
    }

    /** @throws IllegalArgumentException per this class's own javadoc "Which firmwares are commandable" section */
    private static int resolveReturnToHomeCustomMode(MavlinkSocketHub.CommandTarget target) {
        String firmware = target.firmware();
        if (firmware == null) {
            throw new IllegalArgumentException("MAVLink sysid " + target.sysid()
                    + " has not sent a HEARTBEAT yet -- firmware unknown, cannot resolve a return-to-home mode");
        }
        if (!FIRMWARE_ARDUPILOT.equals(firmware)) {
            if (FIRMWARE_GENERIC.equals(firmware)) {
                throw new IllegalArgumentException("Betaflight (MAVLink sysid " + target.sysid() + ") has no "
                        + "return-to-home command-TX capability: its RC link does not process "
                        + "MAV_CMD_DO_SET_MODE, even though its own mode table has an RTL-named mode");
            }
            throw new IllegalArgumentException("Firmware \"" + firmware + "\" (MAVLink sysid " + target.sysid()
                    + ") has no return-to-home capability in DRONE-INFRA I-e Stage 1 (ArduPilot/INAV only)");
        }
        Integer customMode =
                FlightModes.customModeFor(FlightModes.AUTOPILOT_ARDUPILOTMEGA, target.mavType(), RTL_MODE_NAME);
        if (customMode == null) {
            throw new IllegalArgumentException("ArduPilot vehicle sysid " + target.sysid() + " (mavType "
                    + target.mavType() + ") has no RTL mode in its mode table");
        }
        return customMode;
    }

    private CommandResult sendReturnToHome(String bindKey, MavlinkSocketHub.CommandTarget target, int customMode) {
        DatagramSocket socket = telemetrySource.socket(bindKey);
        if (socket == null) {
            throw new IllegalArgumentException("MAVLink gateway for device's stream is no longer open");
        }
        int sysid = target.sysid();
        CompletableFuture<CommandAck> ackFuture = telemetrySource.awaitAck(bindKey, sysid, MAV_CMD_DO_SET_MODE);
        try {
            MavlinkConnection connection = MavlinkConnection.create(InputStream.nullInputStream(),
                    new MavlinkUdpOutputStream(socket, target.sourceAddress().getAddress(), target.sourceAddress().getPort()));
            CommandLong commandLong = CommandLong.builder()
                    .targetSystem(sysid)
                    .targetComponent(TARGET_COMPONENT_AUTOPILOT)
                    .command(MavCmd.MAV_CMD_DO_SET_MODE)
                    .confirmation(0)
                    .param1(MODE_FLAG_CUSTOM_MODE_ENABLED)
                    .param2((float) customMode)
                    .build();
            connection.send2(COMMANDER_SYSTEM_ID, COMMANDER_COMPONENT_ID, commandLong);
            LOG.log(System.Logger.Level.INFO, () -> "Sent return-to-home (DO_SET_MODE custom_mode=" + customMode
                    + ") to MAVLink sysid " + sysid + " at " + target.sourceAddress());

            CommandAck ack = ackFuture.get(ACK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            MavResult result = ack.result().entry();
            if (result == MavResult.MAV_RESULT_ACCEPTED) {
                return CommandResult.ACCEPTED;
            }
            String resultName = result != null ? result.name() : ("unrecognized result " + ack.result().value());
            LOG.log(System.Logger.Level.WARNING, "MAVLink sysid " + sysid + " refused return-to-home: " + resultName);
            throw new IllegalStateException("Vehicle sysid " + sysid + " refused return-to-home: " + resultName);
        } catch (TimeoutException e) {
            LOG.log(System.Logger.Level.WARNING, "No COMMAND_ACK from MAVLink sysid " + sysid
                    + " for return-to-home within " + ACK_TIMEOUT_MILLIS + "ms");
            return CommandResult.NO_ACK;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to send return-to-home command to MAVLink sysid " + sysid, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting return-to-home acknowledgement", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed while awaiting return-to-home acknowledgement", e.getCause());
        } finally {
            telemetrySource.cancelAckWait(bindKey, sysid, MAV_CMD_DO_SET_MODE);
        }
    }
}
