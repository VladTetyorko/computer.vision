package com.drones.vision.api.dto;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.kernel.UsageOrigin;
import com.drones.vision.warehouse.domain.model.AssetUsage;
import com.drones.vision.warehouse.domain.model.UsagePhase;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the one thing that made {@code phase}/{@code origin} worth adding to this DTO: each is
 * computed by {@code UsageTracker} and stored by {@code AssetUsageMapper}, so a mapping that
 * quietly dropped either here would leave the value correct everywhere except where anyone can see
 * it — and no other test in this module would notice (docs/plans/active/DRONE-ONBOARDING-PLAN.md O7;
 * {@code origin} added docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D2, wave R2).
 */
class AssetUsageResponseTest {

    @Test
    void thePhaseAUsageIsInSurvivesTheTripToTheWire() {
        AssetUsage inFlight = usage().withPhase(UsagePhase.IN_FLIGHT);

        assertEquals("IN_FLIGHT", AssetUsageResponse.from(inFlight).phase());
    }

    @Test
    void aUsageThatHasNotArmedYetReportsPreflightRatherThanNothing() {
        assertEquals("PREFLIGHT", AssetUsageResponse.from(usage()).phase(),
                "an absent phase would read as 'unknown' in the UI, which is a different claim");
    }

    @Test
    void theOriginAUsageWasOpenedBySurvivesTheTripToTheWire() {
        AssetUsage operatorEngaged = usage().withOrigin(UsageOrigin.OPERATOR);

        assertEquals("OPERATOR", AssetUsageResponse.from(operatorEngaged).origin());
    }

    @Test
    void aUsageOpenedByAStreamReportsStreamRatherThanNothing() {
        assertEquals("STREAM", AssetUsageResponse.from(usage()).origin(),
                "this fixture's origin is explicitly STREAM");
    }

    private static AssetUsage usage() {
        return new AssetUsage(UsageId.random(), AssetId.random(), Instant.parse("2026-08-18T12:00:00Z"),
                null, null, null, 0L, null, UsagePhase.PREFLIGHT, UsageOrigin.STREAM);
    }
}
