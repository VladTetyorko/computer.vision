package com.drones.vision.kernel;

/**
 * Where a device's data actually comes from: a real sensor, or a synthetic stand-in for one that
 * is not fitted yet ("the drone has no camera yet") or is being tested before a real one arrives
 * ("it has a camera and I need to test it") — docs/plans/active/SOURCE-ONBOARDING-CONTEXT.md §5.
 *
 * <p>Independent of a device's stream protocol: a {@code "sim"}-protocol device is always
 * {@link #SIMULATED}, but a wired {@code "rtsp"}/{@code "mavlink"} device can be either, depending
 * on whether the transmitting end is this app's own synthetic feed or a real one — see
 * docs/plans/active/ARCHITECTURE-AUDIT-2026-08-26.md finding D4 for why that fact used to be
 * unrepresentable ("the only test is string-matching {@code protocol == "sim"}"). This is a
 * property of one device, not of the asset it belongs to: an asset with a real autopilot and a
 * synthetic camera is honestly half-real, and nothing here forces one flag onto the whole vehicle.
 */
public enum DeviceOrigin {

    /** A genuine sensor: a real camera, autopilot, or other physical source. */
    LIVE,

    /** A synthetic stand-in — no physical hardware backs this device's data. */
    SIMULATED
}
