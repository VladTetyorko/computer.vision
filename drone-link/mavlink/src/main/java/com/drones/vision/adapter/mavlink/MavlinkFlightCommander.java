package com.drones.vision.adapter.mavlink;

import com.drones.mavlink.CompId;
import com.drones.mavlink.PeerId;
import com.drones.mavlink.SysId;
import com.drones.mavlink.service.CommandService;

import com.drones.vision.flight.domain.model.CommandResult;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.flight.domain.model.FlightCapability;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.flight.domain.port.FlightCommandPort;

import io.dronefleet.mavlink.common.MavCmd;
import io.dronefleet.mavlink.common.MavResult;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

/**
 * {@link FlightCommandPort} implementation sending guarded MAVLink 2 UDP commands to an aircraft
 * this platform is already ingesting telemetry from (docs/plans/active/DRONE-INFRA-PLAN.md I-e). Stage 1 opened
 * the RX-only doctrine for exactly one command, return-to-home; Stage 2 added arbitrary mode select
 * ({@code MAV_CMD_DO_SET_MODE}) and arm/disarm ({@code MAV_CMD_COMPONENT_ARM_DISARM}), plus a
 * {@link #capabilities(Device)} snapshot. docs/plans/active/MAVLINK-CORE-PLAN.md W4 rewired the actual
 * send/await machinery onto {@code mavlink-core}'s {@link CommandService} — every resolve/reject
 * rule and every wire-level byte below is preserved exactly; only <i>how</i> a command physically
 * gets sent and awaited changed. docs/plans/active/MAVLINK-COMMANDS-PLAN.md P1 then (a) split the
 * arm/disarm "force" magic, which had been an accidental defect (see {@link #ARM_FORCE_MAGIC}), and
 * (b) turned single-shot sends into a bounded retry for absolute-state commands (see {@link #send}).
 *
 * <h2>Resolving a vehicle to command</h2>
 * A device names a {@link MavlinkTelemetrySource} bind address ({@code udp://host:port}, plus an
 * optional pinned {@code sysid} option) — the exact same address its telemetry ingest uses. This
 * class shares {@code telemetrySource}'s own {@link MavlinkGateway} registry rather than tracking
 * anything of its own: {@link #supports(Device)} delegates straight to {@link
 * MavlinkTelemetrySource#supports(Device)} so the two can never disagree, and every command
 * resolves the device's <b>current claim</b> — sysid, firmware/mavType, and last-seen UDP source
 * address — via {@link MavlinkTelemetrySource#commandTarget}.
 *
 * <h2>Stage-1 reachability rule (still applies to every command)</h2>
 * A command can only be sent to an address this platform has actually received a datagram from —
 * there is no other way to know where "the aircraft" is on the network. A device that has never
 * been opened, or whose pinned sysid has never actually transmitted, has no known source address
 * and every command method throws {@link IllegalArgumentException} rather than guess a destination:
 * <b>you cannot command what you cannot hear</b>.
 *
 * <h2>Which firmwares are commandable</h2>
 * Only ArduPilot (and INAV, which masquerades as ArduPilot on the wire — see {@link FlightModes})
 * is commandable. Betaflight ({@code autopilot} GENERIC) is explicitly <b>not</b>, for any of these
 * commands: its RC link processes neither {@code MAV_CMD_DO_SET_MODE} nor {@code
 * MAV_CMD_COMPONENT_ARM_DISARM}, so sending it one would be a silent no-op at best — it is rejected
 * with {@link IllegalArgumentException} <em>before</em> {@link FlightModes} is ever consulted, even
 * though Betaflight's own table happens to contain an RTL-named mode. A vehicle whose firmware is
 * unknown (no {@code HEARTBEAT} yet) or unrecognized (e.g. PX4 — out of scope) is rejected the same
 * way. INAV reports as commandable and its commands are honestly attempted: if the INAV RX ignores
 * them the call simply returns {@link CommandResult#NO_ACK}, documented rather than hidden.
 *
 * <h2>Sending and awaiting acknowledgement — now via {@link CommandService}</h2>
 * Every command is a {@code COMMAND_LONG} sent from this platform's ground-station identity
 * ({@code mavlink-core}'s {@code MavlinkNode.groundStation()}, sysid 255 / compid 190 — the same
 * convention this class always used) to the vehicle's autopilot component ({@value
 * #TARGET_COMPONENT_AUTOPILOT}). A fresh, stateless {@link CommandService} is built per call from
 * the resolved device's gateway ({@link MavlinkGateway#sink()}/{@link MavlinkGateway#correlator()}).
 * docs/plans/active/MAVLINK-COMMANDS-PLAN.md P1 re-scoped this from a single-shot send into a
 * bounded retry: every command this class sends today is <i>absolute-state</i> (arm/disarm's
 * param1 is a state, not a toggle; {@code DO_SET_MODE}/{@code DO_AUX_FUNCTION}'s target level is
 * absolute too), so a silently-dropped {@code COMMAND_LONG} is safe to resend verbatim — {@link
 * CommandService} itself increments {@code confirmation} per attempt, so the vehicle can tell a
 * resend from a fresh request. {@link #send}'s {@code retryable} parameter freezes this eligibility
 * rule at the call site: {@code commandRetries} attempts beyond the first, each waiting up to
 * {@code ackTimeout} ({@code vision.mavlink.command-retries} / {@code vision.mavlink.ack-timeout}),
 * only for commands that opt in; a future relative/incremental command (e.g. a delta {@code
 * DO_REPOSITION}) must pass {@code retryable=false}. {@code MAV_RESULT_ACCEPTED} → {@link
 * CommandResult#ACCEPTED}; any other terminal result → {@link IllegalStateException} naming it
 * (never retried — {@code mavlink-core}'s {@code RequestResponse} only retries on outright silence,
 * never on a terminal refusal like {@code DENIED}); silence through every attempt → {@link
 * CommandResult#NO_ACK} (UDP is lossy in both directions; the command may still have landed).
 *
 * <p>Plain class with no framework dependency — instantiated directly by {@code vision-app}'s
 * wiring configuration, given the same {@link MavlinkTelemetrySource} instance used for real
 * telemetry ingest (same pattern as {@code MavlinkHeartbeatScanner}), so it resolves claims
 * against the gateway actually running, not a second one of its own.
 */
public final class MavlinkFlightCommander implements FlightCommandPort {

    private static final System.Logger LOG = System.getLogger(MavlinkFlightCommander.class.getName());

    /** {@code MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED}'s wire value — COMMAND_LONG param1 convention for DO_SET_MODE. */
    private static final float MODE_FLAG_CUSTOM_MODE_ENABLED = 1.0f;
    /** ARM_DISARM param1: 1 = arm, 0 = disarm. */
    private static final float ARM = 1.0f;
    private static final float DISARM = 0.0f;

    /**
     * ARM_DISARM param2 force-<b>arm</b> magic (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D2b,
     * mavlink-commands/R1-command-catalog.md) — distinct from {@link #DISARM_FORCE_MAGIC}. Before
     * this wave both the arm and disarm paths sent {@code 21196} (the force-<b>disarm</b> magic) on
     * a forced request; on the arm path that only "worked" because of an acknowledged ArduPilot
     * firmware bug (issues #32996 / #26521) that silently force-arms on the wrong magic instead of
     * correctly rejecting the malformed request — every forced arm before this wave has been
     * bypassing pre-arm safety checks by firmware accident, not by the deliberate design {@code
     * force=true} is supposed to request.
     */
    private static final float ARM_FORCE_MAGIC = 2989.0f;

    /**
     * ARM_DISARM param2 force-<b>disarm</b> magic (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D2b)
     * — unchanged from before this wave; this was always the correct value here, and remains the
     * one {@link #emergencyStop} sends for its unconditional forced disarm.
     */
    private static final float DISARM_FORCE_MAGIC = 21196.0f;

    private static final float ARM_DISARM_NO_FORCE = 0.0f;

    /** {@code MAV_CMD_DO_AUX_FUNCTION}'s switch-level parameter range — 0 low, 1 middle, 2 high. */
    private static final int AUX_LEVEL_LOW = 0;
    private static final int AUX_LEVEL_HIGH = 2;

    /** {@code MAV_COMP_ID_AUTOPILOT1} — the conventional command target component, and the component id this module's own claim policy assumes every vehicle's telemetry/acks come from (see {@link VehicleClaimPolicy}). */
    static final int TARGET_COMPONENT_AUTOPILOT = 1;

    private static final String FIRMWARE_ARDUPILOT = "ardupilot";
    /** {@link MavlinkTelemetryDecoder#firmwareLabel}'s label for {@code autopilot} GENERIC — Betaflight, in practice. */
    private static final String FIRMWARE_GENERIC = "generic";
    private static final String RTL_MODE_NAME = "RTL";
    /**
     * ArduRover's own {@code Hold} mode name ({@code custom_mode} 4, {@link
     * FlightModes#ARDUPILOT_ROVER} via {@code tableFor}) — the rover-safe emergency stop
     * (docs/plans/active/FLEET-RADIO-PLAN.md R4b). ArduRover actively brakes and holds against a
     * slope in {@code Hold}, unlike a forced disarm, which only cuts motor output and leaves
     * steering dead while the vehicle coasts.
     */
    private static final String ROVER_HOLD_MODE_NAME = "Hold";

    private final MavlinkTelemetrySource telemetrySource;
    private final Duration ackTimeout;

    /**
     * Retry budget for the absolute-state commands that opt in via {@link #send}'s {@code
     * retryable} parameter (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D2a) — {@code
     * vision.mavlink.command-retries}, plumbed through {@link MavlinkSettings#commandRetries()}.
     */
    private final int commandRetries;

    /**
     * Back-compat overload predating {@link MavlinkSettings} being threaded through this class
     * (docs/plans/active/MAVLINK-COMMANDS-PLAN.md P1) — {@link #ackTimeout} and {@link
     * #commandRetries} both take {@link MavlinkSettings#defaults()}'s values.
     */
    public MavlinkFlightCommander(MavlinkTelemetrySource telemetrySource) {
        this(telemetrySource, MavlinkSettings.defaults());
    }

    /**
     * Back-compat overload for {@code vision-app}'s existing {@code TelemetryWiring} call site
     * (out of this wave's file scope — docs/plans/active/MAVLINK-COMMANDS-PLAN.md P1's brief is
     * {@code drone-link/mavlink/**} plus the {@code application.yaml} {@code mavlink:} block only).
     * {@code ackTimeout} is now the <b>per-attempt</b> wait, re-scoped by this same wave from a
     * single whole-command wait (see {@link MavlinkSettings#ackTimeout()}); {@link #commandRetries}
     * defaults to {@link MavlinkSettings#defaults()}'s value since this overload has no seam of its
     * own to receive a caller-supplied one. <b>Known gap</b> (see this module's MODULE.md): until a
     * future wave adds {@code commandRetries} to {@code VisionMavlinkProperties} and threads it
     * through {@code TelemetryWiring}, production keeps sending {@code properties.ackTimeout()}
     * (today still defaulted at 2s, not 700ms) as the per-attempt wait here — i.e. the new retry
     * budget applies, but at the old, larger per-attempt timeout, so a fully-silent vehicle takes up
     * to {@code (commandRetries + 1) * 2s} rather than the ~2.1s this wave intended.
     */
    public MavlinkFlightCommander(MavlinkTelemetrySource telemetrySource, Duration ackTimeout) {
        this(telemetrySource, MavlinkSettings.defaults().withAckTimeout(ackTimeout));
    }

    /**
     * Canonical constructor (docs/plans/active/MAVLINK-COMMANDS-PLAN.md P1). {@code
     * settings.ackTimeout()} is the per-attempt wait and {@code settings.commandRetries()} the
     * retry budget for commands that opt in (see {@link #send}'s {@code retryable} parameter) —
     * together bounding worst-case latency to {@code (commandRetries + 1) * ackTimeout}.
     */
    public MavlinkFlightCommander(MavlinkTelemetrySource telemetrySource, MavlinkSettings settings) {
        this.telemetrySource = Objects.requireNonNull(telemetrySource, "telemetrySource must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        this.ackTimeout = settings.ackTimeout();
        this.commandRetries = settings.commandRetries();
    }

    @Override
    public boolean supports(Device device) {
        return telemetrySource.supports(device);
    }

    @Override
    public CommandResult setMode(Device device, String modeName) {
        Objects.requireNonNull(modeName, "modeName must not be null");
        ResolvedTarget resolved = resolveReachableTarget(device);
        int customMode = resolveCustomMode(resolved.target(), modeName);
        // DO_SET_MODE's target mode is absolute, not incremental -- retry-eligible per
        // docs/plans/active/MAVLINK-COMMANDS-PLAN.md D2a.
        return send(resolved, MavCmd.MAV_CMD_DO_SET_MODE,
                MODE_FLAG_CUSTOM_MODE_ENABLED, (float) customMode, 0f, 0f, 0f, 0f, 0f,
                "set-mode " + modeName, true);
    }

    @Override
    public CommandResult returnToHome(Device device) {
        // Stage-1 compatibility: RTL is just a mode. resolveCustomMode verifies RTL resolves for
        // the tracked ArduPilot vehicle family (copter=6, plane/rover=11) exactly as before.
        return setMode(device, RTL_MODE_NAME);
    }

    @Override
    public CommandResult arm(Device device, boolean force) {
        return armOrDisarm(device, ARM, force, "arm");
    }

    @Override
    public CommandResult disarm(Device device, boolean force) {
        return armOrDisarm(device, DISARM, force, "disarm");
    }

    private CommandResult armOrDisarm(Device device, float armParam, boolean force, String verb) {
        ResolvedTarget resolved = resolveReachableTarget(device);
        requireCommandableFirmware(resolved.target());
        float forceMagic = armParam == ARM ? ARM_FORCE_MAGIC : DISARM_FORCE_MAGIC;
        // COMPONENT_ARM_DISARM's param1 is an absolute state (armed/disarmed), not a toggle --
        // retry-eligible per docs/plans/active/MAVLINK-COMMANDS-PLAN.md D2a.
        return send(resolved, MavCmd.MAV_CMD_COMPONENT_ARM_DISARM,
                armParam, force ? forceMagic : ARM_DISARM_NO_FORCE, 0f, 0f, 0f, 0f, 0f,
                force ? verb + " (forced)" : verb, true);
    }

    /**
     * Vehicle-kind-gated emergency stop (docs/plans/active/FLEET-RADIO-PLAN.md R4b, F13). Before this
     * wave this was an unconditional forced disarm for every vehicle kind — correct for a copter or
     * plane (the intended kill-switch outcome is "it comes down now, not flying away"), but actively
     * wrong for a ground rover or surface boat: disarming only cuts motor output, so on a slope or in
     * a current the vehicle keeps moving with its steering now dead. A rover does not fall, it coasts.
     *
     * <h2>Per vehicle kind</h2>
     * <ul>
     *   <li><b>{@link VehicleKind#COPTER}/{@link VehicleKind#PLANE}</b> — unchanged: {@link
     *       #armOrDisarm} exactly as before this wave, byte-identical on the wire.</li>
     *   <li><b>{@link VehicleKind#ROVER}</b> — {@link #emergencyStopRover(ResolvedTarget)}: {@code
     *       MAV_CMD_DO_SET_MODE} into ArduRover's {@code Hold} (custom_mode 4), which actively brakes
     *       and holds against a slope while keeping steering authority alive. <b>No disarm follows a
     *       successful {@code Hold}</b> — a rover's active brake typically depends on the motor
     *       controller still being armed to apply reverse/holding torque; disarming immediately after
     *       would release the very brake this stop just applied, which on a slope is worse than never
     *       stopping at all. If an operator needs the vehicle fully powered down after it has
     *       genuinely stopped, that is a separate, deliberate {@code disarm} call once it is confirmed
     *       stationary — never bundled into the panic-stop path.</li>
     *   <li><b>{@link VehicleKind#UNKNOWN}</b> — same as copter/plane: an unconditional forced disarm.
     *       {@code VehicleKind.UNKNOWN} covers a genuinely unrecognized {@code MAV_TYPE} as well as
     *       {@code VehicleClass.SUBMARINE}/{@code UNSUPPORTED_VEHICLE}/{@code NOT_A_VEHICLE} (see
     *       {@link FlightModes#vehicleKind}) — none of those has a mode table this class can resolve
     *       {@value #ROVER_HOLD_MODE_NAME} against, so a {@code Hold} attempt would either throw
     *       before anything is even sent (no such mode) or, worse, silently resolve nothing at all.
     *       A forced disarm needs no vehicle-family-specific mode table — {@code
     *       MAV_CMD_COMPONENT_ARM_DISARM} is universal across every ArduPilot vehicle kind — so it is
     *       the one stop command guaranteed to actually reach the aircraft and produce a real,
     *       reportable outcome (see "Never silently does nothing" below) rather than an error the
     *       operator has to interpret instead of getting a stop attempt. This is also the platform's
     *       historical, well-understood meaning of "Emergency Stop"; changing it for a machine that
     *       never said what it is would be inventing a new guess, which {@link VehicleKind#UNKNOWN}'s
     *       own contract forbids.</li>
     * </ul>
     *
     * <h2>Never silently does nothing</h2>
     * Both paths end in {@link #send}, which either returns {@link CommandResult#ACCEPTED}/{@link
     * CommandResult#NO_ACK} or throws {@link IllegalStateException} for an explicit refusal — the
     * same three-way, honest outcome every other command in this class already reports. Nothing here
     * catches or downgrades a rover {@code Hold} failure into a false success: a refused or
     * unacknowledged {@code Hold} propagates exactly like a refused or unacknowledged forced disarm
     * always has.
     */
    @Override
    public CommandResult emergencyStop(Device device) {
        ResolvedTarget resolved = resolveReachableTarget(device);
        requireCommandableFirmware(resolved.target());
        if (FlightModes.vehicleKind(resolved.target().mavType()) == VehicleKind.ROVER) {
            return emergencyStopRover(resolved);
        }
        // COPTER, PLANE and UNKNOWN: unconditional forced disarm, unchanged from before this wave --
        // see this method's own javadoc for why UNKNOWN stays on this path rather than guessing Hold.
        return armOrDisarm(device, DISARM, true, "emergency stop");
    }

    /**
     * The rover-safe half of {@link #emergencyStop} (docs/plans/active/FLEET-RADIO-PLAN.md R4b) —
     * {@code MAV_CMD_DO_SET_MODE} into ArduRover's {@code Hold}, never a forced disarm. {@code Hold}
     * is guaranteed present in {@code ARDUPILOT_ROVER}'s table (custom_mode 4), so {@link
     * #resolveCustomMode} cannot fail here the way an arbitrary operator-supplied mode name could.
     */
    private CommandResult emergencyStopRover(ResolvedTarget resolved) {
        int holdMode = resolveCustomMode(resolved.target(), ROVER_HOLD_MODE_NAME);
        // Hold is an absolute mode target -- this is the rover e-stop D2a names explicitly as
        // retry-eligible.
        return send(resolved, MavCmd.MAV_CMD_DO_SET_MODE,
                MODE_FLAG_CUSTOM_MODE_ENABLED, (float) holdMode, 0f, 0f, 0f, 0f, 0f,
                "emergency stop (Hold)", true);
    }

    @Override
    public CommandResult auxFunction(Device device, int function, int level) {
        if (level < AUX_LEVEL_LOW || level > AUX_LEVEL_HIGH) {
            throw new IllegalArgumentException("Aux function switch level must be within ["
                    + AUX_LEVEL_LOW + "," + AUX_LEVEL_HIGH + "]: " + level);
        }
        ResolvedTarget resolved = resolveReachableTarget(device);
        requireCommandableFirmware(resolved.target());
        // param1 = the RCx_OPTION function number, param2 = 0 low / 1 middle / 2 high. What the
        // number means is the vehicle's business: this adapter deliberately keeps no table of aux
        // functions, because a stale copy of the firmware's own list is worse than none. The level
        // is an absolute switch position, not an increment -- retry-eligible per
        // docs/plans/active/MAVLINK-COMMANDS-PLAN.md D2a.
        return send(resolved, MavCmd.MAV_CMD_DO_AUX_FUNCTION,
                (float) function, (float) level, 0f, 0f, 0f, 0f, 0f,
                "aux-function " + function + " at level " + level, true);
    }

    @Override
    public FlightCapability capabilities(Device device) {
        if (device == null || !supports(device)) {
            return FlightCapability.notCommandable();
        }
        String bindKey = telemetrySource.bindKeyFor(device);
        MavlinkGateway.CommandTarget target = telemetrySource.commandTarget(bindKey, device.id());
        // No claim, never heard, or no firmware/ArduPilot family observed yet -> not commandable.
        // Betaflight ("generic") and any non-ArduPilot firmware fall through to notCommandable here
        // too; INAV masquerades as "ardupilot" and is (honestly, best-effort) reported commandable.
        if (target == null || !FIRMWARE_ARDUPILOT.equals(target.firmware())) {
            return FlightCapability.notCommandable();
        }
        List<String> modes = FlightModes.selectableModes(FlightModes.AUTOPILOT_ARDUPILOTMEGA, target.mavType());
        return new FlightCapability(true, true, true, modes, FlightModes.vehicleKind(target.mavType()));
    }

    /** @throws IllegalArgumentException if unsupported, or the vehicle has never been heard (no source address) */
    private ResolvedTarget resolveReachableTarget(Device device) {
        Objects.requireNonNull(device, "device must not be null");
        if (!supports(device)) {
            throw new IllegalArgumentException("MavlinkFlightCommander does not support device: " + device);
        }
        String bindKey = telemetrySource.bindKeyFor(device);
        MavlinkGateway.CommandTarget target = telemetrySource.commandTarget(bindKey, device.id());
        if (target == null || target.sourceAddress() == null) {
            throw new IllegalArgumentException("No MAVLink vehicle has ever been heard for device " + device.id()
                    + " -- you cannot command what you cannot hear (Stage 1 reachability rule); make sure its "
                    + "telemetry stream is open and the aircraft is actually transmitting");
        }
        return new ResolvedTarget(bindKey, target);
    }

    /** @throws IllegalArgumentException per this class's own "Which firmwares are commandable" javadoc */
    private static void requireCommandableFirmware(MavlinkGateway.CommandTarget target) {
        String firmware = target.firmware();
        if (firmware == null) {
            throw new IllegalArgumentException("MAVLink sysid " + target.sysid()
                    + " has not sent a HEARTBEAT yet -- firmware unknown, cannot command it");
        }
        if (FIRMWARE_GENERIC.equals(firmware)) {
            throw new IllegalArgumentException("Betaflight (MAVLink sysid " + target.sysid() + ") is not "
                    + "commandable: its RC link processes neither MAV_CMD_DO_SET_MODE nor "
                    + "MAV_CMD_COMPONENT_ARM_DISARM, even though its own mode table has an RTL-named mode");
        }
        if (!FIRMWARE_ARDUPILOT.equals(firmware)) {
            throw new IllegalArgumentException("Firmware \"" + firmware + "\" (MAVLink sysid " + target.sysid()
                    + ") is not commandable in DRONE-INFRA I-e (ArduPilot/INAV only)");
        }
    }

    /** @throws IllegalArgumentException if the firmware is not commandable, or {@code modeName} is unknown for it */
    private static int resolveCustomMode(MavlinkGateway.CommandTarget target, String modeName) {
        requireCommandableFirmware(target);
        Integer customMode = FlightModes.customModeFor(FlightModes.AUTOPILOT_ARDUPILOTMEGA, target.mavType(), modeName);
        if (customMode == null) {
            throw new IllegalArgumentException("ArduPilot vehicle sysid " + target.sysid() + " (mavType "
                    + target.mavType() + ") has no \"" + modeName + "\" mode in its mode table");
        }
        return customMode;
    }

    /**
     * @param retryable whether this command may be silently resent on ack timeout
     *                   (docs/plans/active/MAVLINK-COMMANDS-PLAN.md D2a) — {@code true} only for
     *                   absolute-state commands, where a resend after silence is safe because the
     *                   state it requests does not change between attempts: {@code
     *                   COMPONENT_ARM_DISARM}, {@code DO_SET_MODE}, {@code DO_AUX_FUNCTION}, and the
     *                   rover {@code Hold} e-stop are the only callers today, and all four pass
     *                   {@code true}. Any future <i>relative/incremental</i> command (e.g. a delta
     *                   reposition) must pass {@code false} at its own call site — resending it would
     *                   double-apply the delta, which silence-triggered retry must never risk.
     */
    private CommandResult send(ResolvedTarget resolved, MavCmd command,
                                float param1, float param2, float param3, float param4,
                                float param5, float param6, float param7, String description,
                                boolean retryable) {
        String bindKey = resolved.bindKey();
        MavlinkGateway.CommandTarget target = resolved.target();
        MavlinkGateway gateway = telemetrySource.gateway(bindKey);
        if (gateway == null) {
            throw new IllegalArgumentException("MAVLink gateway for device's stream is no longer open");
        }
        PeerId peerId = new PeerId(new SysId(target.sysid()), new CompId(TARGET_COMPONENT_AUTOPILOT));
        int retries = retryable ? commandRetries : 0;
        CommandService commandService = new CommandService(gateway.sink(), gateway.correlator(), ackTimeout, retries);

        LOG.log(System.Logger.Level.INFO,
                () -> "Sending " + description + " to MAVLink sysid " + target.sysid() + " at " + target.sourceAddress());

        CommandService.CommandOutcome outcome;
        try {
            outcome = commandService.sendLong(peerId, command, param1, param2, param3, param4, param5, param6, param7).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting " + description + " acknowledgement", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException(
                    "Failed to send " + description + " to MAVLink sysid " + target.sysid(), e.getCause());
        }

        if (outcome.status() == CommandService.CommandOutcome.Status.ACCEPTED) {
            return CommandResult.ACCEPTED;
        }
        if (outcome.status() == CommandService.CommandOutcome.Status.NO_ACK) {
            LOG.log(System.Logger.Level.WARNING, "No COMMAND_ACK from MAVLink sysid " + target.sysid()
                    + " for " + description + " within " + ackTimeout.toMillis() + "ms per attempt ("
                    + (retries + 1) + " attempt(s) total)");
            return CommandResult.NO_ACK;
        }
        MavResult result = outcome.mavResult();
        String resultName = result != null ? result.name() : ("unrecognized result " + outcome.resultCode());
        LOG.log(System.Logger.Level.WARNING, "MAVLink sysid " + target.sysid() + " refused " + description + ": " + resultName);
        throw new IllegalStateException("Vehicle sysid " + target.sysid() + " refused " + description + ": " + resultName);
    }

    /** A device's resolved, reachable command target plus the bind key its gateway is under. */
    private record ResolvedTarget(String bindKey, MavlinkGateway.CommandTarget target) {
    }
}
