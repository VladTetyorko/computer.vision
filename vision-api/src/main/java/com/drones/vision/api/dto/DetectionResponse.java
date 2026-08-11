package com.drones.vision.api.dto;

import com.drones.vision.domain.model.Detection;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response body element for one detected object, embedded in {@link DetectionResultResponse}.
 *
 * <p>{@code @JsonInclude(NON_NULL)} covers exactly one field, {@code track} (docs/TRACKING-PLAN.md
 * &sect;4.G) — every other field is always present. <b>An untracked detection's payload is therefore
 * byte-identical to the pre-tracking wire</b>, which is the whole reason the track facts are one
 * nested object instead of five flat fields (docs/TRACKING-ORCHESTRATION.md &sect;6 rule 1).
 *
 * @param label      the detected class label
 * @param confidence [0,1]
 * @param box        the detection's bounding box, normalized against frame dimensions
 * @param modelId    the model that produced this detection
 * @param modelVersion the model's version string
 * @param track      this detection's track identity for this frame, or absent when it is untracked
 *                   (tracking off for the stream, or a pre-tracking cv-service)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DetectionResponse(String label, double confidence, BoundingBoxResponse box, String modelId,
                                 String modelVersion, DetectionTrackResponse track) {

    /**
     * The canonical constructor before docs/TRACKING-PLAN.md wave T6 added {@code track}, kept as a
     * convenience constructor defaulting it to {@code null} ("untracked") — the same N-1-arg idiom
     * the domain's own {@code Detection}/{@code DetectionResult} use, so every pre-existing call
     * site compiles unchanged.
     *
     * @param label        the detected class label
     * @param confidence   [0,1]
     * @param box          the detection's bounding box
     * @param modelId      the model that produced this detection
     * @param modelVersion the model's version string
     */
    public DetectionResponse(String label, double confidence, BoundingBoxResponse box, String modelId,
                              String modelVersion) {
        this(label, confidence, box, modelId, modelVersion, null);
    }

    /**
     * Maps a domain {@link Detection} to its wire representation.
     *
     * @param detection the detection to map
     * @return the response body for {@code detection}
     */
    public static DetectionResponse from(Detection detection) {
        return new DetectionResponse(detection.label(), detection.confidence(),
                BoundingBoxResponse.from(detection.box()), detection.model().id(), detection.model().version(),
                detection.track() == null ? null : DetectionTrackResponse.from(detection.track()));
    }
}
