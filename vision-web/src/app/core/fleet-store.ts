import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from './api/vision-api';
import { describeHttpError } from './api-error';
import { ToastService } from './toast.service';
import type {
  ActiveStream,
  Device,
  RegisterDeviceRequest,
  SimulationResponse,
  StartSimulationRequest,
  StartStreamRequest,
  StartStreamResult,
} from './api/models';

/** How often devices + streams are re-read while the tab is visible. */
const POLL_INTERVAL_MS = 5_000;

/**
 * Single source of truth for devices and active streams.
 *
 * Every page reads the same two signals, so the Wall, the Devices tab and a Live view
 * cannot disagree — and there is exactly one poller in the app rather than one per page
 * (docs/WEB-PLAN.md, W6). Polling pauses while the tab is hidden.
 */
@Injectable({ providedIn: 'root' })
export class FleetStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);

  private readonly devicesSignal = signal<readonly Device[]>([]);
  private readonly streamsSignal = signal<readonly ActiveStream[]>([]);
  private readonly loadingSignal = signal(false);
  private readonly reachableSignal = signal<boolean | null>(null);

  readonly devices = this.devicesSignal.asReadonly();
  readonly streams = this.streamsSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();

  /** `null` until the first request settles, so the header shows no verdict prematurely. */
  readonly reachable = this.reachableSignal.asReadonly();

  /** Device ids that currently have a running stream. */
  readonly liveDeviceIds = computed(
    () => new Set(this.streamsSignal().map((stream) => stream.deviceId)),
  );

  constructor() {
    void this.refresh();
    const handle = setInterval(() => {
      if (!document.hidden) {
        void this.refresh({ quiet: true });
      }
    }, POLL_INTERVAL_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(handle));
  }

  /** The stream currently running for a device, if any. */
  streamFor(deviceId: string): ActiveStream | undefined {
    return this.streamsSignal().find((stream) => stream.deviceId === deviceId);
  }

  device(deviceId: string): Device | undefined {
    return this.devicesSignal().find((device) => device.id === deviceId);
  }

  /**
   * Re-reads devices and streams together.
   *
   * `quiet` suppresses the toast for background polls — a backend that goes away should
   * light up the header dot, not spray a toast every five seconds.
   */
  async refresh(options: { quiet?: boolean } = {}): Promise<void> {
    this.loadingSignal.set(true);
    try {
      const [devices, streams] = await Promise.all([
        this.api.listDevices(),
        this.api.listStreams(),
      ]);
      this.devicesSignal.set(devices);
      this.streamsSignal.set(streams);
      this.reachableSignal.set(true);
    } catch (error) {
      this.reachableSignal.set(false);
      if (!options.quiet) {
        this.toasts.error(describeHttpError(error));
      }
    } finally {
      this.loadingSignal.set(false);
    }
  }

  async register(request: RegisterDeviceRequest): Promise<Device | null> {
    return this.run(async () => {
      const device = await this.api.registerDevice(request);
      await this.refresh({ quiet: true });
      this.toasts.ok(`Registered ${device.name}.`);
      return device;
    });
  }

  async start(deviceId: string, request: StartStreamRequest = {}): Promise<StartStreamResult | null> {
    return this.run(async () => {
      const result = await this.api.startStream(deviceId, request);
      await this.refresh({ quiet: true });
      if (!result.viewUrl) {
        this.toasts.info('Stream started, but no publisher is wired — there is nothing to watch yet.');
      }
      return result;
    });
  }

  async stop(streamId: string): Promise<boolean> {
    const result = await this.run(async () => {
      await this.api.stopStream(streamId);
      await this.refresh({ quiet: true });
      return true;
    });
    return result ?? false;
  }

  /**
   * Creates a simulated asset (docs/CYCLES-PLAN.md §4) and refreshes the device/stream lists so
   * the new device shows up immediately. Returns `null` on failure (already toasted by `run()`);
   * the caller decides the specific success toast/Watch action since that depends on whether
   * `autoStart` produced a `streamId` — this store only knows how to fail loudly once.
   */
  async simulate(request: StartSimulationRequest): Promise<SimulationResponse | null> {
    return this.run(async () => {
      const result = await this.api.startSimulation(request);
      await this.refresh({ quiet: true });
      return result;
    });
  }

  /** Idempotent — stops a simulated asset's stream (and, for `transport=rtsp`, its feed). */
  async stopSimulation(assetId: string): Promise<boolean> {
    const result = await this.run(async () => {
      await this.api.stopSimulation(assetId);
      await this.refresh({ quiet: true });
      return true;
    });
    return result ?? false;
  }

  /**
   * Runs an action, turning any failure into one explained toast.
   *
   * Centralised here rather than in an HTTP interceptor so that a failure is reported once,
   * by the layer that knows what the user was trying to do.
   */
  private async run<T>(action: () => Promise<T>): Promise<T | null> {
    try {
      return await action();
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return null;
    }
  }
}
