package com.drones.vision.application;

import com.drones.vision.domain.model.DatasetExport;
import com.drones.vision.domain.model.DatasetId;
import com.drones.vision.domain.model.SampleImage;
import com.drones.vision.domain.model.SampleStatus;
import com.drones.vision.domain.model.TrainingSample;
import com.drones.vision.domain.model.TrainingSampleId;
import com.drones.vision.domain.model.UserId;

import java.util.List;

/**
 * Capture, correction/labeling and export of {@link TrainingSample}s (docs/CV-TRAINING-PLAN.md
 * §2) — the operator-in-the-loop half of the CV model-improvement loop. One interface, one
 * implementation ({@link DefaultLabelingService}); {@link com.drones.vision.domain.model.Dataset}
 * CRUD lives in {@link DatasetService}.
 *
 * <h2>Scope (docs/CV-TRAINING-PLAN.md Open Questions §4)</h2>
 * A dataset carrying {@code ownership} is normally group-scoped like every other owned datum, but
 * a {@link VisibilityScope.Kind#ASSIGNED_ASSETS} (pilot) scope carries <b>no</b> group information
 * at all — {@link VisibilityScope#includesGroup} is hard-{@code false} for it. Gating every method
 * here on dataset group-ownership the way {@link DatasetService#list}/{@link DatasetService#get}
 * do would make this entire feature structurally unreachable for a pilot, including the FPV
 * operator this capture feature exists for in the first place (the same trap {@code
 * DefaultMarkService}'s original group-scoped {@code list()} fell into and was later revised away
 * from — see that class's own "Design history worth keeping" note). This class therefore resolves
 * the open question by letting a pilot see <i>every</i> dataset (a dataset name/class list is not
 * sensitive the way an asset is); the source <b>asset</b>'s own {@link VisibilityScope#includes}
 * gate is what actually protects a pilot's reach — they may capture/label into any dataset, but
 * only from a stream whose asset they can see. {@link VisibilityScope.Kind#GROUPS} (manager)
 * scopes are still restricted to their own visible group subtree; {@link
 * VisibilityScope.Kind#UNBOUNDED} sees everything.
 */
public interface LabelingService {

    /**
     * Captures a training sample from a stream's <b>current</b> raw frame and detections: reads
     * {@link StreamService#latestRawFrame} (full-resolution, pre-overlay) and {@link
     * StreamService#latestDetections}, maps every detection to a {@link
     * com.drones.vision.domain.model.AnnotationSource#MODEL} annotation, and saves a new {@link
     * com.drones.vision.domain.model.SampleStatus#PENDING} sample (+ its JPEG image) into {@code
     * spec}'s dataset.
     *
     * @param spec   the stream to capture from and the dataset to add the sample to
     * @param actor  who is capturing, for the audit trail
     * @param scope  the acting user's visibility
     * @return the newly captured, persisted sample
     * @throws java.util.NoSuchElementException if the dataset is unknown, or the stream has no
     *                                            frame available yet (unknown/not running/no frame
     *                                            published)
     * @throws AccessDeniedException             if the dataset, or (when its owning asset can be
     *                                            resolved) the stream's source asset, is outside
     *                                            {@code scope}
     */
    TrainingSample capture(CaptureSpec spec, UserId actor, VisibilityScope scope);

    /**
     * Lists one dataset's samples.
     *
     * @param id           the dataset to list samples for
     * @param statusOrNull restrict to one status, or {@code null} for every status
     * @param limit        maximum number of samples to return; must be positive
     * @param actor        who is asking, for the audit trail on a denial
     * @param scope        the acting user's visibility
     * @return an immutable snapshot
     * @throws java.util.NoSuchElementException if the dataset is unknown
     * @throws AccessDeniedException             if the dataset is outside {@code scope}
     */
    List<TrainingSample> samples(DatasetId id, SampleStatus statusOrNull, int limit, UserId actor,
                                  VisibilityScope scope);

    /**
     * Reads one sample's stored image bytes.
     *
     * @param id    the sample whose image to read
     * @param actor who is asking, for the audit trail on a denial
     * @param scope the acting user's visibility
     * @return the sample's image
     * @throws java.util.NoSuchElementException if the sample is unknown, or has no stored image
     * @throws AccessDeniedException             if the sample's dataset is outside {@code scope}
     */
    SampleImage image(TrainingSampleId id, UserId actor, VisibilityScope scope);

    /**
     * Confirms or corrects one sample: replaces its annotations and moves it to {@code
     * spec.status()} ({@link SampleStatus#LABELED} or {@link SampleStatus#DISCARDED}), stamping
     * {@code labeledBy}/{@code labeledAt}. Every annotation's label must be a member of the
     * sample's dataset's {@link com.drones.vision.domain.model.Dataset#classes()} — the domain
     * deliberately does not enforce this (see {@link TrainingSample}'s own javadoc), so it lives
     * here. May be called more than once on the same sample (re-correcting an already {@code
     * LABELED} sample, or reviving a {@code DISCARDED} one) — the only status transition this
     * method itself forbids is codified on {@link LabelSpec} (only {@code LABELED}/{@code
     * DISCARDED} are ever valid targets), not on the sample's current status.
     *
     * @param id    the sample to label
     * @param spec  the corrected annotations and review outcome
     * @param actor who is labeling, for the audit trail and {@code labeledBy} stamp
     * @param scope the acting user's visibility
     * @return the updated, persisted sample
     * @throws java.util.NoSuchElementException if the sample is unknown
     * @throws AccessDeniedException             if the sample's dataset, or (when its asset is
     *                                            known) source asset, is outside {@code scope}
     * @throws IllegalArgumentException          if any annotation's label is not a member of the
     *                                            dataset's class vocabulary
     */
    TrainingSample label(TrainingSampleId id, LabelSpec spec, UserId actor, VisibilityScope scope);

    /**
     * Exports every {@link SampleStatus#LABELED} sample in a dataset as a YOLO-format dataset
     * (docs/CV-TRAINING-PLAN.md §5) via {@link com.drones.vision.domain.port.out.DatasetExportPort}.
     * {@link SampleStatus#PENDING}/{@link SampleStatus#DISCARDED} samples are skipped.
     *
     * @param id    the dataset to export
     * @param actor who is exporting, for the audit trail
     * @param scope the acting user's visibility
     * @return the completed export's manifest
     * @throws java.util.NoSuchElementException if the dataset is unknown
     * @throws AccessDeniedException             if the dataset is outside {@code scope}
     * @throws IllegalStateException             if a {@code LABELED} sample has no stored image (a
     *                                            data-integrity condition that should never occur
     *                                            in a well-formed system)
     */
    DatasetExport export(DatasetId id, UserId actor, VisibilityScope scope);
}
