package com.drones.vision.api.support;

import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.asset.AssetSummary;
import com.drones.vision.warehouse.domain.model.Asset;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Builds the hand-rolled CSV export behind {@code GET /api/inventory/export?format=csv}
 * (docs/plans/active/WAREHOUSE-UX-PLAN.md &sect;3.3) — one row per asset the caller's {@link
 * VisibilityScope} includes, in the fixed column order a spreadsheet-import tool expects rather
 * than the JSON shape of {@code AssetSummaryResponse}. Deliberately not a generic CSV library
 * dependency: thirteen fixed columns over a bounded fleet size does not earn one.
 *
 * <p>Deleted assets are excluded, matching every other fleet-facing read's default (see {@link
 * AssetService#assets()}'s own default) — an export is a snapshot of the working fleet, not an
 * archive dump.
 */
public final class InventoryExportService {

    /** Column order, frozen as the wire contract of the export — see this class's own javadoc. */
    private static final List<String> HEADERS = List.of("id", "name", "category", "serial", "make", "model",
            "registration", "inventoryState", "custodian", "location", "lifecycle", "createdAt", "lastFlownAt");

    private final AssetService assetService;

    public InventoryExportService(AssetService assetService) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
    }

    /**
     * Renders every asset {@code scope} includes as CSV, header row first.
     *
     * @param scope the caller's visibility scope
     * @return the full CSV document, {@code \n}-terminated lines, RFC 4180-style quoting for any
     *         field containing a comma, quote, or newline
     */
    public String toCsv(VisibilityScope scope) {
        StringBuilder csv = new StringBuilder();
        csv.append(String.join(",", HEADERS)).append('\n');
        for (AssetSummary summary : assetService.assets(scope, false)) {
            csv.append(row(summary)).append('\n');
        }
        return csv.toString();
    }

    private static String row(AssetSummary summary) {
        Asset asset = summary.asset();
        String custodianId = summary.custody().custodianId() == null ? null
                : summary.custody().custodianId().value().toString();
        String lastFlownAt = summary.lastUsedAt() == null ? null : summary.lastUsedAt().toString();
        return Arrays.stream(new String[] {
                asset.id().value().toString(),
                asset.displayName(),
                summary.categoryName(),
                summary.identity().serialNumber(),
                summary.identity().make(),
                summary.identity().model(),
                summary.identity().registration(),
                summary.inventoryState().name(),
                custodianId,
                summary.custody().location(),
                asset.state().name(),
                asset.createdAt().toString(),
                lastFlownAt
        }).map(InventoryExportService::escape).collect(Collectors.joining(","));
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
