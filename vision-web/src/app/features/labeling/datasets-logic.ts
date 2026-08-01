/**
 * Pure logic behind `DatasetsPage` (`/manage/training`, docs/CV-TRAINING-PLAN.md Wave T5) — the
 * dataset list + "New dataset" form. Split out per this app's own convention of keeping component
 * logic thin and unit-testing the framework-free parts directly.
 */

/**
 * Turns the "New dataset" form's free-text classes field into the ordered, deduplicated,
 * non-blank list `CreateDatasetRequest#classes` expects. Splits on comma **or** newline (an
 * operator pasting a multi-line list from elsewhere shouldn't have to reformat it first), trims
 * each entry, drops blanks, and keeps only the first occurrence of a repeated name — order matters
 * (it becomes the YOLO class index, docs/CV-TRAINING-PLAN.md §5), so this never re-sorts.
 */
export function parseClassesInput(text: string): readonly string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const raw of text.split(/[,\n]/)) {
    const trimmed = raw.trim();
    if (trimmed.length === 0 || seen.has(trimmed)) {
      continue;
    }
    seen.add(trimmed);
    result.push(trimmed);
  }
  return result;
}

/** The "Create dataset" button's enabled predicate — a name and at least one class are both required (a classless dataset could never accept a single valid annotation, per `LabelingService#label`'s own vocabulary check). */
export function canSubmitDataset(name: string, classes: readonly string[], submitting: boolean): boolean {
  return !submitting && name.trim().length > 0 && classes.length > 0;
}
