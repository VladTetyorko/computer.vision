package com.drones.vision.api.support;

import com.drones.vision.kernel.Capability;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared conversion for the optional {@code capabilities} field on {@code RegisterDeviceRequest}
 * and {@code CreateAssetRequest.DeviceSpec} (both {@code ...api.dto}).
 *
 * <p>Public only because it now lives in {@code ...api.support}, a different package from its
 * {@code ...api.dto} callers (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row B) — this is still
 * de-duplicated request-parsing logic, not part of the wire contract itself, and still has no
 * second implementation ({@code .claude/skills/java-clean-code/SKILL.md} §1).
 *
 * <h2>Why the default depends on the protocol</h2>
 * A flat {@code VIDEO} default silently broke every telemetry-only device a client registered
 * without naming capabilities (docs/plans/active/TELEMETRY-ONLY-ONBOARDING-CONTEXT.md §2 B2). The
 * device was created and looked fine, but {@code MavlinkTelemetrySource#supports} requires {@code
 * TELEMETRY}, so {@code UsageTracker} never subscribed to it and {@code MavlinkManualControlSender}
 * — which delegates to that same predicate — refused to command it. The failure was invisible:
 * a saved, healthy-looking device that could never report a position or take a stick input.
 * {@link #parse(List, String)} closes that by defaulting from the one fact the request always
 * carries alongside the missing capabilities: the protocol.
 */
public final class CapabilityParsing {

    /**
     * The one protocol in this codebase that is telemetry and nothing else. Declared locally, as
     * {@code DefaultSimulationService} declares its own copy — {@code vision-api} may not depend on
     * {@code adapter-mavlink}, where the authoritative constant lives (ArchUnit-enforced).
     */
    private static final String PROTOCOL_MAVLINK = "mavlink";

    private CapabilityParsing() {
    }

    /**
     * Parses capability names into a {@link Capability} set.
     *
     * @param names capability names matched against {@link Capability#name()}
     *              case-insensitively; {@code null} or empty defaults to
     *              {@code Set.of(Capability.VIDEO)} (backward-compatible with
     *              the Phase-1 VIDEO-only default)
     * @return the resolved, non-empty capability set
     * @throws IllegalArgumentException if any name doesn't match a known
     *                                   {@link Capability}, listing the valid values
     */
    public static Set<Capability> parse(List<String> names) {
        return parse(names, null);
    }

    /**
     * Parses capability names into a {@link Capability} set, defaulting from {@code protocol} when
     * the caller named none.
     *
     * <p>Defaulting only — an explicitly supplied {@code names} always wins, so every existing
     * caller that already names its capabilities is unaffected.
     *
     * @param names    capability names matched against {@link Capability#name()} case-insensitively;
     *                 {@code null} or empty defers to {@code protocol}
     * @param protocol the device's protocol key, used only to pick a default; {@code null} (or any
     *                 protocol with no telemetry adapter behind it) keeps the historical
     *                 {@code [VIDEO]}
     * @return the resolved, non-empty capability set
     * @throws IllegalArgumentException if any name doesn't match a known {@link Capability}
     */
    public static Set<Capability> parse(List<String> names, String protocol) {
        if (names == null || names.isEmpty()) {
            return defaultsFor(protocol);
        }
        Set<Capability> capabilities = new LinkedHashSet<>();
        for (String name : names) {
            capabilities.add(toCapability(name));
        }
        return Set.copyOf(capabilities);
    }

    /**
     * What a device of this protocol exposes when the request said nothing.
     *
     * <p>{@code sim} is deliberately <em>not</em> given {@code TELEMETRY} here even though
     * {@code SimulatedTelemetrySource} would claim it: {@code DefaultSimulationService} already
     * builds its simulated devices with explicit capabilities, and the wizard's {@code synthetic}
     * option promises "no telemetry" in as many words. Widening the default would make that copy a
     * lie and open a telemetry source nobody asked for — a real behaviour change, not a bug fix.
     */
    private static Set<Capability> defaultsFor(String protocol) {
        if (protocol != null && PROTOCOL_MAVLINK.equalsIgnoreCase(protocol.trim())) {
            return Set.of(Capability.TELEMETRY);
        }
        return Set.of(Capability.VIDEO);
    }

    private static Capability toCapability(String name) {
        if (name != null) {
            for (Capability capability : Capability.values()) {
                if (capability.name().equalsIgnoreCase(name)) {
                    return capability;
                }
            }
        }
        throw new IllegalArgumentException("Unknown capability: " + name + " (valid values: "
                + Arrays.stream(Capability.values()).map(Enum::name).collect(Collectors.joining(", ")) + ")");
    }
}
