package com.drones.vision.flight.domain.port;

import com.drones.vision.flight.domain.model.MessageIntervalOutcome;
import com.drones.vision.flight.domain.model.ParameterReading;
import com.drones.vision.flight.domain.model.ParameterWriteOutcome;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.warehouse.domain.model.Device;

import java.time.Duration;
import java.util.List;

/**
 * Driven port: talk to a vehicle over its own protocol for onboarding purposes (docs/plans/active/
 * DRONE-ONBOARDING-PLAN.md section 3.1 PROBE/CONFIGURE). One real implementation is expected in
 * {@code drone-link/mavlink}'s {@code MavlinkVehicleConfigurator} (O4), riding the same shared
 * {@code MavlinkGateway} socket {@code MavlinkHeartbeatScanner} already borrows -- this port opens
 * no new connection of its own.
 *
 * <p>Every action method takes a {@code linkKey} (e.g. {@code "udp://0.0.0.0:14550#7"}), mirroring
 * the plan's own sequence diagram (section 3.2: {@code VehicleConfigPort.probe(linkKey, window)}) --
 * not a {@link Device}, because the pre-registration probe stage (section 3.1 PROBE) runs before any
 * {@code DeviceId} exists at all. A per-asset caller derives the {@code linkKey} from the resolved
 * device's own stream descriptor first.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #supports(Device)} decides whether a <em>registered</em> device is one this
 *       implementation can talk to at all -- checked before deriving a {@code linkKey} from it.</li>
 *   <li>{@link #probe(String, Duration)} never throws for an incomplete answer -- an unanswered
 *       {@code AUTOPILOT_VERSION} or parameter read is reported via {@link
 *       VehicleProfile#complete()}/{@link VehicleProfile#incompleteReason()}, never fabricated
 *       (C7).</li>
 *   <li>{@link #requestMessageInterval} and {@link #writeParam} each send one command and wait for
 *       the aircraft's own acknowledgement (or its absence) before returning; neither is
 *       fire-and-forget.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * Every method must be safe to call concurrently for different {@code linkKey}s.
 */
public interface VehicleConfigPort {

    /**
     * Whether this implementation can talk to the aircraft behind {@code device} at all -- checked
     * before a per-asset caller derives a {@code linkKey} from it, mirroring {@code
     * FlightCommandPort#supports}.
     */
    boolean supports(Device device);

    /**
     * Passively inventories what a vehicle sends and actively requests its capability/parameter
     * evidence, for at most {@code window}.
     *
     * @param linkKey the candidate's identity ({@code "udp://host:port#sysid"})
     * @param window  the probe budget; {@link VehicleProfile#complete()} is {@code false} with a
     *               reason when the window elapses before every half answered
     * @return the observed snapshot; never {@code null}
     */
    VehicleProfile probe(String linkKey, Duration window);

    /**
     * Mechanism A (docs/plans/active/DRONE-ONBOARDING-PLAN.md section 4a) -- a runtime {@code
     * MAV_CMD_SET_MESSAGE_INTERVAL} request. Not a write: nothing persists, nothing survives a
     * reboot.
     *
     * @param linkKey   the target vehicle
     * @param messageId the MAVLink message id to (re)request
     * @param interval  the requested interval; {@link Duration#ZERO} requests "disable"
     * @return the outcome, never {@code null}
     */
    MessageIntervalOutcome requestMessageInterval(String linkKey, int messageId, Duration interval);

    /**
     * Reads named parameters. A parameter never answered simply has no entry in the result --
     * {@link VehicleProfile}'s own "only what was actually read" rule (section 5.3) applies equally
     * here.
     *
     * @param linkKey        the target vehicle
     * @param parameterNames the parameter names to read
     * @return the readings actually obtained, possibly fewer than requested; never {@code null}
     */
    List<ParameterReading> readParams(String linkKey, List<String> parameterNames);

    /**
     * Mechanism B (docs/plans/active/DRONE-ONBOARDING-PLAN.md section 4a) -- a single {@code
     * PARAM_SET}, with snapshot-before/read-back-after already applied by the implementation: a
     * read-back that differs from {@code value} must be reported as {@link
     * com.drones.vision.flight.domain.model.RemediationResultCode#DENIED}, with the previous value
     * restored, not as {@code ACCEPTED}. Tier gating and the disarmed-only interlock are the
     * caller's job (section 6.2) -- this method performs the write it is asked to perform.
     *
     * @param linkKey       the target vehicle
     * @param parameterName the parameter to write
     * @param value         the requested value
     * @return the outcome, never {@code null}
     */
    ParameterWriteOutcome writeParam(String linkKey, String parameterName, double value);
}
