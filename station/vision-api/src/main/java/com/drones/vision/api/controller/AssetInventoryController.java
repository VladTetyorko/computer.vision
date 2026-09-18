package com.drones.vision.api.controller;

import com.drones.vision.api.dto.AssetDetailsResponse;
import com.drones.vision.api.dto.CreateMaintenanceRecordRequest;
import com.drones.vision.api.dto.CustodyActionRequest;
import com.drones.vision.api.dto.FleetMaintenanceRecordResponse;
import com.drones.vision.api.dto.InventoryActionRequest;
import com.drones.vision.api.dto.MaintenanceRecordResponse;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.identity.application.handover.HandoverService;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.warehouse.application.custody.AssetCustodyService;
import com.drones.vision.warehouse.application.maintenance.MaintenanceListState;
import com.drones.vision.warehouse.application.maintenance.MaintenanceService;
import com.drones.vision.warehouse.domain.model.MaintenanceId;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.port.AssetImageRepositoryPort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Driving REST adapter for the warehouse-to-field lifecycle (docs/plans/active/WAREHOUSE-UX-PLAN.md
 * &sect;3.4): custody (issue/return), inventory state (ground/release/retire), and maintenance
 * records. Split off {@link AssetController} rather than folded into it, the same reasoning that
 * class's own javadoc gives for {@link AssetStreamController} — adding {@link AssetCustodyService}
 * and {@link MaintenanceService} here would have pushed {@code AssetController}'s constructor two
 * past .claude/skills/java-clean-code/SKILL.md &sect;3's five-parameter ceiling.
 *
 * <p><b>Six collaborators, one past that ceiling</b> — the same documented exception {@link
 * AssetStreamController} (6) and {@link StreamController} (7) already carry. {@link HandoverService}
 * joined {@link AssetCustodyService} rather than replacing it: hand-over owns issue/return only
 * (docs/plans/active/INVENTORY-REWORK-PLAN.md D1/D2), while ground/release/retire stay warehouse's
 * own verbs and must not be re-homed in identity to save a parameter. The honest fix is to split the
 * four {@code /maintenance} endpoints onto their own controller, which would leave five here and two
 * there; that is a larger move than this wave's additive scope allows, and is recorded as debt rather
 * than done quietly.
 *
 * <p><b>Authorisation lives in the services, not here</b> — the same pattern {@link
 * OnboardingController}'s own javadoc documents: {@link AssetCustodyService}'s custody/inventory
 * verbs and {@link MaintenanceService#open}/{@link MaintenanceService#close} resolve the asset
 * through {@link CurrentUser#authority()} and check {@code Authority#mayManageFleet} internally
 * (docs/plans/active/AUTH-ROLES-PLAN.md wave B6, superseding the bare {@code VisibilityScope#canManage}
 * check they used before); {@link MaintenanceService#listForAsset}/{@link MaintenanceService#fleetWide}
 * stay on {@link CurrentUser#scope()} instead, since those are reads checked on {@code includes}, not
 * authority. Every one auditing and throwing {@link java.util.NoSuchElementException}/{@link
 * com.drones.vision.platform.AccessDeniedException}/{@link IllegalStateException} themselves — mapped
 * centrally by {@link com.drones.vision.api.exception.ApiExceptionHandler}. This controller only
 * translates HTTP shape and forwards {@link CurrentUser#userId()}/{@link CurrentUser#authority()}/
 * {@link CurrentUser#scope()} as each service method requires.
 *
 * <p>Custody/inventory writes return the full {@link AssetDetailsResponse} (matching every other
 * mutation on {@link AssetController}); maintenance writes/reads return {@link
 * MaintenanceRecordResponse} instead, since opening/closing a record does not by itself change the
 * asset's own fields. {@link #fleetMaintenance} is the one fleet-wide read here — {@link
 * FleetMaintenanceRecordResponse} instead of {@link MaintenanceRecordResponse}, since a fleet-wide
 * table needs the asset's name/category alongside each record (docs/plans/active/WAREHOUSE-UX-PLAN.md
 * §3.3, D5).
 */
@RestController
public class AssetInventoryController {

    /**
     * Default {@code limit} for {@link #fleetMaintenance} when the caller omits it — generous for
     * this codebase's operating scale (see {@code DefaultFleetSummaryService#MAX_ASSETS_IN_SUMMARY}'s
     * own javadoc for the general stance on caps).
     */
    private static final int DEFAULT_FLEET_MAINTENANCE_LIMIT = 200;

    private final HandoverService handoverService;
    private final AssetCustodyService assetCustodyService;
    private final MaintenanceService maintenanceService;
    private final AssetService assetService;
    private final AssetImageRepositoryPort assetImageRepositoryPort;
    private final CurrentUser currentUser;

    public AssetInventoryController(HandoverService handoverService, AssetCustodyService assetCustodyService,
                                     MaintenanceService maintenanceService, AssetService assetService,
                                     AssetImageRepositoryPort assetImageRepositoryPort, CurrentUser currentUser) {
        this.handoverService = Objects.requireNonNull(handoverService, "handoverService must not be null");
        this.assetCustodyService =
                Objects.requireNonNull(assetCustodyService, "assetCustodyService must not be null");
        this.maintenanceService = Objects.requireNonNull(maintenanceService, "maintenanceService must not be null");
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.assetImageRepositoryPort =
                Objects.requireNonNull(assetImageRepositoryPort, "assetImageRepositoryPort must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * Issues an in-stock asset to a custodian, or returns an issued one to stock.
     *
     * <p><b>Issuing also grants the custodian the {@code PILOT} seat</b>
     * (docs/plans/active/INVENTORY-REWORK-PLAN.md D1) — one call, one decision. It used to write
     * custody alone, leaving the person holding the aircraft unable to fly it until somebody
     * separately assigned them; the fit-out wizard papered over that with a second client call, and
     * every other issue path simply produced the broken half-state
     * (docs/plans/active/INVENTORY-REWORK-CONTEXT.md §3, defect B). The composition itself lives in
     * {@link HandoverService}, not here: a controller sequencing two writes with a compensating undo
     * between them would be business logic in an adapter.
     *
     * <p><b>Returning does not revoke the seat</b> (D2) — authorisation to fly outlives possession of
     * the box, so a returned asset keeps its assignment and re-issuing to the same person is a no-op
     * on the roster.
     *
     * @param id      the asset
     * @param request the custody action to perform
     * @return the full detail view of the updated asset
     */
    @PostMapping("/api/assets/{id}/custody")
    public AssetDetailsResponse custody(@PathVariable String id, @RequestBody CustodyActionRequest request) {
        AssetId assetId = AssetId.of(id);
        switch (request.toAction()) {
            case ISSUE -> handoverService.issue(assetId, request.requireCustodianId(), request.location(),
                    currentUser.userId(), currentUser.authority());
            case RETURN -> handoverService.returnToStock(assetId, currentUser.userId(), currentUser.authority());
        }
        return detailsResponse(assetId);
    }

    /**
     * Grounds, releases, or retires an asset.
     *
     * @param id      the asset
     * @param request the inventory action to perform
     * @return the full detail view of the updated asset
     */
    @PostMapping("/api/assets/{id}/inventory")
    public AssetDetailsResponse inventory(@PathVariable String id, @RequestBody InventoryActionRequest request) {
        AssetId assetId = AssetId.of(id);
        switch (request.toAction()) {
            case GROUND -> assetCustodyService.ground(assetId, request.requireKind(), request.requireSummary(),
                    currentUser.userId(), currentUser.authority());
            case RELEASE -> assetCustodyService.release(assetId, currentUser.userId(), currentUser.authority());
            case RETIRE -> assetCustodyService.retire(assetId, currentUser.userId(), currentUser.authority());
        }
        return detailsResponse(assetId);
    }

    /**
     * Lists an asset's maintenance history, open or closed, newest first.
     *
     * @param id the asset
     * @return the asset's maintenance records
     */
    @GetMapping("/api/assets/{id}/maintenance")
    public List<MaintenanceRecordResponse> listMaintenance(@PathVariable String id) {
        return maintenanceService.listForAsset(AssetId.of(id), currentUser.scope()).stream()
                .map(MaintenanceRecordResponse::from)
                .toList();
    }

    /**
     * Lists maintenance records across every asset the caller's scope includes — this endpoint's
     * fleet-wide counterpart, one call rather than one {@link #listMaintenance} per grounded asset
     * (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3, D5; docs/plans/active/WAREHOUSE-UX-CONTEXT.md
     * W7 handoff).
     *
     * @param state {@code open}, {@code closed}, or {@code all}, case-insensitive; defaults to
     *              {@code open}
     * @param limit bounds the {@code closed}/{@code all} portion's fleet-wide scan; open records are
     *              never limit-truncated (see {@link MaintenanceService#fleetWide})
     * @return the maintenance records the caller may see, each carrying its asset's name and category
     */
    @GetMapping("/api/maintenance")
    public List<FleetMaintenanceRecordResponse> fleetMaintenance(
            @RequestParam(defaultValue = "open") String state,
            @RequestParam(defaultValue = "" + DEFAULT_FLEET_MAINTENANCE_LIMIT) int limit) {
        return maintenanceService.fleetWide(toListState(state), limit, currentUser.scope()).stream()
                .map(FleetMaintenanceRecordResponse::from)
                .toList();
    }

    private static MaintenanceListState toListState(String state) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("state must not be blank");
        }
        try {
            return MaintenanceListState.valueOf(state.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown maintenance list state: " + state
                    + " (valid values: open, closed, all)", e);
        }
    }

    /**
     * Opens a maintenance record against an asset, without changing its inventory state.
     *
     * @param id      the asset
     * @param request the record to open
     * @return the opened record
     */
    @PostMapping("/api/assets/{id}/maintenance")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceRecordResponse openMaintenance(@PathVariable String id,
                                                      @RequestBody CreateMaintenanceRecordRequest request) {
        return MaintenanceRecordResponse.from(maintenanceService.open(AssetId.of(id), request.toKind(),
                request.summary(), currentUser.userId(), currentUser.authority()));
    }

    /**
     * Closes an open maintenance record.
     *
     * @param id        the asset (unused beyond shaping the URL — the record id alone identifies it;
     *                  kept in the path for symmetry with the other {@code /assets/{id}/maintenance}
     *                  endpoints)
     * @param recordId  the record to close
     * @return the closed record
     */
    @PostMapping("/api/assets/{id}/maintenance/{recordId}/close")
    public MaintenanceRecordResponse closeMaintenance(@PathVariable String id, @PathVariable String recordId) {
        return MaintenanceRecordResponse.from(
                maintenanceService.close(MaintenanceId.of(recordId), currentUser.userId(), currentUser.authority()));
    }

    /**
     * {@code firmware}, {@code totalFlightSeconds} and {@code custody.custodianName} are always
     * absent on this controller's responses — deliberately, not an oversight: this class's own
     * javadoc already explains why {@link AssetCustodyService}/{@link MaintenanceService} live here
     * rather than on {@link AssetController} (the five-parameter ceiling), and this constructor has
     * no room left for {@code com.drones.vision.api.support.AssetRowFacts}, which owns all three
     * joins. A caller wanting them after a custody/inventory mutation should follow up with {@code
     * GET /api/assets/{id}}, which does join them (see {@link AssetController#details}) — the
     * custodian's id is on the response either way, so nothing here is unknowable, only unjoined.
     */
    private AssetDetailsResponse detailsResponse(AssetId id) {
        return AssetDetailsResponse.from(assetService.details(currentUser.scope(), id),
                assetImageRepositoryPort.existsByAssetId(id), null, null, null);
    }
}
