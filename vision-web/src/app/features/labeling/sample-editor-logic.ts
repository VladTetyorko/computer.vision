import type { Annotation, SampleStatus } from '../../core/api/models';

/**
 * Pure logic behind `SampleEditorPage` (`/manage/training/:datasetId/samples/:sampleId`,
 * docs/plans/done/CV-TRAINING-PLAN.md Wave T5) — the confirm/correct step. Split out so the vocabulary and
 * box-sanity checks that gate the "Confirm" button are unit-testable without mounting
 * `vision-sample-box-editor`.
 */

export interface AnnotationValidation {
  readonly valid: boolean;
  /** Every distinct label present that isn't a member of the dataset's own `classes` — the
   *  server's own `LabelingService#label` rejects these with a 400, so this is the client-side
   *  mirror of that one rule (docs/plans/done/CV-TRAINING-PLAN.md §2). */
  readonly invalidLabels: readonly string[];
  /** `true` when any box has collapsed to zero width/height (e.g. an aborted drag) — never a valid ground-truth box. */
  readonly hasDegenerateBox: boolean;
}

/**
 * Whether `annotations` is a savable confirm/correct — every label must be a member of `classes`
 * (the dataset's own vocabulary; an empty `classes` list makes every non-empty annotation set
 * invalid, matching the server's own "the dataset has no vocabulary yet" reality) and every box must
 * have positive width and height.
 */
export function validateAnnotations(annotations: readonly Annotation[], classes: readonly string[]): AnnotationValidation {
  const invalidLabels = [...new Set(annotations.filter((a) => !classes.includes(a.label)).map((a) => a.label))];
  const hasDegenerateBox = annotations.some((a) => a.box.width <= 0 || a.box.height <= 0);
  return { valid: invalidLabels.length === 0 && !hasDegenerateBox, invalidLabels, hasDegenerateBox };
}

/** A human label for a `SampleStatus` — the sample editor's own header subtitle and the dataset grid's tabs share this rather than three inline ternaries. */
export function sampleStatusLabel(status: SampleStatus): string {
  switch (status) {
    case 'PENDING':
      return 'Pending review';
    case 'LABELED':
      return 'Labeled';
    case 'DISCARDED':
      return 'Discarded';
  }
}
