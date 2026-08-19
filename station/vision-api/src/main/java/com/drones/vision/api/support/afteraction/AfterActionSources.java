package com.drones.vision.api.support.afteraction;

import com.drones.vision.flight.application.VehicleProfileService;
import com.drones.vision.map.application.mark.MarkService;
import com.drones.vision.platform.AuditTrailPort;

import java.util.Objects;

/**
 * Bundles the three cross-context read collaborators {@link AfterActionAssembler} needs beyond
 * {@code AssetService}/{@code ReplayService}, purely to stay at the five-constructor-parameter
 * ceiling (.claude/skills/java-clean-code/SKILL.md &sect;3) — the same reason {@code
 * ReplaySources} (vision-events) and {@code TrainingStores} (vision-learning) exist. Each member
 * remains an independently-substitutable service/port; nothing about the bundle itself is a real
 * collaborator.
 *
 * @param markService          resolves marks visible to the requesting viewer (D5/D6)
 * @param vehicleProfileService resolves the flight passport (O11/O13)
 * @param auditTrailPort       resolves this asset's audit trail, gated on the viewer's own
 *                             {@code VisibilityScope#canManageOrg()} inside the assembler, mirroring
 *                             {@code AuditController}'s own gate exactly
 */
public record AfterActionSources(MarkService markService, VehicleProfileService vehicleProfileService,
                                  AuditTrailPort auditTrailPort) {

    public AfterActionSources {
        Objects.requireNonNull(markService, "markService must not be null");
        Objects.requireNonNull(vehicleProfileService, "vehicleProfileService must not be null");
        Objects.requireNonNull(auditTrailPort, "auditTrailPort must not be null");
    }
}
