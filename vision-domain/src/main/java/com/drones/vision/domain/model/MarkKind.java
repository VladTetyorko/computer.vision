package com.drones.vision.domain.model;

/**
 * The tactical category a {@link Mark} represents (docs/TACTICAL-MARKS-PLAN.md §1) — an icon/colour
 * category, not a breach semantic (unlike {@link ZoneKind}, which drives inside/outside evaluation).
 * Styling by kind (colour, icon) is a web-layer concern, not modeled here.
 */
public enum MarkKind {

    /** Something to strike or watch — the primary tactical point of interest. */
    TARGET,

    /** A danger to the aircraft or crew (obstacle, threat, no-fly condition). */
    HAZARD,

    /** A point of interest with no tactical charge either way (landmark, waypoint). */
    POI,

    /** A friendly asset or position, marked to avoid confusion with a target. */
    FRIENDLY
}
