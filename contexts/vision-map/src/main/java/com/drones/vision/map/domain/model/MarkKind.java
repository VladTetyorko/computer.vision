package com.drones.vision.map.domain.model;

/**
 * The tactical category a {@link Mark} represents (docs/plans/done/MAP-REWORK-PLAN.md §2.2) — an icon/colour
 * category, not a breach semantic (unlike {@link ZoneKind}, which drives inside/outside
 * evaluation), and orthogonal to {@link Affiliation} — this is "what it is", affiliation is
 * "whose it is". Styling by kind (colour, icon) is a web-layer concern, not modeled here.
 *
 * <p>Migrated from the original {@code TARGET | HAZARD | POI | FRIENDLY} set
 * (docs/plans/done/TACTICAL-MARKS-PLAN.md §1): {@code FRIENDLY} is deleted — a friendly mark is now any kind
 * with {@link Affiliation#FRIENDLY} — and {@link #UNIT}/{@link #EQUIPMENT} are added. The old-kind
 * &rarr; (new kind, default affiliation) mapping applied by migration and devsupport seed data:
 * {@code TARGET}&rarr;({@link #TARGET}, {@link Affiliation#HOSTILE}), {@code HAZARD}&rarr;({@link
 * #HAZARD}, {@link Affiliation#UNKNOWN}), {@code POI}&rarr;({@link #POI}, {@link
 * Affiliation#NEUTRAL}), {@code FRIENDLY}&rarr;({@link #UNIT}, {@link Affiliation#FRIENDLY}).
 */
public enum MarkKind {

    /** A person or force element (a unit, a position). */
    UNIT,

    /** A vehicle, weapon system, or other piece of matériel. */
    EQUIPMENT,

    /** A danger to the aircraft or crew (obstacle, threat, no-fly condition). */
    HAZARD,

    /** A point of interest with no tactical charge either way (landmark, waypoint). */
    POI,

    /** Something to strike or watch — the primary tactical point of interest. */
    TARGET
}
