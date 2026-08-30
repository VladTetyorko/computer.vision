package com.drones.vision.learning.application;

import com.drones.vision.learning.domain.model.CvModelRecord;
import com.drones.vision.learning.domain.model.ModelAvailability;
import com.drones.vision.learning.domain.model.ModelMetrics;
import com.drones.vision.learning.domain.model.ModelProvenance;
import com.drones.vision.learning.domain.model.ModelRuntime;
import com.drones.vision.learning.domain.model.ModelStatus;
import com.drones.vision.learning.domain.model.ModelTaskType;
import com.drones.vision.perception.domain.model.ModelRef;

import java.util.List;
import java.util.Objects;

/**
 * One row of {@link ModelRegistryService#models()} — a {@link CvModelRecord} joined with {@link
 * ModelAvailability}, the live fact of whether the connected CV worker actually reports this
 * (modelId, version) right now (docs/plans/active/CV-SETTINGS-PLAN.md §3.2, §5.2 {@code CvModel}
 * wire shape).
 *
 * <p>Two ways to build one:
 * <ul>
 *   <li>{@link #of(CvModelRecord, ModelAvailability)} — a real platform row, joined with whatever
 *       availability the merge computed for it. Every field mirrors the row's own.</li>
 *   <li>{@link #synthesize(ModelRef, boolean)} — the CV worker reports a model with no {@link
 *       CvModelRecord} row at all (nothing has trained or promoted it through this platform's loop
 *       yet — e.g. a bundled checkpoint on a fresh deployment, before {@code cv_models} has any seed
 *       data). Availability is always {@link ModelAvailability#PRESENT} (the worker just reported
 *       it); status is {@link ModelStatus#LIVE} when the worker's own {@code stage} marks it the
 *       active default, else {@link ModelStatus#DRAFT} — the worker wire only distinguishes
 *       {@code "active"} from {@code "available"} (see {@code GrpcModelRegistryPort}'s own javadoc),
 *       so {@code DRAFT} is the honest "we don't know more than that" fallback, not a guess at
 *       {@code CANDIDATE}/{@code RETIRED}. Every other field falls back to the same
 *       {@link #SYNTHESIZED_KIND}/{@link #SYNTHESIZED_TASK_TYPE}/{@link #SYNTHESIZED_RUNTIME}
 *       defaults {@code DefaultModelRegistryService#promote} uses when it has to create a row from
 *       scratch for the same reason — see that class for why the two must agree.</li>
 * </ul>
 *
 * @param modelId            the model id, as the CV worker names it
 * @param version            the model version
 * @param displayName        human-readable name for the picker
 * @param kind                free-form catalogue tag
 * @param openVocab          whether this model does open-vocabulary detection
 * @param defaultLabelFilter the label allow-list a stream starting with this model defaults to
 * @param taskType           what kind of inference this model performs
 * @param runtime            which runtime this model actually executes on
 * @param classes            the closed-set class roster this model can report
 * @param status             this row's lifecycle state
 * @param availability       whether the connected CV worker actually reports this model right now
 * @param metrics            this model's reported accuracy, or {@code null} if none reported
 * @param provenance         where this model came from; never {@code null}
 */
public record CvModelView(String modelId, String version, String displayName, String kind, boolean openVocab,
                           List<String> defaultLabelFilter, ModelTaskType taskType, ModelRuntime runtime,
                           List<String> classes, ModelStatus status, ModelAvailability availability,
                           ModelMetrics metrics, ModelProvenance provenance) {

    /** Shared with {@code DefaultModelRegistryService#promote}'s own row-synthesis — see class javadoc. */
    static final String SYNTHESIZED_KIND = "unregistered";
    static final ModelTaskType SYNTHESIZED_TASK_TYPE = ModelTaskType.DETECT;
    static final ModelRuntime SYNTHESIZED_RUNTIME = ModelRuntime.PYTORCH;

    public CvModelView {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("CvModelView modelId must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("CvModelView version must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("CvModelView displayName must not be blank");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("CvModelView kind must not be blank");
        }
        Objects.requireNonNull(defaultLabelFilter, "CvModelView defaultLabelFilter must not be null");
        Objects.requireNonNull(taskType, "CvModelView taskType must not be null");
        Objects.requireNonNull(runtime, "CvModelView runtime must not be null");
        Objects.requireNonNull(classes, "CvModelView classes must not be null");
        Objects.requireNonNull(status, "CvModelView status must not be null");
        Objects.requireNonNull(availability, "CvModelView availability must not be null");
        Objects.requireNonNull(provenance, "CvModelView provenance must not be null");
        defaultLabelFilter = List.copyOf(defaultLabelFilter);
        classes = List.copyOf(classes);
    }

    /**
     * Builds a view from a real platform row.
     *
     * @param record       the catalogue row
     * @param availability whether the connected worker reports this (modelId, version) right now
     * @return the joined view
     */
    public static CvModelView of(CvModelRecord record, ModelAvailability availability) {
        Objects.requireNonNull(record, "record must not be null");
        Objects.requireNonNull(availability, "availability must not be null");
        return new CvModelView(record.modelId(), record.version(), record.displayName(), record.kind(),
                record.openVocab(), record.defaultLabelFilter(), record.taskType(), record.runtime(),
                record.classes(), record.status(), availability, record.metrics(), record.provenance());
    }

    /**
     * Builds a row-less view for a worker-reported model with no platform row — see class javadoc.
     *
     * @param ref            the worker-reported model reference
     * @param workerReportsLive whether the worker's own {@code stage} marks this the active default
     * @return a synthesized, best-effort view
     */
    public static CvModelView synthesize(ModelRef ref, boolean workerReportsLive) {
        Objects.requireNonNull(ref, "ref must not be null");
        return new CvModelView(ref.id(), ref.version(), ref.id(), SYNTHESIZED_KIND, false, List.of(),
                SYNTHESIZED_TASK_TYPE, SYNTHESIZED_RUNTIME, List.of(),
                workerReportsLive ? ModelStatus.LIVE : ModelStatus.DRAFT, ModelAvailability.PRESENT, null,
                ModelProvenance.none());
    }
}
