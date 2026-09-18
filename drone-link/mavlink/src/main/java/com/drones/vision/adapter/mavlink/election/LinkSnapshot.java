package com.drones.vision.adapter.mavlink.election;

import com.drones.mavlink.session.LinkQuality;
import com.drones.mavlink.transport.CarrierKind;
import com.drones.mavlink.transport.LinkId;
import com.drones.mavlink.transport.SerialRole;

import java.time.Duration;
import java.util.Objects;

/**
 * One link's election-relevant state for one sysid at the instant a {@link LinkGroup} was
 * snapshotted — the adapter-internal shape {@code MavlinkVehicleLinkPort} translates into
 * {@code vision-flight}'s own {@code LinkView} (LINK-PAIRING-PLAN.md §3.4).
 *
 * @param id             this link's stable identity
 * @param carrier        which technology this link rides on
 * @param serialRole     {@link SerialRole#NONE} for a {@link CarrierKind#UDP} link
 * @param label          a short, human-facing name
 * @param active         {@code true} iff this is the group's current ACTIVE link
 * @param receiving      {@code true} iff this link has heard the group's sysid within {@link
 *                       LinkElectionSettings#softTimeout()}
 * @param heartbeatAge   time since this link last delivered a frame from the group's sysid
 * @param quality        last known {@code RADIO_STATUS} reading for this link, or {@code null} if
 *                       none has ever arrived
 */
public record LinkSnapshot(LinkId id, CarrierKind carrier, SerialRole serialRole, String label, boolean active,
                            boolean receiving, Duration heartbeatAge, LinkQuality.Quality quality) {

    public LinkSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(carrier, "carrier must not be null");
        Objects.requireNonNull(serialRole, "serialRole must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(heartbeatAge, "heartbeatAge must not be null");
        // quality is deliberately nullable -- see class javadoc.
    }
}
