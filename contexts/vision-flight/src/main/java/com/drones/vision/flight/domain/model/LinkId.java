package com.drones.vision.flight.domain.model;

import java.util.Objects;

/**
 * A link's stable identity, mirrored from {@code mavlink-core}'s {@code
 * com.drones.mavlink.transport.LinkId} (LINK-PAIRING-PLAN.md §3.4/§3.1). A local, identically-shaped
 * copy rather than a direct import: this module's {@code domain}/{@code application} packages may
 * depend only on {@code ..domain..}/{@code ..kernel..}/{@code ..platform..}/{@code java..}
 * (station/vision-app's {@code ArchitectureTest#domainDependsOnlyOnDomainAndJava}/{@code
 * #applicationDependsOnlyOnApplicationDomainAndJava}), so a context module can never import a
 * {@code com.drones.mavlink} type directly — mirroring this codebase's existing precedent for
 * {@code RcChannels} (see {@code mavlink-core}'s own MODULE.md Gotchas). {@code
 * com.drones.vision.adapter.mavlink.MavlinkVehicleLinkPort} (the sole implementer of {@link
 * com.drones.vision.flight.domain.port.VehicleLinkPort}) is where the translation between the two
 * happens.
 */
public record LinkId(String value) {

    public LinkId {
        Objects.requireNonNull(value, "value must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("LinkId value must not be blank");
        }
    }
}
