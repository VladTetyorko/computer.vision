package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Configuration for controller setup ({@code vision.control.*}), docs/plans/active/
 * CONTROLLER-SETUP-CONTEXT.md decision C5/C8.
 *
 * <p>Only the auxiliary-function <em>menu</em> is configurable, and only because that list is
 * ArduPilot's rather than ours: the firmware adds {@code RCx_OPTION} numbers every release, and a
 * station flying newer firmware than this jar was built against must be able to offer one without a
 * rebuild (CLAUDE.md rule 1). Everything else the setup page offers — input kinds, switch positions,
 * channel functions, actions — is a property of what this platform can actually send, so it comes
 * from the domain's own enums and is deliberately not configurable.
 *
 * <p>Left empty (the default), {@code ControlProfileWiring} falls back to {@code
 * AuxFunctionCatalog.defaults()}; {@code application.yaml} documents the key rather than restating
 * the list, exactly as the {@code vision.rc} block documents its own defaults.
 *
 * @param auxFunctions the aux functions to offer, in display order; empty means "use the built-in
 *                     catalogue"
 */
@ConfigurationProperties(prefix = "vision.control")
public record VisionControlProperties(@DefaultValue List<AuxFunction> auxFunctions) {

    /**
     * One offered auxiliary function.
     *
     * @param number the {@code RCx_OPTION} number ArduPilot knows it by
     * @param label  what to call it in front of an operator
     */
    public record AuxFunction(int number, String label) {
    }

    public VisionControlProperties {
        auxFunctions = List.copyOf(auxFunctions == null ? List.of() : auxFunctions);
    }
}
