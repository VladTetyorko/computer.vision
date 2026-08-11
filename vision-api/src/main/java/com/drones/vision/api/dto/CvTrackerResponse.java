package com.drones.vision.api.dto;

import java.util.List;

/**
 * One entry in {@code GET /api/cv/trackers}'s roster (docs/TRACKING-PLAN.md &sect;4.F's frozen wire
 * contract) — a tracker engine the Fly cockpit's Tracking section can select, filtered by the
 * currently-selected mode.
 *
 * <p>No {@code @JsonInclude(NON_NULL)} — every field is always present, {@code modes} a real
 * (never absent) list. Mirrors {@link CvModelResponse}'s posture exactly, which is the point: this
 * roster is the same kind of thing, and it is wired the same way (a static, config-backed {@code
 * vision-app} bean, docs/CV-CONTROL-PLAN.md &sect;D's frozen decision applied to trackers).
 *
 * @param id          the engine id cv-service routes on ({@code bytetrack}/{@code lk}/{@code ncc}) —
 *                    what {@code tracking.engineId} forwards verbatim
 * @param displayName UI label
 * @param modes       which tracking modes this engine can serve ({@code ASSOCIATE} and/or {@code
 *                    FOLLOW}) — the wire anticipated cv-service's two engine protocols
 *                    (docs/TRACKING-ORCHESTRATION.md &sect;2.2), so the picker never offers an
 *                    associator for a follow
 * @param needsAssets whether the engine needs model assets shipped alongside it; {@code false} for
 *                    all three built-ins, carried for the deferred ONNX engines (&sect;5.B)
 * @param costHint    free-form measured per-frame cost string for the picker, e.g. {@code "~0.4 ms/frame"}
 */
public record CvTrackerResponse(String id, String displayName, List<String> modes, boolean needsAssets,
                                 String costHint) {

    public CvTrackerResponse {
        modes = List.copyOf(modes);
    }
}
