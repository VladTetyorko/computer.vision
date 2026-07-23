import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { Player } from '../../ui/player';
import { FleetStore } from '../../core/fleet-store';
import { SettingsStore } from '../../core/settings-store';
import { TelemetryStore } from '../../core/telemetry-store';
import { DetectionsStore } from '../../core/detections-store';
import { TelemetryOsd } from './telemetry-osd';
import { LiveMap } from './live-map';
import { DetectionsStrip } from './detections-strip';

@Component({
  selector: 'vision-live',
  imports: [Player, RouterLink, TelemetryOsd, LiveMap, DetectionsStrip],
  templateUrl: './live.html',
  styleUrl: './live.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  // Own instance per route activation: the telemetry/detections polls start/stop with this page
  // (docs/CYCLES-PLAN.md §2, docs/MVP1-PLAN.md §C8) rather than as app-wide singletons like `FleetStore`.
  providers: [TelemetryStore, DetectionsStore],
})
export class LivePage {
  /** Bound from the route by `withComponentInputBinding()`. */
  readonly deviceId = input.required<string>();

  private readonly router = inject(Router);
  protected readonly fleet = inject(FleetStore);
  protected readonly settings = inject(SettingsStore);
  protected readonly telemetry = inject(TelemetryStore);
  protected readonly detections = inject(DetectionsStore);

  protected readonly busy = signal(false);

  protected readonly device = computed(() => this.fleet.device(this.deviceId()));
  protected readonly stream = computed(() => this.fleet.streamFor(this.deviceId()));
  protected readonly live = computed(() => this.stream() !== undefined);

  /**
   * Capability-driven panels: a fixed camera and a drone are not the same viewing
   * experience, and `Device.capabilities` already says which is which
   * (docs/UX-DESIGN.md §5.2).
   */
  protected readonly hasTelemetry = computed(() =>
    (this.device()?.capabilities ?? []).includes('TELEMETRY'),
  );
  protected readonly hasPtz = computed(() => (this.device()?.capabilities ?? []).includes('PTZ'));

  protected readonly optionPairs = computed(() =>
    Object.entries(this.device()?.options ?? {}).map(([key, value]) => ({ key, value })),
  );

  constructor() {
    // Only devices that declare TELEMETRY are worth looking up an asset/usage for at all —
    // `hasTelemetry()` flips true once `FleetStore` has loaded the device, so this also covers
    // the brief window before that first fetch resolves.
    effect(() => {
      const deviceId = this.deviceId();
      if (this.hasTelemetry()) {
        this.telemetry.track(deviceId);
      } else {
        this.telemetry.reset();
      }
    });

    // Detections only make sense while a stream is actually running — there is no streamId to
    // poll otherwise (docs/MVP1-PLAN.md §C8 bullet 4).
    effect(() => {
      const streamId = this.stream()?.streamId;
      if (streamId) {
        this.detections.track(streamId);
      } else {
        this.detections.reset();
      }
    });
  }

  protected async start(): Promise<void> {
    const device = this.device();
    if (!device) {
      return;
    }
    this.busy.set(true);
    try {
      await this.fleet.start(device.id, this.settings.effective());
    } finally {
      this.busy.set(false);
    }
  }

  protected async stop(): Promise<void> {
    const stream = this.stream();
    if (!stream) {
      return;
    }
    this.busy.set(true);
    try {
      await this.fleet.stop(stream.streamId);
    } finally {
      this.busy.set(false);
    }
  }

  protected back(): Promise<boolean> {
    return this.router.navigate(['/devices']);
  }

  protected onConfidence(value: string): void {
    this.settings.adjust({ confidenceThreshold: Number(value) });
  }

  protected onFps(value: string): void {
    this.settings.adjust({ inferenceFps: Number(value) });
  }
}
