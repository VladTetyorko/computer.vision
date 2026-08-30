package com.drones.vision.learning.application;

import com.drones.vision.learning.domain.port.CvModelRepositoryPort;
import com.drones.vision.learning.domain.port.TrainingRunRepositoryPort;

import java.util.Objects;

/**
 * The two ports {@link DefaultTrainingJobService} needs to make a training run durable
 * (docs/plans/active/CV-SETTINGS-PLAN.md §3.3, fixing H7 — "training metrics evaporate"), bundled
 * into one constructor parameter (java-clean-code SKILL.md §3 — "bundle collaborators rather than
 * sprawl"): {@link DefaultTrainingJobService} already takes {@code TrainingPort}/{@code
 * LabelingService}/{@code AuditTrailPort}, and adding both new ports as individual parameters
 * alongside those (plus the retention-cap tunable) would push the constructor past the
 * five-parameter ceiling. Both are genuinely independent, substitutable ports in their own right —
 * this bundle exists only because they land together, as one wave, exactly like {@link
 * TrainingStores}'s own precedent.
 *
 * @param trainingRuns persists the run's own progress (docs/plans/active/CV-SETTINGS-PLAN.md §3.3
 *                     {@code TrainingRunRecord})
 * @param models       persists the {@code CANDIDATE} {@code CvModelRecord} a {@code SUCCEEDED} run
 *                     produces (docs/plans/active/CV-SETTINGS-PLAN.md §3.2, §8 OQ6 — never {@code
 *                     LIVE})
 */
public record TrainingRunStores(TrainingRunRepositoryPort trainingRuns, CvModelRepositoryPort models) {

    public TrainingRunStores {
        Objects.requireNonNull(trainingRuns, "trainingRuns must not be null");
        Objects.requireNonNull(models, "models must not be null");
    }
}
