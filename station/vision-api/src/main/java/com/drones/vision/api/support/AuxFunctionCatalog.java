package com.drones.vision.api.support;

import java.util.List;
import java.util.Objects;

/**
 * The ArduPilot auxiliary functions this deployment offers in the controller-setup picker
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md §2.3, decision C5).
 *
 * <p>Configuration rather than code because the list is firmware's, not ours: ArduPilot adds
 * {@code RCx_OPTION} numbers every release, and a station flying a newer firmware than this jar was
 * built against must be able to offer one without a rebuild (CLAUDE.md rule 1). {@link #defaults()}
 * mirrors {@code vision.control.aux-functions} in {@code application.yaml}; {@code vision-app} binds
 * the property onto an instance of this record, the same framework-free bridge {@link
 * OnboardingProperties} documents — {@code vision-api} may not depend on {@code vision-app}.
 *
 * <p>Nothing here is a whitelist: {@code POST /api/assets/{id}/aux-function} accepts any number in
 * range, because an operator who knows their airframe's option number should not be blocked by this
 * list being short. It is a menu, not a gate.
 *
 * @param functions the offered functions, in the order they should be shown
 */
public record AuxFunctionCatalog(List<Function> functions) {

    /**
     * One offered auxiliary function.
     *
     * @param number the {@code RCx_OPTION} number ArduPilot knows it by
     * @param label  what to call it in front of an operator
     */
    public record Function(int number, String label) {

        public Function {
            if (number < 0) {
                throw new IllegalArgumentException("Aux function number must not be negative: " + number);
            }
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("Aux function " + number + " must have a label");
            }
        }
    }

    public AuxFunctionCatalog {
        functions = List.copyOf(Objects.requireNonNull(functions, "functions must not be null"));
    }

    /**
     * The representative set surveyed in CONTROLLER-SETUP-CONTEXT.md §2.3 — the switch-driven
     * features an operator of this platform is most likely to want, not ArduPilot's full table.
     *
     * @return the default catalogue, mirroring {@code application.yaml}
     */
    public static AuxFunctionCatalog defaults() {
        return new AuxFunctionCatalog(List.of(
                new Function(4, "Return to launch"),
                new Function(9, "Camera trigger"),
                new Function(16, "Auto mode"),
                new Function(18, "Land"),
                new Function(19, "Gripper"),
                new Function(22, "Parachute release"),
                new Function(31, "Motor emergency stop"),
                new Function(46, "RC override enable"),
                new Function(55, "Guided mode"),
                new Function(56, "Loiter mode"),
                new Function(81, "Disarm"),
                new Function(153, "Arm/disarm"),
                new Function(165, "Arm / emergency stop")));
    }
}
