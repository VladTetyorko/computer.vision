import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** `GET /api/demo` — whether the backend's demo package is present, and what footage it would use. */
export interface DemoStatus {
  readonly enabled: boolean;
  readonly videosDirectory: string;
  readonly videos: readonly string[];
}

/** `POST /api/demo/seed` — what one press created, plus one line per step that failed. */
export interface DemoSeedResult {
  readonly assets: number;
  readonly users: number;
  readonly assignments: number;
  readonly zones: number;
  readonly marks: number;
  readonly streamsStarted: number;
  readonly assetNames: readonly string[];
  readonly usernames: readonly string[];
  readonly password: string;
  readonly videosUsed: readonly string[];
  readonly problems: readonly string[];
}

/**
 * The demo package's own HTTP client, deliberately separate from `core/api/vision-api.ts`.
 *
 * The demo is an additive, removable extra — its two routes vanish with `vision.demo.enabled=false`
 * — so it carries its own tiny client rather than growing the app's one real API surface with
 * endpoints no production deployment serves. Same promise-returning shape as `VisionApi` so the
 * calling component reads identically.
 */
@Injectable({ providedIn: 'root' })
export class DemoApi {
  private readonly http = inject(HttpClient);

  /** Rejects (404) when the backend was built or configured without the demo package. */
  status(): Promise<DemoStatus> {
    return firstValueFrom(this.http.get<DemoStatus>('/api/demo'));
  }

  /** Fills the platform with demo data. Slow by nature — it opens real video streams. */
  seed(): Promise<DemoSeedResult> {
    return firstValueFrom(this.http.post<DemoSeedResult>('/api/demo/seed', {}));
  }
}
