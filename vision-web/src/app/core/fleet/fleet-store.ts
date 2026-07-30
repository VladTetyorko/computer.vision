import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from '../api/vision-api';
import { describeHttpError } from '../api-error';
import { PollScheduler } from '../poll-scheduler';
import { ToastService } from '../toast.service';
import { LiveStore } from '../live/live-store';
import { isLiveAvailable } from '../live/live-fallback-logic';
import type {
  ActiveStream,
  AssetDeletionResponse,
  AssetDetails,
  AssetEdit,
  AssetSummary,
  CvModel,
  Device,
  DeviceEdit,
  DevicesSnapshot,
  PatchStreamConfigResponse,
  RegisterDeviceRequest,
  SettableLifecycleState,
  SimulationResponse,
  StartSimulationRequest,
  StartStreamRequest,
  StartStreamResult,
  UpdateStreamConfigRequest,
} from '../api/models';

/** How often devices + streams are re-read while the tab is visible. */
const POLL_INTERVAL_MS = 5_000;

/**
 * Console prefix for this store's diagnostic logging — this codebase has no logging
 * service/convention (grep-verified), so plain `console.*` with a stable prefix, mirroring
 * `shared/player/player.ts`'s `[player]`/`features/fly/fly.ts`'s `[fly]`. Deliberately placed here rather than in
 * each calling page: this is the one place every stream-start request/response actually passes
 * through, regardless of which page (Fly, Live, asset detail, Wall's Watch action) triggered it.
 */
const LOG_PREFIX = '[fleet]';

/**
 * Single source of truth for devices and active streams.
 *
 * Every page reads the same two signals, so the Wall, the Devices tab and a Live view
 * cannot disagree — and there is exactly one poller in the app rather than one per page
 * (docs/WEB-PLAN.md, W6).
 *
 * **Projection of `LiveStore`'s `devices` topic** (docs/REALTIME-PLAN.md §4's backend follow-up
 * batch — the same poll-vs-live pattern `TelemetryStore`/`DetectionsStore` established in R-c,
 * simplified since this topic is app-wide and always-on, not per-asset/opt-in): while `LiveStore`
 * is `'open'`, `devices`/`streams` are set atomically from each `DevicesSnapshotResponse` envelope
 * (both fields from the *same* snapshot — never independently stale relative to each other) and
 * the 5s poll below is paused entirely; while not `'open'`, the 5s `GET /api/devices`+`GET
 * /api/streams` poll is the (documented) fallback, exactly as it always was. No ref-counted
 * subscribe/unsubscribe is needed here (unlike `trackTelemetry`/`trackDetections`) — `devices` is
 * always-on, arriving on every connection regardless of the `topics` query parameter, so this store
 * only ever routes by `LiveStore.connectionState()`, never calls `track*`/`untrack*`.
 *
 * The constructor's own one-time `refresh()` call stays unconditional (mirrors `TelemetryStore`'s
 * "always one backfill fetch" precedent) — it paints something immediately without waiting on the
 * SSE handshake, and a live snapshot simply overwrites it moments later once `LiveStore` connects.
 */
@Injectable({ providedIn: 'root' })
export class FleetStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly scheduler = inject(PollScheduler);
  private readonly live = inject(LiveStore);

  private readonly devicesSignal = signal<readonly Device[]>([]);
  private readonly streamsSignal = signal<readonly ActiveStream[]>([]);
  private readonly loadingSignal = signal(false);
  private readonly reachableSignal = signal<boolean | null>(null);

  /**
   * The detection-model picker's roster (docs/CV-CONTROL-PLAN.md §4) — fetched once, here, rather
   * than per-page: every consumer (`features/fly/cv-control-panel.ts`, `features/live/live.ts`,
   * `features/settings/settings.ts`) reads the identical list, same "one source, no page-to-page
   * disagreement" reasoning as `devices`/`streams`. Unlike those two, this is **not** re-polled —
   * the roster is config-backed at the server (docs/CV-CONTROL-PLAN.md §D: "changes at deploy time,
   * not runtime") — a one-shot fetch at construction is enough for the app's lifetime.
   */
  private readonly modelsSignal = signal<readonly CvModel[]>([]);

  readonly devices = this.devicesSignal.asReadonly();
  readonly streams = this.streamsSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  /** Empty until the one-shot fetch resolves, and permanently empty on failure — see class doc.
   * `getCvModels()` never errors server-side, so an empty list here means either "still loading" or
   * "the request itself failed" (network down); every reader degrades to "no extra model facts"
   * rather than blocking on either (`cv-control-panel-logic.ts#findModel`). */
  readonly models = this.modelsSignal.asReadonly();

  /** `null` until the first request settles, so the header shows no verdict prematurely. */
  readonly reachable = this.reachableSignal.asReadonly();

  /** Device ids that currently have a running stream. */
  readonly liveDeviceIds = computed(
    () => new Set(this.streamsSignal().map((stream) => stream.deviceId)),
  );

  /** `null` until the poll is actually paused/resumed for the first time — see `applyTransport`. */
  private stopPollingFn: (() => void) | null = null;

  constructor() {
    void this.refresh(); // one-time initial fetch, regardless of live — see class doc.
    void this.loadModels(); // one-time, unrelated to the devices/streams poll — see `models`' own doc comment.
    // Poll-while-visible now runs off the app's one shared timer (docs/CYCLES-PLAN.md §9, CU-b
    // item 3 — `PollScheduler`) rather than this store's own `setInterval`. Started unconditionally
    // here so today's (pre-live, or live-unavailable) behavior is unchanged byte-for-byte; the
    // effect below only ever pauses it (once live opens) or resumes it (once live drops), never
    // double-registers it — see `applyTransport`'s own `stopPollingFn !== null` guard.
    this.stopPollingFn = this.schedulePoll();

    // Re-evaluates poll-vs-live whenever `LiveStore` (re)connects or drops (docs/REALTIME-PLAN.md
    // §4's backend follow-up batch) — mirrors `TelemetryStore`/`DetectionsStore`'s identical
    // reconnect-driven effect, simplified: no per-session `tracking` guard is needed since this
    // store has no track()/reset() session at all, just "poll, unless live is open".
    effect(() => {
      this.applyTransport(isLiveAvailable(this.live.connectionState()));
    });

    // Applies each `devices` snapshot atomically the moment one arrives — independent of whether
    // the poll is currently running, so a snapshot that lands before the connectionState effect
    // above has paused polling (or one that arrives while genuinely subscribed) is never dropped.
    effect(() => {
      const snapshot = this.live.devices();
      if (snapshot !== undefined) {
        this.applyDevicesSnapshot(snapshot);
      }
    });

    inject(DestroyRef).onDestroy(() => this.stopPolling());
  }

  /**
   * Switches whether the local 5s poll is running — **not** whether `LiveStore` itself has a
   * connection (that's `LiveStore`'s own concern; there is nothing to subscribe/unsubscribe here,
   * `devices` being always-on). `liveAvailable` pauses the poll; its absence resumes it, refetching
   * immediately first (mirrors `TelemetryStore.applyTransport`'s reconnect-driven branch) since
   * `devices`/`streams` may be stale from however long the live connection was up. A no-op when the
   * poll is already in the requested state (`stopPollingFn`'s own nullness tracks that).
   */
  private applyTransport(liveAvailable: boolean): void {
    if (liveAvailable) {
      this.stopPolling();
      return;
    }
    if (this.stopPollingFn !== null) {
      return; // already polling
    }
    void this.refresh({ quiet: true });
    this.stopPollingFn = this.schedulePoll();
  }

  /**
   * Applies one `devices` envelope: both lists set together (the envelope's own atomicity
   * guarantee — see class doc), and `reachable` set `true` — a live snapshot arriving is itself
   * proof the backend is reachable, exactly as a successful `refresh()` would conclude.
   */
  private applyDevicesSnapshot(snapshot: DevicesSnapshot): void {
    this.devicesSignal.set(snapshot.devices);
    this.streamsSignal.set(snapshot.streams);
    this.reachableSignal.set(true);
  }

  /**
   * Returns the `refresh()` promise (not `void`-discarded) so `PollScheduler`'s in-flight guard can
   * skip a tick while the previous poll is still pending, rather than piling another request on top
   * of a slow/hung backend (docs/MVP2-PLAN.md §S, S-b).
   */
  private schedulePoll(): () => void {
    return this.scheduler.schedule(POLL_INTERVAL_MS, () => this.refresh({ quiet: true }));
  }

  private stopPolling(): void {
    this.stopPollingFn?.();
    this.stopPollingFn = null;
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

  /**
   * One-shot fetch of the CV model roster — silent-degrade, no toast: this is background
   * enrichment for a picker, not a user-initiated action, mirroring `fly.ts#loadCapabilities`'s
   * identical "stays hidden/empty on failure" posture rather than `refresh()`'s own user-facing
   * error toast (that one guards the entire fleet view's own data).
   */
  private async loadModels(): Promise<void> {
    try {
      const response = await this.api.getCvModels();
      this.modelsSignal.set(response.models);
    } catch (error) {
      console.warn(`${LOG_PREFIX} could not load the CV model roster`, { error });
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

  /**
   * Live-patches a *running* stream's detection config (docs/CV-CONTROL-PLAN.md §3) —
   * `features/fly/cv-control-panel.ts`'s one write path, both for debounced hot-knob edits and a
   * deliberate model change. `run()`-wrapped like every other mutation here, so a failure (an
   * unknown/torn-down stream, an out-of-range value that somehow slipped past the panel's own
   * slider bounds) surfaces as exactly one toast rather than a silently-dropped PATCH — errors are
   * expected to be rare here (the panel debounces + clamps client-side), unlike `devices`/`streams`'
   * own 5s poll, so a toast per genuine failure is not the spam it would be on a poll cadence.
   * `null` on failure, mirroring `updateDevice`/`assignDevice`'s own contract; the caller checks the
   * response's own `modelReArmed` on success.
   */
  async patchStreamConfig(
    streamId: string,
    patch: UpdateStreamConfigRequest,
  ): Promise<PatchStreamConfigResponse | null> {
    return this.run(() => this.api.patchStreamConfig(streamId, patch));
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
