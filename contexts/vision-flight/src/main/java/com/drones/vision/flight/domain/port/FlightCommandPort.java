package com.drones.vision.flight.domain.port;

import com.drones.vision.flight.domain.model.CommandResult;
import com.drones.vision.warehouse.domain.model.Device;
import com.drones.vision.flight.domain.model.FlightCapability;

/**
 * Driven port: send a flight command to the aircraft behind a device (docs/plans/active/DRONE-INFRA-PLAN.md
 * I-e — guarded command TX). The RX-only doctrine ({@code docs/main/CYCLES-PLAN.md} §0) ends only
 * here, deliberately, and only this far. Stage 1 opened it for a single command, return-to-home;
 * Stage 2 (docs/plans/active/DRONE-INFRA-PLAN.md I-e Stage 2) adds the two next capability-gated command
 * classes — arbitrary mode select and arm/disarm — plus a {@link #capabilities(Device)} snapshot
 * a UI drives itself from. There is still intentionally no fully generic command surface (arbitrary
 * MAVLink opcodes, mission/fence upload) — those are later, separately-gated stages of the same
 * plan, each needing its own deliberate go-ahead.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #supports(Device)} must be checked first — not every device this platform ingests
 *       telemetry from is necessarily commandable by a given implementation.</li>
 *   <li>{@link #setMode(Device, String)}, {@link #arm(Device, boolean)}, {@link #disarm(Device,
 *       boolean)} and {@link #returnToHome(Device)} each send one command to the aircraft behind
 *       {@code device} and wait briefly for the aircraft's own acknowledgement before returning.
 *       They are not fire-and-forget: the call blocks for up to an implementation-defined short
 *       timeout.</li>
 *   <li>{@link #capabilities(Device)} is the one non-blocking, never-throwing method — a
 *       best-effort snapshot of what the vehicle can be sent, from what it has already been heard
 *       to report.</li>
 * </ul>
 *
 * <h2>Exceptions (the four command methods)</h2>
 * <ul>
 *   <li>{@link IllegalArgumentException} — {@code device} is not a device this implementation can
 *       command at all (see {@link #supports(Device)}), or it is a supported device whose aircraft
 *       cannot be sent this command: an unrecognized or never-yet-heard firmware, a firmware whose
 *       own remote-control link is known not to process MAVLink commands (e.g. Betaflight — its
 *       receiver implements neither {@code MAV_CMD_DO_SET_MODE} nor {@code
 *       MAV_CMD_COMPONENT_ARM_DISARM} even though its own mode table happens to include an RTL-like
 *       mode number), or (for {@link #setMode(Device, String)}) a mode name the vehicle's firmware
 *       does not define.</li>
 *   <li>{@link IllegalStateException} — the aircraft was reachable and answered, but its own
 *       acknowledgement explicitly refused the command (denied / unsupported / failed).</li>
 * </ul>
 * Neither exception covers a lost or missing acknowledgement — a command that may or may not have
 * landed is a normal, non-exceptional outcome; see {@link CommandResult#NO_ACK}.
 *
 * <h2>Threading</h2>
 * Every method here must be safe to call concurrently for different devices.
 */
public interface FlightCommandPort {

    /**
     * Whether this adapter can send commands to the aircraft behind the given device.
     *
     * @param device the device to check
     * @return {@code true} if this adapter can attempt a command for it (this does not by itself
     *         guarantee any command will succeed — see the exceptions the command methods may still
     *         throw, and the finer-grained {@link #capabilities(Device)} snapshot)
     */
    boolean supports(Device device);

    /**
     * Commands the aircraft behind {@code device} into the named flight mode and waits for its
     * acknowledgement.
     *
     * @param device   the device whose aircraft should change mode
     * @param modeName the target mode name as this platform reports it (e.g. {@code "Loiter"},
     *                 {@code "RTL"}) — one of {@link FlightCapability#selectableModes()}
     * @return {@link CommandResult#ACCEPTED} once the aircraft's own acknowledgement confirms it,
     *         or {@link CommandResult#NO_ACK} if none arrived within the timeout
     * @throws IllegalArgumentException if {@code device} is unsupported, its firmware is not
     *                                   commandable, or {@code modeName} is unknown for the vehicle
     * @throws IllegalStateException    if the aircraft explicitly refused the command
     */
    CommandResult setMode(Device device, String modeName);

    /**
     * Commands the aircraft behind {@code device} to arm (spin up its motors) and waits for its
     * acknowledgement. <b>Arming is the highest-danger action this port exposes</b> — callers gate
     * it accordingly.
     *
     * @param device the device whose aircraft should arm
     * @param force  when {@code true}, request a forced arm that bypasses the autopilot's own
     *               pre-arm safety checks (the MAVLink {@code 21196} magic value in the command's
     *               force parameter); when {@code false}, a normal arm the autopilot may refuse if
     *               its pre-arm checks fail
     * @return {@link CommandResult#ACCEPTED} or {@link CommandResult#NO_ACK}, same contract as
     *         {@link #setMode(Device, String)}
     * @throws IllegalArgumentException if {@code device} is unsupported or its firmware is not
     *                                   commandable
     * @throws IllegalStateException    if the aircraft explicitly refused the command
     */
    CommandResult arm(Device device, boolean force);

    /**
     * Commands the aircraft behind {@code device} to disarm (stop its motors) and waits for its
     * acknowledgement.
     *
     * @param device the device whose aircraft should disarm
     * @param force  when {@code true}, request a forced disarm that bypasses the autopilot's own
     *               checks (the MAVLink {@code 21196} magic value in the command's force parameter)
     *               — the caller is responsible for the crash risk of forcing a disarm mid-flight;
     *               when {@code false}, a normal disarm the autopilot may refuse (e.g. while flying)
     * @return {@link CommandResult#ACCEPTED} or {@link CommandResult#NO_ACK}, same contract as
     *         {@link #setMode(Device, String)}
     * @throws IllegalArgumentException if {@code device} is unsupported or its firmware is not
     *                                   commandable
     * @throws IllegalStateException    if the aircraft explicitly refused the command
     */
    CommandResult disarm(Device device, boolean force);

    /**
     * Sends a return-to-home (return-to-launch) command to the aircraft behind {@code device} and
     * waits for its acknowledgement. Kept for Stage-1 compatibility; equivalent to {@code
     * setMode(device, "RTL")}.
     *
     * @param device the device whose aircraft should return home
     * @return {@link CommandResult#ACCEPTED} once the aircraft's own acknowledgement confirms it,
     *         or {@link CommandResult#NO_ACK} if none arrived within the timeout
     * @throws IllegalArgumentException if {@code device} is unsupported, or the aircraft's
     *                                   firmware has no invocable return-to-home capability
     * @throws IllegalStateException    if the aircraft explicitly refused the command
     */
    CommandResult returnToHome(Device device);

    /**
     * A best-effort snapshot of what commands the aircraft behind {@code device} currently accepts,
     * derived entirely from the firmware/vehicle-family this platform has most recently heard it
     * report. Never throws and never blocks on the network: an unsupported, never-heard, or
     * non-commandable device (e.g. Betaflight) reports {@link FlightCapability#notCommandable()}.
     * A commandable vehicle whose remote link may in fact ignore the command (INAV, indistinguishable
     * from ArduPilot on the wire) still reports commandable — the command is honestly <em>attempted</em>
     * and simply returns {@link CommandResult#NO_ACK} if ignored, rather than being hidden here.
     *
     * @param device the device to describe
     * @return the capability snapshot; {@link FlightCapability#notCommandable()} when nothing is
     *         known or the vehicle cannot be commanded
     */
    FlightCapability capabilities(Device device);
}
