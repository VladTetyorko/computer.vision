package com.drones.vision.api.support.afteraction;

import java.util.Locale;

/**
 * The six evidence categories an after-action package always reports, in the fixed order the
 * FROZEN WIRE CONTRACT (docs/plans/done/AFTER-ACTION-PLAN.md &sect;3.1) requires: {@code
 * telemetry, detections, marks, recording, passport, audit}. Declaration order below <b>is</b> the
 * wire order — {@link AfterActionAssembler#assemble} builds {@link AfterActionPackage#parts()} by
 * iterating this enum's {@link #values()}, so reordering the constants reorders the wire.
 */
public enum AfterActionPartKind {

    TELEMETRY,
    DETECTIONS,
    MARKS,
    RECORDING,
    PASSPORT,
    AUDIT;

    /**
     * The wire spelling for {@code parts[].part} — lowercase, unlike {@code parts[].state} which
     * stays the enum name verbatim (docs/plans/done/AFTER-ACTION-PLAN.md &sect;3.1's example:
     * {@code "part": "telemetry"} beside {@code "state": "TRUNCATED"}).
     *
     * @return this kind's lowercase wire name
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
