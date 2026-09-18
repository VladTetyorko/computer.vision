import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { FleetStore } from '../../core/fleet/fleet-store';
import { DetectionsFacade } from '../../core/detections/detections-facade';
import { PollScheduler } from '../../core/poll-scheduler';
import { ToastService } from '../../core/toast.service';
import { ageSeconds } from '../../core/telemetry/telemetry-logic';
import {
  describeSource,
  formatDuration,
  formatLatency,
  sessionDurationSeconds,
  transportLabel,
} from '../../core/stream-info-logic';
import type { Transport } from './player';
import { humanAge } from '../../core/telemetry/telemetry-logic';

/** How often the live-ticking duration/detection-age readouts tick, independent of any poll cadence. */
const CLOCK_TICK_MS = 1_000;

/**
 * A stream's user-meaningful summary (docs/plans/done/MVP2-PLAN.md §U-info) — replaces the old plumbing-first
 * "Source" card (bare URI, device id, stream id, raw `startedAt`) with: a protocol chip + human
 * source description, a live-ticking session duration, CV status (on/off + last-detection age),
 * the player's own measured latency, and a copyable view URL. Every raw identifier is still
 * reachable, just demoted into a collapsed "Technical details" disclosure — nothing is deleted.
 *
 * DI-shares the host page's `FleetStore` (root-provided, always available) and `DetectionsStore`
 * (page-provided — every host of this component must list `DetectionsStore` in its own
 * `providers`, exactly like `TelemetryOsd`/`DetectionsStrip` already do; `features/live/live.ts` and
 * `features/asset-detail/asset-detail.ts` both do). `latencySeconds` is an input rather than a third
 * injected store because it isn't store-backed anywhere — it's `shared/player/player.ts`'s own measurement,
 * piped up through its `latencyChanged` output so this panel never re-measures independently.
 */
@Component({
  selector: 'vision-stream-info',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (device(); as d) {
      <div class="row source-row">
        <span class="chip accent">{{ source()?.protocolLabel }}</span>
        <span class="source-desc truncate" [title]="source()?.description">{{ source()?.description }}</span>
      </div>

      @if (stream(); as s) {
        <dl class="facts">
          <div class="fact">
            <dt>Streaming for</dt>
            <dd>{{ durationLabel() }}</dd>
          </div>
          <div class="fact">
            <dt>Transport</dt>
            <dd>{{ transportFact() }}</dd>
          </div>
          <div class="fact">
            <dt>Latency</dt>
            <dd>{{ latencyLabel() }}</dd>
          </div>
          <div class="fact">
            <dt>Detections</dt>
            <dd>
              <span class="chip" [class.ok]="detectionsOn()">
                <span class="dot" [class.ok]="detectionsOn()"></span>{{ detectionsLabel() }}
              </span>
              @if (lastDetectionAgeLabel(); as age) {
                <span class="muted"> · last seen {{ age }}</span>
              }
            </dd>
          </div>
        </dl>

        @if (s.viewUrl) {
          <button type="button" class="btn secondary small" (click)="copyViewUrl()">Copy view link</button>
        }
      } @else {
        <p class="muted hint">Not streaming.</p>
      }

      <details class="tech-details">
        <summary>Technical details</summary>
        <dl>
          <dt>Device id</dt>
          <dd class="mono">{{ d.id }}</dd>
          <dt>URI</dt>
          <dd class="mono truncate" [title]="d.uri">{{ d.uri }}</dd>
          @if (stream(); as s) {
            <dt>Stream id</dt>
            <dd class="mono">{{ s.streamId }}</dd>
            <dt>Started</dt>
            <dd class="mono">{{ s.startedAt }}</dd>
          }
        </dl>
      </details>
    }
  `,
  styles: `
    .source-row {
      margin-bottom: var(--space-8);
    }

    .source-desc {
      font-weight: 500;
      min-width: 0;
    }

    .facts {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--space-8) var(--space-16);
      margin: 0 0 var(--space-8);
    }

    .fact dt {
      font-size: 0.68rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      color: var(--text-muted);
    }

    .fact dd {
      margin: var(--space-2) 0 0;
      font-size: 0.85rem;
      /* docs/plans/done/UX-REWORK-PLAN.md §U-b item 3 — this grid mixes numeric readouts ("Streaming for",
         "Latency") with text facts ("Transport", the "Detections" chip); tabular-nums only (not
         the mono font), inert on the text ones but keeps the numeric ones from jittering. */
      font-variant-numeric: tabular-nums;
    }

    .tech-details {
      margin-top: var(--space-16);
      font-size: 0.8rem;
    }

    .tech-details summary {
      cursor: pointer;
      color: var(--text-muted);
    }

    .tech-details dl {
      margin: var(--space-8) 0 0;
    }

    .tech-details dt {
      color: var(--text-muted);
      font-size: 0.72rem;
      text-transform: uppercase;
      letter-spacing: 0.05em;
      margin-top: var(--space-8);
    }

    .tech-details dd {
      margin: var(--space-2) 0 0;
    }
  `,
})
export class StreamInfoPanel {
  readonly deviceId = input.required<string>();
  /** `shared/player/player.ts`'s own measured seconds-behind-live, piped up via its `latencyChanged` output. */
  readonly latencySeconds = input<number | null>(null);
  /** `shared/player/player.ts`'s own live transport, piped up via its `transportChanged` output (docs/plans/done/MVP2-PLAN.md §L / §U3). */
  readonly transport = input<Transport>('hls');

  private readonly fleet = inject(FleetStore);
  private readonly detections = inject(DetectionsFacade);
  private readonly toasts = inject(ToastService);

  private readonly nowSignal = signal(Date.now());

  protected readonly device = computed(() => this.fleet.device(this.deviceId()));
  protected readonly stream = computed(() => this.fleet.streamFor(this.deviceId()));
  protected readonly source = computed(() => {
    const d = this.device();
    return d ? describeSource(d) : undefined;
  });

  protected readonly durationLabel = computed(() => {
    const s = this.stream();
    return s ? formatDuration(sessionDurationSeconds(s.startedAt, this.nowSignal())) : undefined;
  });

  protected readonly latencyLabel = computed(() => formatLatency(this.latencySeconds()));
  protected readonly transportFact = computed(() => transportLabel(this.transport()));

  protected readonly detectionsOn = computed(() => this.detections.status() === 'on');

  /**
   * Status legibility pass (docs/plans/done/UX-QUICKWINS-PLAN.md QF-3): the dot alone used to be the only
   * carrier of on/off — a word now always renders beside it, in the same chip idiom
   * `shared/ui/events-rail.ts`'s OPEN/CLOSED chip uses, self-describing even read out of context.
   */
  protected readonly detectionsLabel = computed(() =>
    this.detectionsOn() ? 'Detections on' : 'Detections off',
  );

  protected readonly lastDetectionAgeLabel = computed(() => {
    const latest = this.detections.results()[0];
    const age = ageSeconds(latest?.capturedAt, this.nowSignal());
    return age === undefined ? undefined : `${humanAge(age)} ago`;
  });

  constructor() {
    const stop = inject(PollScheduler).schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    inject(DestroyRef).onDestroy(stop);
  }

  protected async copyViewUrl(): Promise<void> {
    const url = this.stream()?.viewUrl;
    if (!url) {
      return;
    }
    try {
      await navigator.clipboard.writeText(url);
      this.toasts.ok('View link copied.');
    } catch {
      this.toasts.error('Could not copy automatically — the link is in Technical details below.');
    }
  }
}
