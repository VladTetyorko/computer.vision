import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type {
  ActiveStream,
  AssetDeletionResponse,
  AssetDetails,
  AssetEdit,
  AssetSummary,
  DetectionResult,
  Device,
  DeviceEdit,
  RegisterDeviceRequest,
  ScanRequest,
  ScanResult,
  SettableLifecycleState,
  SimulationResponse,
  StartSimulationRequest,
  StartStreamRequest,
  StartStreamResult,
  TelemetrySample,
  UsageTimeline,
} from './models';

/**
 * The only place the frontend knows REST URLs.
 *
 * Every method returns a promise: components hold signals, not subscriptions, so
 * awaiting and assigning is both simpler and zoneless-friendly.
 */
@Injectable({ providedIn: 'root' })
export class VisionApi {
  private readonly http = inject(HttpClient);

  // --- Devices -------------------------------------------------------------

  /**
   * `includeDeleted` defaults to `false` (today's behavior, unchanged) and is only added to the
   * query string when `true` — the warehouse page's "show archived" toggle (docs/CYCLES-PLAN.md
   * §8's pinned contract).
   */
  listDevices(includeDeleted = false): Promise<Device[]> {
    return firstValueFrom(
      this.http.get<Device[]>('/api/devices', includeDeleted ? { params: { includeDeleted: true } } : {}),
    );
  }

  registerDevice(request: RegisterDeviceRequest): Promise<Device> {
    return firstValueFrom(this.http.post<Device>('/api/devices', request));
  }

  // --- Devices — warehouse lifecycle (docs/CYCLES-PLAN.md §8's pinned contract) --------------
  // CW-a builds the server side of these in parallel with this UI; every call here degrades to
  // one `FleetStore.run()` toast (via its thin wrappers) if it 404s before CW-a ships.

  updateDevice(id: string, edit: DeviceEdit): Promise<Device> {
    return firstValueFrom(this.http.patch<Device>(`/api/devices/${encodeURIComponent(id)}`, edit));
  }

  /** `DEACTIVATED` on a `DELETED` device is a restore; `DELETED` is reached only via `deleteDevice`. */
  setDeviceState(id: string, state: SettableLifecycleState): Promise<Device> {
    return firstValueFrom(
      this.http.post<Device>(`/api/devices/${encodeURIComponent(id)}/state`, { state }),
    );
  }

  /** Soft delete (archive) — idempotent. */
  deleteDevice(id: string): Promise<Device> {
    return firstValueFrom(this.http.delete<Device>(`/api/devices/${encodeURIComponent(id)}`));
  }

  // --- Streams -------------------------------------------------------------

  listStreams(): Promise<ActiveStream[]> {
    return firstValueFrom(this.http.get<ActiveStream[]>('/api/streams'));
  }

  startStream(deviceId: string, request: StartStreamRequest = {}): Promise<StartStreamResult> {
    return firstValueFrom(
      this.http.post<StartStreamResult>(
        `/api/devices/${encodeURIComponent(deviceId)}/stream`,
        request,
      ),
    );
  }

  stopStream(streamId: string): Promise<void> {
    return firstValueFrom(
      this.http.delete<void>(`/api/streams/${encodeURIComponent(streamId)}`),
    );
  }

  /**
   * Recent detection results for a stream, newest first (docs/MVP1-PLAN.md §C8 bullet 3) — backs
   * the Live page's chip strip + CV status dot. An unknown/never-detected stream returns an empty
   * array rather than 404ing, mirroring `usageTelemetry`'s precedent.
   */
  streamDetections(streamId: string, limit = 50): Promise<DetectionResult[]> {
    return firstValueFrom(
      this.http.get<DetectionResult[]>(`/api/streams/${encodeURIComponent(streamId)}/detections`, {
        params: { limit },
      }),
    );
  }

  // --- Discovery -----------------------------------------------------------

  scan(request: ScanRequest = {}): Promise<ScanResult> {
    return firstValueFrom(this.http.post<ScanResult>('/api/discovery/scan', request));
  }

  // --- Assets ----------------------------------------------------------------
  // Backs the live telemetry OSD/map (docs/CYCLES-PLAN.md §2): a device's asset — and
  // that asset's open usage — is looked up on demand, not polled by a fleet-wide store.

  /** `includeDeleted` defaults to `false` (today's behavior, unchanged) — see `listDevices`. */
  listAssets(includeDeleted = false): Promise<AssetSummary[]> {
    return firstValueFrom(
      this.http.get<AssetSummary[]>(
        '/api/assets',
        includeDeleted ? { params: { includeDeleted: true } } : {},
      ),
    );
  }

  getAsset(assetId: string): Promise<AssetDetails> {
    return firstValueFrom(this.http.get<AssetDetails>(`/api/assets/${encodeURIComponent(assetId)}`));
  }

  usageTelemetry(usageId: string, limit = 200): Promise<TelemetrySample[]> {
    return firstValueFrom(
      this.http.get<TelemetrySample[]>(`/api/usages/${encodeURIComponent(usageId)}/telemetry`, {
        params: { limit },
      }),
    );
  }

  /**
   * The flight-replay window for one usage (docs/MVP2-PLAN.md §R, R-a/R-b — `UsageTimelineController`,
   * windowed + downsampled, unlike `usageTelemetry` above). `fromMs`/`toMs`/`maxPoints` are each
   * only added to the query string when given — omitting all three (`pages/replay/replay.ts`'s
   * only call site) lets the server default the window to the usage's own bounds and thin to its
   * own `DEFAULT_MAX_POINTS`; the replay cockpit instead always asks for `maxPoints: 2000` (the
   * server's clamp ceiling) once, up front, then scrubs entirely against the in-memory result —
   * no re-fetch per drag frame.
   */
  usageTimeline(
    usageId: string,
    options: { readonly fromMs?: number; readonly toMs?: number; readonly maxPoints?: number } = {},
  ): Promise<UsageTimeline> {
    const params: Record<string, number> = {};
    if (options.fromMs !== undefined) {
      params['fromMs'] = options.fromMs;
    }
    if (options.toMs !== undefined) {
      params['toMs'] = options.toMs;
    }
    if (options.maxPoints !== undefined) {
      params['maxPoints'] = options.maxPoints;
    }
    return firstValueFrom(
      this.http.get<UsageTimeline>(`/api/usages/${encodeURIComponent(usageId)}/timeline`, { params }),
    );
  }

  // --- Assets — warehouse lifecycle (docs/CYCLES-PLAN.md §8's pinned contract) ---------------

  updateAsset(id: string, edit: AssetEdit): Promise<AssetDetails> {
    return firstValueFrom(this.http.patch<AssetDetails>(`/api/assets/${encodeURIComponent(id)}`, edit));
  }

  /** `DEACTIVATED` on a `DELETED` asset is a restore; `DELETED` is reached only via `deleteAsset`. */
  setAssetState(id: string, state: SettableLifecycleState): Promise<AssetDetails> {
    return firstValueFrom(
      this.http.post<AssetDetails>(`/api/assets/${encodeURIComponent(id)}/state`, { state }),
    );
  }

  /** Soft delete (archive) — idempotent. Reports what it retained, not just that it succeeded. */
  deleteAsset(id: string): Promise<AssetDeletionResponse> {
    return firstValueFrom(
      this.http.delete<AssetDeletionResponse>(`/api/assets/${encodeURIComponent(id)}`),
    );
  }

  /** Assigns an unowned device to an asset; 409 when the device is already owned elsewhere. */
  assignDevice(assetId: string, deviceId: string): Promise<AssetDetails> {
    return firstValueFrom(
      this.http.post<AssetDetails>(`/api/assets/${encodeURIComponent(assetId)}/devices`, { deviceId }),
    );
  }

  /** 409 when this is the asset's last device — every asset needs at least one. */
  unassignDevice(assetId: string, deviceId: string): Promise<AssetDetails> {
    return firstValueFrom(
      this.http.delete<AssetDetails>(
        `/api/assets/${encodeURIComponent(assetId)}/devices/${encodeURIComponent(deviceId)}`,
      ),
    );
  }

  // --- Simulation ------------------------------------------------------------
  // Backs the "Simulate a source" wizard (docs/CYCLES-PLAN.md §4): a video file path in, a
  // registered, categorized, optionally already-streaming asset out.

  startSimulation(request: StartSimulationRequest): Promise<SimulationResponse> {
    return firstValueFrom(this.http.post<SimulationResponse>('/api/simulations', request));
  }

  /** Idempotent — stops the asset's stream and, for `transport=rtsp`, its transmitted feed too. */
  stopSimulation(assetId: string): Promise<void> {
    return firstValueFrom(
      this.http.delete<void>(`/api/simulations/${encodeURIComponent(assetId)}`),
    );
  }
}
