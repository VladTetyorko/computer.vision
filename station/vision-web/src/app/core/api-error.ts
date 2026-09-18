import { HttpErrorResponse } from '@angular/common/http';
import type { ApiErrorBody } from './api/models';

/**
 * Turns a failed request into a sentence a human can act on.
 *
 * The backend's `ApiExceptionHandler` already produces specific messages for 400/404/409,
 * so those are surfaced verbatim — the domain knows better than the UI why a URI was
 * rejected. This layer adds what the backend cannot know: that the server is unreachable,
 * that a stream is already running, that credentials were refused. "Request failed" is
 * never an acceptable output (docs/main/UX-DESIGN.md §7.1).
 */
export function describeHttpError(error: unknown): string {
  if (!(error instanceof HttpErrorResponse)) {
    return error instanceof Error ? error.message : 'Something went wrong.';
  }

  const fromServer = serverMessage(error);

  switch (error.status) {
    case 0:
      return 'Cannot reach the Vision backend. Is the app running on :8080?';
    case 400:
      return fromServer ?? 'The request was rejected as invalid.';
    case 401:
      // Retired the old RTSP-camera framing ("the device rejected these credentials") — every 401
      // in this app is now this app's own session dying, not a device's (docs/plans/active/AUTH-ROLES-PLAN.md
      // §3.6/§3.7, wave W1): `core/auth/session-interceptor.ts` already turns this into an in-place
      // reauth overlay or a redirect to `/login` for every endpoint that can genuinely session-401;
      // this generic copy only ever surfaces for a caller that renders its own toast/error text on
      // top of that (the interceptor re-throws, it doesn't swallow), or for the two excluded
      // endpoints with their own inline 401 handling (`AuthFacade.login`/`changePassword`).
      return fromServer ?? 'Your session is no longer valid — sign in again.';
    case 403:
      return fromServer ?? 'You do not have access to that.';
    case 404:
      return fromServer ?? 'That no longer exists — it may have been removed already.';
    case 409:
      return fromServer ?? 'That conflicts with the current state — is the stream already running?';
    case 415:
      return fromServer ?? 'That protocol has no adapter in this build.';
    case 503:
      return fromServer ?? 'A dependency is unavailable. Is mediamtx running?';
    default:
      if (error.status >= 500) {
        return fromServer
          ? `The backend failed handling this request: ${fromServer}`
          : 'The backend failed handling this request. Check the application log.';
      }
      return fromServer ?? `Unexpected response (HTTP ${error.status}).`;
  }
}

/** Extracts `ErrorResponse.message` when the backend sent one. */
function serverMessage(error: HttpErrorResponse): string | null {
  const body = error.error as Partial<ApiErrorBody> | string | null;
  if (typeof body === 'string' && body.trim().length > 0) {
    return body;
  }
  if (body && typeof body === 'object' && typeof body.message === 'string' && body.message.trim()) {
    return body.message;
  }
  return null;
}
