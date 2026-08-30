package com.drones.vision.learning.domain.model;

import com.drones.vision.kernel.UserId;

import java.time.Instant;
import java.util.List;

/**
 * One row of the CV model catalogue (docs/plans/active/CV-SETTINGS-PLAN.md §3.2, §5.2 {@code
 * CvModel} wire shape) — platform governance over a model id, joined at the application layer with
 * what the connected CV worker actually reports (see {@link ModelAvailability}). The primary key is
 * ({@link #modelId()}, {@link #version()}), matching {@code cv_models}'s composite key (§5.3);
 * multiple versions of the same {@code modelId} may coexist.
 *
 * <p>Exactly one row across the whole catalogue may be {@link ModelStatus#LIVE} at a time. This
 * record does not enforce that invariant itself — {@code CvModelRepositoryPort#save} does not check
 * it either — it is {@code ModelRegistryService}'s job: demoting the previous LIVE row to RETIRED
 * (via that row's own {@link #retire()}) before or atomically with promoting a new one (via {@link
 * #promote(UserId, Instant)}).
 *
 * @param modelId            the model id as the CV worker names it (e.g. {@code "yolo26n.pt"});
 *                           must not be blank
 * @param version            the model version (e.g. {@code "latest"}); must not be blank
 * @param displayName        human-readable name for the picker (e.g. {@code "People & vehicles"});
 *                           must not be blank
 * @param kind                free-form catalogue tag (e.g. {@code "general"}, {@code "open-vocab"});
 *                           must not be blank
 * @param openVocab          whether this model does open-vocabulary detection (prompted classes)
 *                           rather than a closed class set
 * @param defaultLabelFilter the label allow-list a stream starting with this model defaults to;
 *                           defensively copied, may be empty (meaning "every class")
 * @param taskType           what kind of inference this model performs; must not be {@code null}
 * @param runtime            which runtime this model actually executes on; must not be {@code null}
 * @param classes            the closed-set class roster this model can report, in the model's own
 *                           index order; defensively copied, empty by design for an open-vocab
 *                           model (docs/plans/done/CV-CONTROL-PLAN.md §4)
 * @param status             this row's lifecycle state; must not be {@code null}
 * @param metrics            this model's reported accuracy, or {@code null} if none has been
 *                           measured or reported yet
 * @param provenance         where this model came from; never {@code null} — {@link
 *                           ModelProvenance#none()} for a config-seeded model with nothing to
 *                           report
 * @param promotedBy         who last promoted this row to LIVE, or {@code null} if it never has
 *                           been; must be {@code null} exactly when {@code promotedAt} is
 * @param promotedAt         when this row was last promoted to LIVE, or {@code null} if it never
 *                           has been; must be {@code null} exactly when {@code promotedBy} is
 * @param createdAt          when this catalogue row was first registered; must not be {@code null}
 */
public record CvModelRecord(String modelId, String version, String displayName, String kind,
                             boolean openVocab, List<String> defaultLabelFilter, ModelTaskType taskType,
                             ModelRuntime runtime, List<String> classes, ModelStatus status,
                             ModelMetrics metrics, ModelProvenance provenance, UserId promotedBy,
                             Instant promotedAt, Instant createdAt) {

    public CvModelRecord {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("CvModelRecord modelId must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("CvModelRecord version must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("CvModelRecord displayName must not be blank");
        }
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("CvModelRecord kind must not be blank");
        }
        if (defaultLabelFilter == null) {
            throw new IllegalArgumentException("CvModelRecord defaultLabelFilter must not be null");
        }
        if (taskType == null) {
            throw new IllegalArgumentException("CvModelRecord taskType must not be null");
        }
        if (runtime == null) {
            throw new IllegalArgumentException("CvModelRecord runtime must not be null");
        }
        if (classes == null) {
            throw new IllegalArgumentException("CvModelRecord classes must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("CvModelRecord status must not be null");
        }
        if (provenance == null) {
            throw new IllegalArgumentException("CvModelRecord provenance must not be null");
        }
        if ((promotedBy == null) != (promotedAt == null)) {
            throw new IllegalArgumentException(
                    "CvModelRecord promotedBy and promotedAt must both be null or both be set");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("CvModelRecord createdAt must not be null");
        }
        defaultLabelFilter = List.copyOf(defaultLabelFilter);
        classes = List.copyOf(classes);
    }

    /**
     * Returns a copy of this row promoted to {@link ModelStatus#LIVE}, stamped with who promoted it
     * and when. Does not touch any other row — demoting the catalogue's previous LIVE row is the
     * caller's job, via that row's own {@link #retire()}.
     *
     * @param promotedBy the acting user; must not be {@code null}
     * @param promotedAt when the promotion happened; must not be {@code null}
     * @return a LIVE copy of this row
     */
    public CvModelRecord promote(UserId promotedBy, Instant promotedAt) {
        if (promotedBy == null) {
            throw new IllegalArgumentException("promote promotedBy must not be null");
        }
        if (promotedAt == null) {
            throw new IllegalArgumentException("promote promotedAt must not be null");
        }
        return new CvModelRecord(modelId, version, displayName, kind, openVocab, defaultLabelFilter,
                taskType, runtime, classes, ModelStatus.LIVE, metrics, provenance, promotedBy, promotedAt,
                createdAt);
    }

    /**
     * Returns a copy of this row demoted to {@link ModelStatus#RETIRED}. Its promotion history
     * ({@code promotedBy}/{@code promotedAt}) is kept, not cleared — retiring a model does not erase
     * the record of its last promotion, which is what lets a rollback find it again.
     *
     * @return a RETIRED copy of this row
     */
    public CvModelRecord retire() {
        return new CvModelRecord(modelId, version, displayName, kind, openVocab, defaultLabelFilter,
                taskType, runtime, classes, ModelStatus.RETIRED, metrics, provenance, promotedBy, promotedAt,
                createdAt);
    }
}
