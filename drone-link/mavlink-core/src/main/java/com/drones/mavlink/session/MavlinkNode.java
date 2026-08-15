package com.drones.mavlink.session;

import com.drones.mavlink.CompId;
import com.drones.mavlink.SysId;

import java.util.Objects;

/** This platform's own MAVLink identity — who a {@link MavlinkSession} presents itself as. */
public record MavlinkNode(SysId system, CompId component) {

    public MavlinkNode {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(component, "component");
    }

    /** The conventional ground-control-station identity: sysid 255, compid 190 (QGC's own default). */
    public static MavlinkNode groundStation() {
        return new MavlinkNode(new SysId(255), new CompId(190));
    }
}
