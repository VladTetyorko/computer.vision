package com.drones.mavlink.service;

import io.dronefleet.mavlink.minimal.Heartbeat;
import io.dronefleet.mavlink.minimal.MavAutopilot;
import io.dronefleet.mavlink.minimal.MavState;
import io.dronefleet.mavlink.minimal.MavType;
import io.dronefleet.mavlink.util.EnumValue;

import java.util.Objects;

/**
 * What our own periodic {@code HEARTBEAT} says about us. Raw wire values, not interpreted — same
 * discipline as {@code com.drones.mavlink.session.HeartbeatInfo} for a peer's heartbeat.
 *
 * @param baseMode a raw {@code MAV_MODE_FLAG_*} bitmask, not necessarily matching any single named
 *                 constant — see {@code io.dronefleet.mavlink.util.EnumValue#create(int)}
 */
public record HeartbeatContent(MavType type, MavAutopilot autopilot, int baseMode, long customMode, MavState systemStatus) {

    public HeartbeatContent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(autopilot, "autopilot");
        Objects.requireNonNull(systemStatus, "systemStatus");
    }

    /**
     * The conventional "I am a ground control station" announcement — {@link HeartbeatService}'s
     * default, matching {@code com.drones.mavlink.session.MavlinkNode#groundStation()}'s own identity
     * convention (this platform, by default, is a GCS/fleet gateway, not a flight controller).
     */
    public static HeartbeatContent groundStation() {
        return new HeartbeatContent(MavType.MAV_TYPE_GCS, MavAutopilot.MAV_AUTOPILOT_INVALID, 0, 0, MavState.MAV_STATE_ACTIVE);
    }

    Heartbeat toMessage() {
        return Heartbeat.builder()
                .type(type)
                .autopilot(autopilot)
                .baseMode(EnumValue.create(baseMode))
                .customMode(customMode)
                .systemStatus(systemStatus)
                .mavlinkVersion(3)
                .build();
    }
}
