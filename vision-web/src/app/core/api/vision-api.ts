import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import type {
  ActiveStream,
  Device,
  RegisterDeviceRequest,
  ScanRequest,
  ScanResult,
  StartStreamRequest,
  StartStreamResult,
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

  listDevices(): Promise<Device[]> {
    return firstValueFrom(this.http.get<Device[]>('/api/devices'));
  }

  registerDevice(request: RegisterDeviceRequest): Promise<Device> {
    return firstValueFrom(this.http.post<Device>('/api/devices', request));
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

  // --- Discovery -----------------------------------------------------------

  scan(request: ScanRequest = {}): Promise<ScanResult> {
    return firstValueFrom(this.http.post<ScanResult>('/api/discovery/scan', request));
  }
}
