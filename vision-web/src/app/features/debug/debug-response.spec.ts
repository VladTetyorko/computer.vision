import { describe, expect, it } from 'vitest';
import { describeHealthProbe, formatResponseBody, isSuccessStatus, type HealthProbeResponse } from './debug-response';

function response(overrides: Partial<HealthProbeResponse> = {}): HealthProbeResponse {
  return { status: 200, bodyText: '{"status":"UP"}', reached: true, ...overrides };
}

describe('formatResponseBody', () => {
  it('pretty-prints a JSON body', () => {
    const result = formatResponseBody('{"a":1,"b":[2,3]}');
    expect(result.isJson).toBe(true);
    expect(result.text).toBe(JSON.stringify({ a: 1, b: [2, 3] }, null, 2));
  });

  it('falls back to the raw text when the body is not JSON', () => {
    const result = formatResponseBody('not json at all');
    expect(result).toEqual({ text: 'not json at all', isJson: false });
  });

  it('reports an empty body distinctly rather than as a parse failure', () => {
    expect(formatResponseBody('')).toEqual({ text: '(empty body)', isJson: false });
    expect(formatResponseBody(null)).toEqual({ text: '(empty body)', isJson: false });
  });
});

describe('isSuccessStatus', () => {
  it('is true across the 2xx range', () => {
    expect(isSuccessStatus(200)).toBe(true);
    expect(isSuccessStatus(204)).toBe(true);
    expect(isSuccessStatus(299)).toBe(true);
  });

  it('is false outside the 2xx range, including 0 (unreachable)', () => {
    expect(isSuccessStatus(0)).toBe(false);
    expect(isSuccessStatus(199)).toBe(false);
    expect(isSuccessStatus(300)).toBe(false);
    expect(isSuccessStatus(404)).toBe(false);
    expect(isSuccessStatus(500)).toBe(false);
  });
});

describe('describeHealthProbe', () => {
  it('is null when no check has been attempted yet', () => {
    expect(describeHealthProbe(null)).toBeNull();
  });

  it('is healthy (green) for a real 2xx {"status":"UP"} document', () => {
    expect(describeHealthProbe(response({ bodyText: '{"status":"UP"}' }))).toEqual({
      kind: 'healthy',
      label: 'UP',
      tone: 'ok',
    });
  });

  it('is unhealthy (red) for a real 2xx document reporting anything other than UP', () => {
    expect(describeHealthProbe(response({ bodyText: '{"status":"DOWN"}' }))).toEqual({
      kind: 'unhealthy',
      label: 'DOWN',
      tone: 'danger',
    });
    expect(describeHealthProbe(response({ bodyText: '{"status":"OUT_OF_SERVICE"}' }))).toEqual({
      kind: 'unhealthy',
      label: 'OUT_OF_SERVICE',
      tone: 'danger',
    });
  });

  it('is not-exposed (neutral), never a red failure, for a 404 — this deployment has no actuator at all', () => {
    // Spring's own default error body for an unmapped path, e.g. {"timestamp":...,"status":404,"error":"Not Found"} —
    // the exact shape that used to be misread as a health verdict because both bodies use a field named "status".
    expect(
      describeHealthProbe(response({ status: 404, reached: true, bodyText: '{"timestamp":"…","status":404,"error":"Not Found"}' })),
    ).toEqual({ kind: 'not-exposed', label: 'health probe not exposed', tone: 'neutral' });
  });

  it('is not-exposed for a 404 with no body at all', () => {
    expect(describeHealthProbe(response({ status: 404, bodyText: null }))).toEqual({
      kind: 'not-exposed',
      label: 'health probe not exposed',
      tone: 'neutral',
    });
  });

  it('is not-exposed for a 404 with a non-JSON (whitelabel HTML) body', () => {
    expect(describeHealthProbe(response({ status: 404, bodyText: '<html>Whitelabel Error Page</html>' }))).toEqual({
      kind: 'not-exposed',
      label: 'health probe not exposed',
      tone: 'neutral',
    });
  });

  it('is not-exposed for a fully unreachable backend', () => {
    expect(describeHealthProbe(response({ status: 0, reached: false, bodyText: 'Network error' }))).toEqual({
      kind: 'not-exposed',
      label: 'health probe not exposed',
      tone: 'neutral',
    });
  });

  it('is not-exposed for a non-404 non-2xx response too — no verdict without a real health document', () => {
    expect(describeHealthProbe(response({ status: 500, bodyText: '{"status":"whatever"}' }))).toEqual({
      kind: 'not-exposed',
      label: 'health probe not exposed',
      tone: 'neutral',
    });
  });

  it('is not-exposed for a 2xx body that parses but carries no string status field', () => {
    expect(describeHealthProbe(response({ bodyText: '{"components":{}}' }))).toEqual({
      kind: 'not-exposed',
      label: 'health probe not exposed',
      tone: 'neutral',
    });
  });
});
