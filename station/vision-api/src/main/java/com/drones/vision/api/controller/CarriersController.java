package com.drones.vision.api.controller;

import com.drones.vision.api.dto.CarrierSummaryResponse;
import com.drones.vision.api.security.OpenByDesign;
import com.drones.vision.flight.domain.port.CarrierDirectoryPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * Driving REST adapter for {@code GET /api/carriers} (LINK-PAIRING-PLAN.md §3.4/§7 ruling 5):
 * <b>station-wide</b>, not per-asset — a carrier (a radio, a UDP listener, a serial port) belongs to
 * no single asset, so there is no {@code AssetId} to filter by, the same "deployment-wide reference
 * data" reasoning {@link GeofenceController#list} already uses for a different resource.
 *
 * <p>Constructor-injected with {@link CarrierDirectoryPort} directly — no intervening application
 * service, matching this module's existing precedent for a trivial pass-through read ({@code
 * AuditController}/{@code EventController}/{@code SystemEventsController} all call their own port
 * directly with no service layer between).
 */
@RestController
public class CarriersController {

    private final CarrierDirectoryPort carrierDirectoryPort;

    public CarriersController(CarrierDirectoryPort carrierDirectoryPort) {
        this.carrierDirectoryPort = Objects.requireNonNull(carrierDirectoryPort, "carrierDirectoryPort must not be null");
    }

    /**
     * @return every carrier currently registered on the station
     */
    @OpenByDesign(reason = "Deployment-wide reference data with no asset field to filter by -- a "
            + "carrier is not owned by any one asset (LINK-PAIRING-PLAN.md §7 ruling 5).")
    @GetMapping("/api/carriers")
    public List<CarrierSummaryResponse> list() {
        return carrierDirectoryPort.carriers().stream().map(CarrierSummaryResponse::from).toList();
    }
}
