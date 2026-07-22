import { HttpClient, HttpErrorResponse, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface RawRequest {
  readonly method: string;
  readonly path: string;
  /**
   * Raw textarea contents. Empty/undefined sends no body; text that parses as JSON is sent
   * as a JSON object (matching what every real client of this API does); text that does not
   * parse is sent verbatim — a malformed body is a legitimate thing to want to try from a
   * debug console, not something to reject client-side.
   */
  readonly body?: string;
}

export interface RawResponse {
  readonly status: number;
  readonly statusText: string;
  readonly ms: number;
  readonly headers: Record<string, string>;
  readonly bodyText: string | null;
  /** False only when the request never reached the server at all (network failure, status 0). */
  readonly reached: boolean;
}

/** Response headers this console bothers to surface — see WEB-PLAN W5. */
const HEADERS_OF_INTEREST = ['content-type', 'content-length'] as const;

/**
 * Executes exactly the request the Debug tab's user typed, with no shaping and no toasts.
 *
 * Deliberately bypasses `VisionApi`/`FleetStore`: this page's whole purpose is issuing raw,
 * possibly-malformed requests and showing the raw failure in the response viewer, so the
 * typed client and its single-toast error handling (vision-web/MODULE.md Conventions) would
 * actively work against that. Nothing outside `pages/debug/**` should import this service.
 */
@Injectable({ providedIn: 'root' })
export class DebugApiService {
  private readonly http = inject(HttpClient);

  async send(request: RawRequest): Promise<RawResponse> {
    const startedAt = performance.now();
    try {
      const response = await firstValueFrom(
        this.http.request(request.method, request.path, {
          body: parseBody(request.body),
          observe: 'response',
          responseType: 'text',
        }),
      );
      return toRawResponse(response, performance.now() - startedAt);
    } catch (error) {
      if (error instanceof HttpErrorResponse) {
        return toRawResponse(error, performance.now() - startedAt);
      }
      return {
        status: 0,
        statusText: 'Network error',
        ms: performance.now() - startedAt,
        headers: {},
        bodyText: error instanceof Error ? error.message : String(error),
        reached: false,
      };
    }
  }
}

function parseBody(raw: string | undefined): unknown {
  if (raw === undefined || raw.trim().length === 0) {
    return undefined;
  }
  try {
    return JSON.parse(raw);
  } catch {
    return raw;
  }
}

function toRawResponse(response: HttpResponse<string> | HttpErrorResponse, ms: number): RawResponse {
  return {
    status: response.status,
    statusText: response.statusText || (response.status === 0 ? 'Unknown error' : ''),
    ms,
    headers: pickHeaders(response.headers),
    bodyText: extractBody(response),
    reached: response.status !== 0,
  };
}

function pickHeaders(headers: HttpHeaders): Record<string, string> {
  const picked: Record<string, string> = {};
  for (const name of HEADERS_OF_INTEREST) {
    const value = headers.get(name);
    if (value) {
      picked[name] = value;
    }
  }
  return picked;
}

function extractBody(response: HttpResponse<string> | HttpErrorResponse): string | null {
  if (!(response instanceof HttpErrorResponse)) {
    return response.body;
  }
  const body = response.error as unknown;
  if (body === null || body === undefined) {
    return null;
  }
  return typeof body === 'string' ? body : safeStringify(body);
}

function safeStringify(value: unknown): string {
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}
