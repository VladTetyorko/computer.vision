package com.drones.vision.warehouse.application.maintenance;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.MaintenanceRecord;
import java.util.List;

/**
 * The one fact other contexts need from warehouse's maintenance records: whether an asset is
 * currently grounded. Read by vision-flight's readiness evaluation (docs/plans/active/
 * WAREHOUSE-UX-CONTEXT.md OQ1: an open blocker is a NO-GO, and {@code engage} refuses).
 *
 * <p>Filed in the application layer, not {@code domain/port}, mirroring {@code
 * UsageSessionService}: this module's {@code domain/port} interfaces are driven ports this module
 * calls outward into adapters, while a cross-context read contract that this module itself
 * implements belongs beside the service that implements it, so a caller in another context module
 * (vision-flight) never needs to import {@code domain.port} to reach it. {@link
 * DefaultMaintenanceService} is the one implementation, exactly like {@code
 * UsageSessionService}/{@code DefaultUsageSessionService}.
 *
 * <p>Deliberately unscoped: this is an internal, service-to-service call, not a user-facing read,
 * so it carries no {@link com.drones.vision.platform.VisibilityScope} — the caller has already
 * resolved and scope-checked the asset itself (see {@code DefaultVehicleProfileService#probe} for
 * the same pattern against {@code UsageSessionService}).
 */
public interface MaintenanceQuery {

    /**
     * Lists the currently-open, flight-blocking maintenance records for an asset — {@link
     * com.drones.vision.warehouse.domain.model.MaintenanceKind#blocksFlight()} ones only; an open
     * {@code REPAIR} or {@code NOTE} record is not a blocker and is excluded.
     *
     * @param assetId the asset id
     * @return an immutable snapshot of the asset's open blocking records; empty means the asset is
     *         clear to fly as far as maintenance is concerned
     */
    List<MaintenanceRecord> openBlockers(AssetId assetId);
}
