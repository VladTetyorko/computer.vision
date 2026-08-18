package com.drones.vision.app.devsupport;

import com.drones.vision.flight.domain.model.MessageIntervalOutcome;
import com.drones.vision.flight.domain.model.ParameterReading;
import com.drones.vision.flight.domain.model.ParameterWriteOutcome;
import com.drones.vision.flight.domain.model.VehicleProfile;
import com.drones.vision.flight.domain.port.VehicleConfigPort;
import com.drones.vision.warehouse.domain.model.Device;

import java.time.Duration;
import java.util.List;

/**
 * No-op {@link VehicleConfigPort}: {@link #supports} always {@code false}, every other method
 * throws — the flag-off ({@code vision.onboarding.probe.enabled=false}, D17) and
 * no-real-implementation-yet fallback (docs/plans/active/DRONE-ONBOARDING-PLAN.md O5; {@code
 * adapter-mavlink}'s {@code MavlinkVehicleConfigurator} is O4, a separate, concurrent wave this one
 * only defines wiring for).
 *
 * <p>{@link #probe}'s message is byte-identical to §8.1's frozen {@code POST /api/onboarding/probe}
 * 409 body ({@code "vehicle probing is disabled (vision.onboarding.probe.enabled)"}) — {@code
 * VehicleProfileService#probeCandidate} calls this method directly with no {@code supports()} check
 * in front of it (O3 MODULE.md), so this is the literal text that must reach the wire. The other
 * methods' messages are not wire-frozen (only the pre-registration probe's 409 body is a literal
 * string in §8.1); they still name the same flag so an operator sees one consistent reason
 * everywhere the guardrail bites.
 *
 * <p><strong>Deliberately no bean when {@code vision.onboarding.probe.enabled=true}</strong> (see
 * {@code OnboardingWiringConfiguration}): unlike {@link NoopDetectionPort} (a permanent, always-on
 * fallback), this class only ever backs the flag-<em>off</em> state. Flipping the flag on before
 * O4's real implementation is wired would otherwise silently keep every onboarding endpoint
 * behaving as if disabled — a fabricated "it's working" that the plan's own C7 honesty doctrine
 * forbids. Failing application startup (no {@link VehicleConfigPort} bean at all) is the honest
 * failure mode until O4 lands.
 */
public final class NoopVehicleConfigPort implements VehicleConfigPort {

    /** Matches §8.1's frozen {@code POST /api/onboarding/probe} 409 body, verbatim. */
    static final String DISABLED_MESSAGE = "vehicle probing is disabled (vision.onboarding.probe.enabled)";

    @Override
    public boolean supports(Device device) {
        return false;
    }

    @Override
    public VehicleProfile probe(String linkKey, Duration window) {
        throw new IllegalStateException(DISABLED_MESSAGE);
    }

    @Override
    public MessageIntervalOutcome requestMessageInterval(String linkKey, int messageId, Duration interval) {
        throw new IllegalStateException(DISABLED_MESSAGE);
    }

    @Override
    public List<ParameterReading> readParams(String linkKey, List<String> parameterNames) {
        throw new IllegalStateException(DISABLED_MESSAGE);
    }

    @Override
    public ParameterWriteOutcome writeParam(String linkKey, String parameterName, double value) {
        throw new IllegalStateException(DISABLED_MESSAGE);
    }
}
