package com.drones.vision.flight.application.telemetry;

import com.drones.vision.kernel.Telemetry;
import com.drones.vision.kernel.UsageId;
import com.drones.vision.flight.domain.port.TelemetryRepositoryPort;

/**
 * Owns telemetry-sample persistence — the flight half of
 * docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md D1/R3: vision-perception's {@code
 * UsageTracker} used to hold {@link TelemetryRepositoryPort} directly and call {@code save} on it
 * on every sample; this service is the ownership seam that replaces that direct port import,
 * deliberately kept to exactly the one write {@code UsageTracker} needs.
 *
 * <p>Deliberately minimal: this is an ownership seam, not a feature. It exists so nothing outside
 * vision-flight imports {@link TelemetryRepositoryPort}, not to grow into a broader telemetry
 * read/query API — {@code findByUsage} reads are a separate concern for whichever caller needs
 * them (e.g. a replay/trail view), not this service's job.
 */
public interface TelemetryService {

    /**
     * Appends one telemetry sample for the given usage.
     *
     * @param usageId   the usage this sample belongs to
     * @param telemetry the sample to persist
     */
    void record(UsageId usageId, Telemetry telemetry);
}
