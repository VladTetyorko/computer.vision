package com.drones.vision.api.dto;

import com.drones.vision.learning.application.CvModelView;
import com.drones.vision.learning.domain.model.ModelAvailability;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One entry in {@code GET /api/cv/models}'s roster (docs/plans/done/CV-CONTROL-PLAN.md §4's frozen wire
 * contract, widened by docs/plans/active/CV-SETTINGS-PLAN.md §5.2 for the {@code /vision/profiles} model
 * picker).
 *
 * <p>The first five fields are the original, always-present picker shape — see {@link
 * com.drones.vision.api.controller.CvModelsController}'s own javadoc for why the roster bean that
 * still uses only these five ({@code CvWiring#cvModelRoster}) is untouched. Every field after {@link
 * #defaultLabelFilter()} is new, {@code @JsonInclude(NON_NULL)}-omitted when absent — a config-roster
 * entry with no registry row behind it reports none of them, exactly matching the frozen TS {@code
 * CvModel} interface's own "absent means not reported by this roster entry, never a fabricated
 * default" contract.
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
 * @param version             the model version, or {@code null} when this roster entry carries none
 *                            (the pre-registry three-entry static roster never sets this)
 * @param taskType            {@code DETECT}/{@code SEGMENT}/{@code OPEN_VOCAB} (the domain {@code
 *                            ModelTaskType} name verbatim — {@code OPEN_VOCAB} has no counterpart in
 *                            the frozen TS union, a documented, harmless gap since the TS type is not
 *                            runtime-validated), or {@code null}
 * @param runtime             {@code PYTORCH}/{@code OPENVINO} (the domain {@code ModelRuntime} name
 *                            verbatim — a strict subset of the frozen TS union), or {@code null}
 * @param classes             the closed-set class roster this model reports, or {@code null}
 * @param status              {@code DRAFT}/{@code CANDIDATE}/{@code LIVE}/{@code RETIRED} (the domain
 *                            {@code ModelStatus} name verbatim — {@code DRAFT}/{@code RETIRED} have no
 *                            counterpart in the frozen TS union, a documented, harmless gap), or
 *                            {@code null}
 * @param availability        {@code PRESENT} or {@code MISSING} — the one deliberate translation off
 *                            the domain enum: {@code ModelAvailability.MISSING_ON_WORKER} maps to the
 *                            wire string {@code "MISSING"} (the frozen TS union has no {@code
 *                            MISSING_ON_WORKER} spelling), or {@code null}
 * @param metrics             this model's reported accuracy, or {@code null} if none reported
 * @param provenance          where this model came from, or {@code null} for a roster entry with no
 *                            registry row behind it at all (distinct from {@link
 *                            com.drones.vision.learning.domain.model.ModelProvenance#none()}, which is
 *                            a real, always-null-field object for a registry row that has one but it is
 *                            empty — see {@link CvModelProvenanceResponse})
 * @param source              {@code "config"} or {@code "registry"} — which of {@code
 *                            ModelRegistryService#models()}'s two paths produced this entry, or {@code
 *                            null} for the pre-registry static roster (reads the same as {@code
 *                            "config"} per the frozen TS contract)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CvModelResponse(String id, String displayName, String kind, boolean openVocab,
                               List<String> defaultLabelFilter, String version, String taskType, String runtime,
                               List<String> classes, String status, String availability,
                               CvModelMetricsResponse metrics, CvModelProvenanceResponse provenance,
                               String source) {

    /** Wire spelling {@link ModelAvailability#MISSING_ON_WORKER} translates to — see class javadoc. */
    private static final String MISSING_AVAILABILITY = "MISSING";

    /** Wire spelling {@code CatalogSource.CONFIG}/{@code REGISTRY} translate to — lowercase, per the
     * frozen TS {@code CvModel#source} union, unlike every other enum on this record. */
    private static final String CONFIG_SOURCE = "config";
    private static final String REGISTRY_SOURCE = "registry";

    public CvModelResponse {
        defaultLabelFilter = List.copyOf(defaultLabelFilter);
        classes = classes == null ? null : List.copyOf(classes);
    }

    /**
     * Maps a joined registry row plus which catalogue path produced it to its wire representation.
     *
     * @param view          the joined row
     * @param registrySource {@code true} when {@code CatalogSource.REGISTRY} produced this row
     *                        (translates to {@code "registry"}), {@code false} for {@code CONFIG}
     *                        (translates to {@code "config"})
     * @return the wire representation
     */
    public static CvModelResponse from(CvModelView view, boolean registrySource) {
        String availability = view.availability() == ModelAvailability.MISSING_ON_WORKER ? MISSING_AVAILABILITY
                : view.availability().name();
        return new CvModelResponse(view.modelId(), view.displayName(), view.kind(), view.openVocab(),
                view.defaultLabelFilter(), view.version(), view.taskType().name(), view.runtime().name(),
                view.classes(), view.status().name(), availability, CvModelMetricsResponse.from(view.metrics()),
                CvModelProvenanceResponse.from(view.provenance()), registrySource ? REGISTRY_SOURCE : CONFIG_SOURCE);
    }
}
