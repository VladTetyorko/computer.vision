import { describe, expect, it } from 'vitest';
import {
  DEBUG_ENDPOINTS,
  findDebugEndpoint,
  methodHasBody,
  prefillForEndpoint,
} from './debug-endpoints';

describe('DEBUG_ENDPOINTS catalog', () => {
  it('covers every controller route from vision-api/MODULE.md, in order', () => {
    const signatures = DEBUG_ENDPOINTS.map((endpoint) => `${endpoint.method} ${endpoint.path}`);
    expect(signatures).toEqual([
      'GET /api/devices',
      'POST /api/devices',
      'GET /api/streams',
      'POST /api/devices/{deviceId}/stream',
      'DELETE /api/streams/{streamId}',
      'GET /api/assets',
      'POST /api/assets',
      'GET /api/assets/{id}',
      'POST /api/assets/{id}/stream',
      'DELETE /api/assets/{id}/stream',
      'GET /api/usages/{usageId}/telemetry',
      'GET /api/categories',
      'POST /api/discovery/scan',
      'GET /actuator/health',
    ]);
  });

  it('has unique ids', () => {
    const ids = DEBUG_ENDPOINTS.map((endpoint) => endpoint.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('gives every POST endpoint a parseable example body', () => {
    for (const endpoint of DEBUG_ENDPOINTS.filter((candidate) => candidate.method === 'POST')) {
      expect(() => JSON.parse(endpoint.exampleBody ?? '')).not.toThrow();
    }
  });

  it('gives non-POST endpoints no example body', () => {
    for (const endpoint of DEBUG_ENDPOINTS.filter((candidate) => candidate.method !== 'POST')) {
      expect(endpoint.exampleBody).toBeUndefined();
    }
  });
});

describe('findDebugEndpoint', () => {
  it('finds a catalog entry by id', () => {
    expect(findDebugEndpoint('categories-list')).toMatchObject({
      method: 'GET',
      path: '/api/categories',
    });
  });

  it('returns undefined for an unknown id', () => {
    expect(findDebugEndpoint('nope')).toBeUndefined();
  });
});

describe('methodHasBody', () => {
  it('is true for methods that carry a body in this console', () => {
    expect(methodHasBody('POST')).toBe(true);
    expect(methodHasBody('PUT')).toBe(true);
    expect(methodHasBody('PATCH')).toBe(true);
  });

  it('is false for GET/DELETE', () => {
    expect(methodHasBody('GET')).toBe(false);
    expect(methodHasBody('DELETE')).toBe(false);
  });
});

describe('prefillForEndpoint', () => {
  it('prefills method, path template and example body for a POST endpoint', () => {
    const prefill = prefillForEndpoint('discovery-scan');
    expect(prefill?.method).toBe('POST');
    expect(prefill?.path).toBe('/api/discovery/scan');
    expect(JSON.parse(prefill?.body ?? '')).toEqual({ timeoutMs: 4000 });
  });

  it('prefills an empty body for a GET endpoint', () => {
    expect(prefillForEndpoint('devices-list')).toEqual({
      method: 'GET',
      path: '/api/devices',
      body: '',
    });
  });

  it('carries the {placeholder} through untouched, for the user to edit', () => {
    const prefill = prefillForEndpoint('stream-stop');
    expect(prefill?.path).toBe('/api/streams/{streamId}');
  });

  it('returns undefined for an unknown id', () => {
    expect(prefillForEndpoint('nope')).toBeUndefined();
  });
});
