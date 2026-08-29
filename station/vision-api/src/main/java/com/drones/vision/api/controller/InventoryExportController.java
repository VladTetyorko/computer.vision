package com.drones.vision.api.controller;

import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.support.InventoryExportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Driving REST adapter for {@code GET /api/inventory/export} (docs/plans/active/
 * WAREHOUSE-UX-PLAN.md &sect;3.3) — a two-collaborator controller split off {@link
 * AssetInventoryController}/{@link AssetController} for the same reason those two are already
 * split from each other: one more collaborator here would push whichever controller absorbed it
 * past .claude/skills/java-clean-code/SKILL.md &sect;3's five-parameter ceiling.
 */
@RestController
public class InventoryExportController {

    private final InventoryExportService inventoryExportService;
    private final CurrentUser currentUser;

    public InventoryExportController(InventoryExportService inventoryExportService, CurrentUser currentUser) {
        this.inventoryExportService =
                Objects.requireNonNull(inventoryExportService, "inventoryExportService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Exports every asset the caller's scope includes as CSV.
     *
     * @param format only {@code csv} is supported today; defaults to {@code csv} when absent
     * @return the CSV document as an attachment download
     * @throws IllegalArgumentException if {@code format} is present and not {@code csv} (400)
     */
    @GetMapping("/api/inventory/export")
    public ResponseEntity<String> export(@RequestParam(required = false, defaultValue = "csv") String format) {
        if (!"csv".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("Unsupported export format: " + format + " (only csv is supported)");
        }
        String csv = inventoryExportService.toCsv(currentUser.scope());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("inventory.csv", StandardCharsets.UTF_8).build()
                                .toString())
                .body(csv);
    }
}
