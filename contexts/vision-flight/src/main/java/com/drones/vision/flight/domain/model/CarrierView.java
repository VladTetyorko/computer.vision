package com.drones.vision.flight.domain.model;

import java.util.Objects;

/**
 * One carrier currently registered somewhere on the station — the station-wide reference-data
 * counterpart to {@link LinkView} (LINK-PAIRING-PLAN.md §3.4/§7 ruling 5: {@code GET /api/carriers}
 * is station-wide, not per-asset, since a carrier belongs to no single asset). Unlike {@link
 * LinkView}, this carries no election state ({@code active}/{@code receiving}/{@code heartbeatAge}/
 * {@code quality}) and no {@code deviceId} — it describes the carrier itself (what it is, how it is
 * prioritized), not any one vehicle's current relationship to it.
 *
 * @param id         this carrier's stable identity (the same {@link LinkId} a {@link LinkView} for
 *                   the same physical link would carry)
 * @param carrier    which technology this carrier rides on
 * @param serialRole {@link SerialRole#NONE} for a {@link CarrierKind#UDP} carrier
 * @param label      a short, human-facing name
 * @param priority   election priority (higher wins) — the same value {@code LinkDescriptor.priority}
 *                   carries on the adapter side
 */
public record CarrierView(LinkId id, CarrierKind carrier, SerialRole serialRole, String label, int priority) {

    public CarrierView {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(carrier, "carrier must not be null");
        Objects.requireNonNull(serialRole, "serialRole must not be null");
        Objects.requireNonNull(label, "label must not be null");
    }
}
