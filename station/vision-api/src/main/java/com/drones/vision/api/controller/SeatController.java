package com.drones.vision.api.controller;

import com.drones.vision.api.dto.SeatsResponse;
import com.drones.vision.api.dto.TakeSeatRequest;
import com.drones.vision.api.security.SeatAccess;
import com.drones.vision.flight.domain.model.SeatKind;
import com.drones.vision.kernel.AssetId;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Driving REST adapter for the two-seat model (docs/plans/active/CREW-CONTROL-PLAN.md &sect;3.6,
 * frozen wire contract): {@code GET}/{@code POST}/{@code DELETE /api/assets/{id}/seats[/{kind}]}.
 *
 * <p>Thin by design — every actual rule lives in {@link SeatAccess}; this class only translates the
 * HTTP shape: parses {@code id}/{@code kind}, runs the visibility gate, and delegates.
 *
 * <h2>Status codes</h2>
 * A malformed {@code id} ({@link AssetId#of}) or an unrecognized {@code kind} (anything but exactly
 * {@code flight}/{@code camera}, lowercase) is a {@link IllegalArgumentException} &rarr; 400, checked
 * before the visibility gate so a bad request never depends on the caller's scope. {@link
 * SeatAccess#requireVisibleAsset} 404s an unknown or out-of-visibility asset. {@link
 * SeatAccess#takeSeat}/{@link SeatAccess#releaseSeat} throw {@link
 * com.drones.vision.platform.AccessDeniedException} (403) or {@link IllegalStateException} (409) per
 * &sect;3.6 — both already mapped centrally by {@code ApiExceptionHandler}, no handler change needed
 * here.
 */
@RestController
public class SeatController {

    private final SeatAccess seatAccess;

    public SeatController(SeatAccess seatAccess) {
        this.seatAccess = Objects.requireNonNull(seatAccess, "seatAccess must not be null");
    }

    /**
     * @param id the asset to read, as a canonical UUID string
     * @return both seats' current state plus the caller's own authority
     */
    @GetMapping("/api/assets/{id}/seats")
    public SeatsResponse seats(@PathVariable String id) {
        AssetId assetId = AssetId.of(id);
        seatAccess.requireVisibleAsset(assetId);
        return seatAccess.seats(assetId);
    }

    /**
     * Take-or-renew — the client's heartbeat.
     *
     * @param id      the asset, as a canonical UUID string
     * @param kind    {@code flight} or {@code camera}, exactly
     * @param request the optional {@code force} flag; the whole body may be absent
     * @return the resulting seat state
     */
    @PostMapping("/api/assets/{id}/seats/{kind}")
    public SeatsResponse take(@PathVariable String id, @PathVariable String kind,
                              @RequestBody(required = false) TakeSeatRequest request) {
        AssetId assetId = AssetId.of(id);
        SeatKind seatKind = parseKind(kind);
        seatAccess.requireVisibleAsset(assetId);
        TakeSeatRequest body = request != null ? request : TakeSeatRequest.EMPTY;
        return seatAccess.takeSeat(assetId, seatKind, body.forceOrDefault());
    }

    /**
     * Releases the named seat — idempotent.
     *
     * @param id   the asset, as a canonical UUID string
     * @param kind {@code flight} or {@code camera}, exactly
     */
    @DeleteMapping("/api/assets/{id}/seats/{kind}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable String id, @PathVariable String kind) {
        AssetId assetId = AssetId.of(id);
        SeatKind seatKind = parseKind(kind);
        seatAccess.requireVisibleAsset(assetId);
        seatAccess.releaseSeat(assetId, seatKind);
    }

    private static SeatKind parseKind(String kind) {
        return switch (kind) {
            case "flight" -> SeatKind.FLIGHT;
            case "camera" -> SeatKind.CAMERA;
            default -> throw new IllegalArgumentException("Unknown seat kind: " + kind);
        };
    }
}
