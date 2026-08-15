package com.drones.mavlink.session;

/**
 * The facts a {@code HEARTBEAT} message carries about a peer's firmware and current mode, decoded
 * but not interpreted — no firmware-name lookup, no "is this ArduCopter or PX4" labelling. That
 * translation is project policy and lives in the driving adapter (plan §3.4), not here.
 *
 * @param autopilot    raw {@code MAV_AUTOPILOT_*} enum value
 * @param mavType      raw {@code MAV_TYPE_*} enum value (the vehicle/component type — named
 *                     {@code mavType} rather than {@code type} to avoid colliding with the wire
 *                     field name once this record is read out of context)
 * @param baseMode     raw {@code MAV_MODE_FLAG_*} bitmask
 * @param customMode   autopilot-specific mode bitfield; meaning depends on {@code autopilot}
 * @param systemStatus raw {@code MAV_STATE_*} enum value
 */
public record HeartbeatInfo(int autopilot, int mavType, int baseMode, long customMode, int systemStatus) {
}
