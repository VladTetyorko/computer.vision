package com.drones.vision.api.dto;

import com.drones.vision.domain.model.LayerId;

/**
 * The one place the {@code /api/map} request bodies agree on what an <em>optional</em> {@code
 * layerId} means (docs/MAP-REWORK-PLAN.md §4.2): absent or blank is {@code null}, which the
 * application layer reads as "resolve my default layer" ({@code CreateMarkRequest}/{@code
 * GeolocateMarkRequest}/{@code CreateDrawingRequest}) or "the COP layer" ({@code
 * PromoteMarkRequest}) — while a present-but-malformed value is a {@code 400}, never a silent
 * fallback to the default.
 *
 * <p>Package-private: four request records in this package share it; nothing outside does.
 */
final class MapRequests {

    private MapRequests() {
    }

    /**
     * @param raw the raw {@code layerId} field
     * @return the parsed id, or {@code null} if the field was absent/blank
     * @throws IllegalArgumentException if present but not a canonical UUID (→ 400)
     */
    static LayerId optionalLayerId(String raw) {
        return raw == null || raw.isBlank() ? null : LayerId.of(raw.trim());
    }
}
