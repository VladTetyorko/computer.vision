import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { describeHttpError } from './api-error';

function httpError(status: number, body?: unknown): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: body });
}

describe('describeHttpError', () => {
  it('names the cause when the backend is unreachable', () => {
    expect(describeHttpError(httpError(0))).toContain('Cannot reach the Vision backend');
  });

  it('prefers the domain message the backend sent', () => {
    const message = 'Unknown device type: DRONE';
    expect(describeHttpError(httpError(400, { error: 'BAD_REQUEST', message }))).toBe(message);
  });

  it('falls back to a specific sentence when the body carries no message', () => {
    expect(describeHttpError(httpError(409))).toContain('already running');
    expect(describeHttpError(httpError(404))).toContain('no longer exists');
  });

  it('explains server failures without leaking a bare status code', () => {
    expect(describeHttpError(httpError(500))).toContain('Check the application log');
  });

  it('accepts a plain-text error body', () => {
    expect(describeHttpError(httpError(400, 'uri must not be blank'))).toBe('uri must not be blank');
  });

  it('never returns an empty string for a non-HTTP failure', () => {
    expect(describeHttpError(new Error('boom'))).toBe('boom');
    expect(describeHttpError('weird')).toBe('Something went wrong.');
  });
});
