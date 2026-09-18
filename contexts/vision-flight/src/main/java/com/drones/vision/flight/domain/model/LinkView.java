package com.drones.vision.flight.domain.model;

import com.drones.vision.kernel.DeviceId;

import java.time.Duration;
import java.util.Objects;

/**
 * One link's election-relevant state for a {@link LinkGroupView} (LINK-PAIRING-PLAN.md §3.4 frozen
 * contract).
 *
 * @param id          this link's stable identity
 * @param carrier     which technology this link rides on
 * @param serialRole  {@link SerialRole#NONE} for a {@link CarrierKind#UDP} link
 * @param label       a short, human-facing name
 * @param active      {@code true} iff this is the group's current ACTIVE link
 * @param receiving   {@code true} iff this link has heard the vehicle recently (within the
 *                    configured soft timeout)
 * @param heartbeatAge time since this link last delivered a frame from the vehicle
 * @param quality     last known radio-quality reading for this link, or {@code null} if none has
 *                    ever arrived
 * @param deviceId    additive beyond the frozen contract (LINK-PAIRING-PLAN.md §4 row L3 task
 *                    brief): the paired telemetry {@link DeviceId} this link belongs to. The web
 *                    contract ({@code LinkView} in {@code station/vision-web}) assumed exactly one
 *                    paired telemetry device per asset; this field lets a multi-device asset's
 *                    links be told apart without renaming or removing anything the web already
 *                    reads.
 */
public record LinkView(LinkId id, CarrierKind carrier, SerialRole serialRole, String label, boolean active,
                        boolean receiving, Duration heartbeatAge, LinkQuality quality, DeviceId deviceId) {

    public LinkView {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(carrier, "carrier must not be null");
        Objects.requireNonNull(serialRole, "serialRole must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(heartbeatAge, "heartbeatAge must not be null");
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        // quality is deliberately nullable -- see class javadoc.
    }
}
