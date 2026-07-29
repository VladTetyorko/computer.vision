package com.drones.vision.domain.port.out;

import com.drones.vision.domain.model.CommandResult;
import com.drones.vision.domain.model.Device;

/**
 * Driven port: send a flight command to the aircraft behind a device (docs/DRONE-INFRA-PLAN.md
 * I-e — guarded command TX). The RX-only doctrine ({@code docs/CYCLES-PLAN.md} §0) ends only
 * here, deliberately, and only this far: this port exists for exactly one command today, return-
 * to-home (Stage 1, "bring it home"). There is intentionally no generic command surface (arbitrary
 * MAVLink commands, arm/disarm, mode select, mission upload) — those are later, separately-gated
 * stages of the same plan, each needing its own deliberate go-ahead and (from Stage 2 on) role
 * enforcement this port does not attempt.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #supports(Device)} must be checked first — not every device this platform ingests
 *       telemetry from is necessarily commandable by a given implementation.</li>
 *   <li>{@link #returnToHome(Device)} sends a return-to-launch command to the aircraft behind
 *       {@code device} and waits briefly for the aircraft's own acknowledgement before returning.
 *       It is not fire-and-forget: the call blocks for up to an implementation-defined short
 *       timeout.</li>
 * </ul>
 *
 * <h2>Exceptions</h2>
 * <ul>
 *   <li>{@link IllegalArgumentException} — {@code device} is not a device this implementation can
 *       command at all (see {@link #supports(Device)}), or it is a supported device whose aircraft
 *       has no return-to-home capability this port can invoke: an unrecognized or never-yet-heard
 *       firmware, or a firmware whose own remote-control link is known not to process this command
 *       (e.g. Betaflight — its receiver does not implement {@code MAV_CMD_DO_SET_MODE} even though
 *       its own mode table happens to include an RTL-like mode number).</li>
 *   <li>{@link IllegalStateException} — the aircraft was reachable and answered, but its own
 *       acknowledgement explicitly refused the command (denied / unsupported / failed).</li>
 * </ul>
 * Neither exception covers a lost or missing acknowledgement — a command that may or may not have
 * landed is a normal, non-exceptional outcome; see {@link CommandResult#NO_ACK}.
 *
 * <h2>Threading</h2>
 * {@link #supports(Device)} and {@link #returnToHome(Device)} must be safe to call concurrently
 * for different devices.
 */
public interface FlightCommandPort {

    /**
     * Whether this adapter can send commands to the aircraft behind the given device.
     *
     * @param device the device to check
     * @return {@code true} if this adapter can attempt {@link #returnToHome(Device)} for it
     *         (this does not by itself guarantee the command will succeed — see the exceptions
     *         {@link #returnToHome(Device)} itself may still throw)
     */
    boolean supports(Device device);

    /**
     * Sends a return-to-home (return-to-launch) command to the aircraft behind {@code device} and
     * waits for its acknowledgement.
     *
     * @param device the device whose aircraft should return home
     * @return {@link CommandResult#ACCEPTED} once the aircraft's own acknowledgement confirms it,
     *         or {@link CommandResult#NO_ACK} if none arrived within the timeout
     * @throws IllegalArgumentException if {@code device} is unsupported, or the aircraft's
     *                                   firmware has no invocable return-to-home capability
     * @throws IllegalStateException    if the aircraft explicitly refused the command
     */
    CommandResult returnToHome(Device device);
}
