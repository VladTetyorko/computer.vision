package com.drones.vision.warehouse.application.maintenance;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.warehouse.domain.model.MaintenanceRecord;

/**
 * A read-model view of one fleet-wide maintenance record — {@link MaintenanceRecord} plus the
 * asset facts {@code GET /api/maintenance} needs so a fleet-wide table needs no per-asset follow-up
 * call (docs/plans/active/WAREHOUSE-UX-PLAN.md &sect;3.3, D5; docs/plans/active/
 * WAREHOUSE-UX-CONTEXT.md W7 handoff: "the Maintenance page needs one call, not O(grounded)"). The
 * same flattening choice {@link com.drones.vision.warehouse.application.asset.AssetSummary} makes
 * for {@code categoryName}, so a list view never needs to reach through a separate asset lookup.
 *
 * @param record     the underlying maintenance record
 * @param assetName  the asset's display name at read time
 * @param categoryId the asset's category slug
 */
public record MaintenanceRecordSummary(MaintenanceRecord record, String assetName, CategoryId categoryId) {

    public MaintenanceRecordSummary {
        if (record == null) {
            throw new IllegalArgumentException("MaintenanceRecordSummary record must not be null");
        }
        if (assetName == null || assetName.isBlank()) {
            throw new IllegalArgumentException("MaintenanceRecordSummary assetName must not be blank");
        }
        if (categoryId == null) {
            throw new IllegalArgumentException("MaintenanceRecordSummary categoryId must not be null");
        }
    }
}
