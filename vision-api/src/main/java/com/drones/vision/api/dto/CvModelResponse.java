package com.drones.vision.api.dto;

import java.util.List;

/**
 * One entry in {@code GET /api/cv/models}'s roster (docs/CV-CONTROL-PLAN.md §4's frozen wire
 * contract) — a detection model the Fly cockpit's model picker can select.
 *
 * <p>No {@code @JsonInclude(NON_NULL)} here — every field is always present, including {@code
 * defaultLabelFilter}, which is a real (possibly empty) list rather than ever absent.
 *
 * @param id                 the exact checkpoint filename {@code cv-service}'s model registry routes
 *                           on — what {@code model} on start/PATCH forwards verbatim. Composite ids
 *                           ({@code "a.pt,b.pt"}) are permitted values of {@code model} elsewhere, but
 *                           this roster lists atomic models only
 * @param displayName        UI label
 * @param kind                free-form UI hint string ({@code general}/{@code specialized}/{@code
 *                            open-vocab}) — not an enum on the wire, to avoid a domain enum for a
 *                            display concern
 * @param openVocab           whether the UI should lead with the label-filter multi-select for this model
 * @param defaultLabelFilter  the class set the UI pre-selects when this model is picked; empty means
 *                            "all classes" — deliberately empty for the open-vocab entry (see {@code
 *                            CvModelCatalog}'s own javadoc for why a fixed preset would be dishonest there)
 */
public record CvModelResponse(String id, String displayName, String kind, boolean openVocab,
                               List<String> defaultLabelFilter) {

    public CvModelResponse {
        defaultLabelFilter = List.copyOf(defaultLabelFilter);
    }
}
