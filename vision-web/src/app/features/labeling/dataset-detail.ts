import { ChangeDetectionStrategy, Component, effect, inject, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Icon } from '../../shared/ui/icon';
import { EmptyState } from '../../shared/ui/empty-state';
import { SectionHeader } from '../../shared/ui/section-header';
import { DatasetDetailFacade } from './dataset-detail-facade';
import { formatBytes } from './dataset-detail-logic';
import type { SampleStatus, TrainingSample } from '../../core/api/models';

/**
 * `/manage/training/:datasetId` — one dataset's sample grid, capture flow, and export action
 * (docs/CV-TRAINING-PLAN.md Wave T5). The capture control here is the operator's "Add to dataset"
 * gesture: pick a currently-live stream, grab its current frame + detections as a `PENDING` sample.
 *
 * Degrades honestly: an unknown or out-of-scope dataset (`DatasetDetailFacade.notFound`) renders a
 * `vision-empty`, never a blocked page; a background sample-list failure toasts and leaves the grid
 * at whatever it last successfully showed (never a fabricated list).
 */
@Component({
  selector: 'vision-dataset-detail-page',
  imports: [FormsModule, RouterLink, Icon, EmptyState, SectionHeader],
  templateUrl: './dataset-detail.html',
  styleUrl: './dataset-detail.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [DatasetDetailFacade],
})
export class DatasetDetailPage {
  /** Bound from the route by `withComponentInputBinding()`. */
  readonly datasetId = input.required<string>();

  protected readonly facade = inject(DatasetDetailFacade);

  protected readonly statuses: readonly SampleStatus[] = ['PENDING', 'LABELED', 'DISCARDED'];
  protected readonly formatBytes = formatBytes;

  constructor() {
    effect(() => {
      this.facade.load(this.datasetId());
    });
  }

  protected formatWhen(iso: string): string {
    return new Date(iso).toLocaleString();
  }

  protected trackSample(_index: number, sample: TrainingSample): string {
    return sample.id;
  }
}
