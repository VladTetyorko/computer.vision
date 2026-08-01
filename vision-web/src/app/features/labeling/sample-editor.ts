import { ChangeDetectionStrategy, Component, effect, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EmptyState } from '../../shared/ui/empty-state';
import { Notice } from '../../shared/ui/notice';
import { SectionHeader } from '../../shared/ui/section-header';
import { SampleBoxEditor } from '../../shared/player/sample-box-editor';
import { SampleEditorFacade } from './sample-editor-facade';
import { sampleStatusLabel } from './sample-editor-logic';
import type { Annotation } from '../../core/api/models';

/**
 * `/manage/training/:datasetId/samples/:sampleId` — the confirm/correct step
 * (docs/CV-TRAINING-PLAN.md Wave T5): the captured frame with its annotations drawn as editable
 * boxes (`vision-sample-box-editor`), a label vocabulary pulled from the dataset, and
 * Confirm/Discard. The suggested boxes are pre-filled from the model at capture time
 * (`Annotation.source === 'MODEL'`); anything the operator drags, resizes, adds, or removes becomes
 * `OPERATOR` the moment it's edited.
 *
 * Degrades honestly: an unknown sample/dataset (`SampleEditorFacade.notFound`) renders a
 * `vision-empty`, never a blocked page.
 */
@Component({
  selector: 'vision-sample-editor-page',
  imports: [RouterLink, EmptyState, Notice, SectionHeader, SampleBoxEditor],
  templateUrl: './sample-editor.html',
  styleUrl: './sample-editor.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [SampleEditorFacade],
})
export class SampleEditorPage {
  /** Bound from the route by `withComponentInputBinding()`. */
  readonly datasetId = input.required<string>();
  readonly sampleId = input.required<string>();

  protected readonly facade = inject(SampleEditorFacade);
  protected readonly sampleStatusLabel = sampleStatusLabel;

  constructor() {
    effect(() => {
      this.facade.load(this.datasetId(), this.sampleId());
    });
  }

  protected formatWhen(iso: string): string {
    return new Date(iso).toLocaleString();
  }

  protected onAnnotationsChange(next: readonly Annotation[]): void {
    this.facade.setAnnotations(next);
  }
}
