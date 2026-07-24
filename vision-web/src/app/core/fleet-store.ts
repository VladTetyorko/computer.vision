import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from './api/vision-api';
import { describeHttpError } from './api-error';
import { PollScheduler } from './poll-scheduler';
import { ToastService } from './toast.service';
import type {
  ActiveStream,
  AssetDeletionResponse,
  AssetDetails,
  AssetEdit,
  AssetSummary,
  Device,
  DeviceEdit,
  RegisterDeviceRequest,
  SettableLifecycleState,
  SimulationResponse,
  StartSimulationRequest,
  StartStreamRequest,
  StartStreamResult,
} from './api/models';

/** How often devices + streams are re-read while the tab is visible. */
const POLL_INTERVAL_MS = 5_000;

/**
 * Console prefix for this store's diagnostic logging — this codebase has no logging
 * service/convention (grep-verified), so plain `console.*` with a stable prefix, mirroring
 * `ui/player.ts`'s `[player]`/`pages/fly/fly.ts`'s `[fly]`. Deliberately placed here rather than in
 * each calling page: this is the one place every stream-start request/response actually passes
 * through, regardless of which page (Fly, Live, asset detail, Wall's Watch action) triggered it.
 */
const LOG_PREFIX = '[fleet]';

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
  private readonly scheduler = inject(PollScheduler);

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
    // Poll-while-visible now runs off the app's one shared timer (docs/CYCLES-PLAN.md §9, CU-b
    // item 3 — `PollScheduler`) rather than this store's own `setInterval`.
    // Returns the `refresh()` promise (not `void`-discarded) so `PollScheduler`'s in-flight guard
    // can skip a tick while the previous poll is still pending, rather than piling another request
    // on top of a slow/hung backend (docs/MVP2-PLAN.md §S, S-b).
    const unsubscribe = this.scheduler.schedule(POLL_INTERVAL_MS, () => this.refresh({ quiet: true }));
    inject(DestroyRef).onDestroy(unsubscribe);
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

  // --- Warehouse: device lifecycle (docs/CYCLES-PLAN.md §8's pinned contract) ----------------
  // Thin `run()`-wrapped wrappers, same seam as `register`/`start`/`stop` above and `simulate`
  // below — chosen over a separate page-scoped store because `devicesSignal` already lives here
  // and every mutation below changes what it should read afterward (a renamed/reactivated device
  // refreshes in place; an archived one simply drops out of the default, non-archived listing —
  // exactly what Wall/Live should see too). CW-a is not live while this lands: a 404 from any of
  // these still produces exactly one toast via `run()`, never a crash.

  async updateDevice(id: string, edit: DeviceEdit): Promise<Device | null> {
    return this.run(async () => {
      const device = await this.api.updateDevice(id, edit);
      await this.refresh({ quiet: true });
      this.toasts.ok(`Updated ${device.name}.`);
      return device;
    });
  }

  /** `DEACTIVATED` on a `DELETED` device is how the pinned contract spells "restore". */
  async setDeviceState(id: string, state: SettableLifecycleState): Promise<Device | null> {
    return this.run(async () => {
      const device = await this.api.setDeviceState(id, state);
      await this.refresh({ quiet: true });
      this.toasts.ok(`${device.name} is now ${state.toLowerCase()}.`);
      return device;
    });
  }

  /** Soft delete (archive) — the device drops out of the default device listing afterward. */
  async deleteDevice(id: string): Promise<Device | null> {
    return this.run(async () => {
      const device = await this.api.deleteDevice(id);
      await this.refresh({ quiet: true });
      this.toasts.ok(`Archived ${device.name}.`);
      return device;
    });
  }

  /**
   * Read-only — deliberately does **not** write `devicesSignal` (so Wall/Live and the default
   * device list stay exactly as archived-free as they are today); this only backs the warehouse
   * table's "show archived" toggle. Still funnelled through `run()` for the one-toast rule.
   */
  async listDevicesIncludingArchived(): Promise<Device[] | null> {
    return this.run(() => this.api.listDevices(true));
  }

  async start(deviceId: string, request: StartStreamRequest = {}): Promise<StartStreamResult | null> {
    console.info(`${LOG_PREFIX} POST /api/devices/${deviceId}/stream`, { request });
    return this.run(async () => {
      const result = await this.api.startStream(deviceId, request);
      console.info(`${LOG_PREFIX} stream start response`, {
        streamId: result.streamId,
        viewUrl: result.viewUrl,
        whepUrl: result.whepUrl,
      });
      await this.refresh({ quiet: true });
      if (!result.viewUrl) {
        console.warn(`${LOG_PREFIX} stream ${result.streamId} started with no viewUrl — nothing to watch yet`);
        this.toasts.info('Stream started, but no publisher is wired — there is nothing to watch yet.');
      }
      if (!result.whepUrl) {
        console.info(`${LOG_PREFIX} stream ${result.streamId} has no whepUrl — the player will use HLS only`);
      }
      return result;
    });
  }

  async stop(streamId: string): Promise<boolean> {
    console.info(`${LOG_PREFIX} POST /api/streams/${streamId}/stop`);
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

  // --- Warehouse: asset lifecycle (docs/CYCLES-PLAN.md §8's pinned contract) -----------------
  // Same seam as `simulate`/`stopSimulation` above: assets aren't tracked by a `FleetStore`
  // signal (the Devices page fetches them ad hoc, as it already did for the C4 wizard's
  // simulated-asset chips), but every mutation here can change what `devicesSignal` should read
  // (deleting an asset also archives its devices, per `AssetDeletion#devicesDeleted`), so each
  // still refreshes devices/streams on success.

  async updateAsset(id: string, edit: AssetEdit): Promise<AssetDetails | null> {
    return this.run(async () => {
      const asset = await this.api.updateAsset(id, edit);
      await this.refresh({ quiet: true });
      this.toasts.ok(`Updated ${asset.displayName}.`);
      return asset;
    });
  }

  /** `DEACTIVATED` on a `DELETED` asset is how the pinned contract spells "restore". */
  async setAssetState(id: string, state: SettableLifecycleState): Promise<AssetDetails | null> {
    return this.run(async () => {
      const asset = await this.api.setAssetState(id, state);
      await this.refresh({ quiet: true });
      this.toasts.ok(`${asset.displayName} is now ${state.toLowerCase()}.`);
      return asset;
    });
  }

  /**
   * Soft delete (archive) — the success toast names what survived (docs/CYCLES-PLAN.md §8: an
   * archive confirmation must say what's retained, not just that the asset is gone).
   */
  async deleteAsset(id: string): Promise<AssetDeletionResponse | null> {
    return this.run(async () => {
      const result = await this.api.deleteAsset(id);
      await this.refresh({ quiet: true });
      this.toasts.ok(
        `Archived ${result.displayName} — ${result.devicesDeleted} device(s) archived, ` +
          `${result.usagesRetained} usage(s) retained, ${result.streamsStopped} stream(s) stopped.`,
      );
      return result;
    });
  }

  /**
   * Read-only — mirrors `listDevicesIncludingArchived`: backs the warehouse asset section's
   * "show archived" toggle without touching any signal Wall/Live read from.
   */
  async listAssetsIncludingArchived(): Promise<AssetSummary[] | null> {
    return this.run(() => this.api.listAssets(true));
  }

  /** Assigns an unowned device to an asset. A device-already-owned 409 gets a specific toast. */
  async assignDevice(assetId: string, deviceId: string): Promise<AssetDetails | null> {
    try {
      const asset = await this.api.assignDevice(assetId, deviceId);
      await this.refresh({ quiet: true });
      this.toasts.ok(`Assigned the device to ${asset.displayName}.`);
      return asset;
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 409) {
        this.toasts.error('That device already belongs to another asset — unassign it there first.');
      } else {
        this.toasts.error(describeHttpError(error));
      }
      return null;
    }
  }

  /**
   * Unassigns a device from an asset. The ≥1-device invariant's 409 gets its own explanation —
   * `describeHttpError`'s generic conflict sentence doesn't know *why* (docs/CYCLES-PLAN.md §8).
   */
  async unassignDevice(assetId: string, deviceId: string): Promise<AssetDetails | null> {
    try {
      const asset = await this.api.unassignDevice(assetId, deviceId);
      await this.refresh({ quiet: true });
      this.toasts.ok(`Unassigned the device from ${asset.displayName}.`);
      return asset;
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 409) {
        this.toasts.error(
          "Can't unassign — every asset needs at least one device. Assign a replacement first.",
        );
      } else {
        this.toasts.error(describeHttpError(error));
      }
      return null;
    }
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
      console.warn(`${LOG_PREFIX} action failed`, { error });
      this.toasts.error(describeHttpError(error));
      return null;
    }
  }
}
