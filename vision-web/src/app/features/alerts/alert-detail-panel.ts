import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { EmptyState } from '../../shared/ui/empty-state';
import { formatConfidence } from '../../core/events/events-logic';
import type { DetectionEvent } from '../../core/api/models';

/**
 * `vision-alert-detail-panel` — `/monitor/alerts`'s two-pane detail body (docs/design/08-alerts.md's
 * own refactor list: "Add `alert-detail-panel.ts` with the frame + box render"). Dumb, presentational,
 * feature-local (single consumer — `AlertsPage`, mirroring `pilots-card.ts`'s own precedent for a
 * non-routed child that still lives beside its one page rather than in `shared/ui/`).
 *
 * **The frame + detection box the design doc asks for does not exist to render.** `DetectionEvent`
 * (`core/api/models.ts`, mirroring `dto.DetectionEventResponse` exactly) carries only
 * `label`/`peakConfidence`/`firstSeen`/`lastSeen`/`state`/`position?` — no frame reference, no stored
 * bounding box, no image id. The cockpit's own live overlay (`shared/player/detection-overlay-logic.ts`)
 * burns boxes onto the *current* video frame by matching a fresh `GET /streams/{id}/detections`
 * batch to what's on-screen right now — a real-time sync technique, not a persisted per-event asset;
 * there is nothing analogous for a past, possibly-closed event. `VisionApi.snapshotUrl(streamId)`
 * exists but returns the stream's own *current* frame, which would be actively misleading for a
 * CLOSED event (or even an OPEN one several seconds old) — rendering "the event's frame" from a
 * live snapshot that has no verified relationship to what was actually detected would be exactly the
 * fabricated value docs/CLAUDE.md's "degrade honestly" rule rules out. So this panel renders the
 * event's full metadata plus an honest note instead of inventing an image endpoint, with
 * `Open cockpit`/`Jump to replay` as the way to actually go **see** the asset — live or replayed —
 * per the task brief's own explicit fallback instruction. Flagged in this task's own final report as
 * a genuine gap, not a silent scope cut.
 */
@Component({
  selector: 'vision-alert-detail-panel',
  imports: [EmptyState],
  templateUrl: './alert-detail-panel.html',
  styleUrl: './alert-detail-panel.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AlertDetailPanel {
  readonly event = input.required<DetectionEvent>();
  readonly sourceLabel = input.required<string>();
  readonly canOpenCockpit = input(false);
  readonly canJumpToReplay = input(false);

  readonly openCockpit = output<void>();
  readonly jumpToReplay = output<void>();

  protected readonly formatConfidence = formatConfidence;

  /** A plain locale-formatted clock/date string — mirrors `features/replay/replay-facade.ts#clockLabel`'s identical un-extracted `Date` delegation (nothing here is logic worth unit-testing, just a browser API call). */
  protected absoluteTime(iso: string): string {
    return new Date(iso).toLocaleString();
  }
}
