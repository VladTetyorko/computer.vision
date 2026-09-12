import { describe, expect, it } from 'vitest';
import { buildCurl } from './debug-curl';

describe('buildCurl', () => {
  it('builds a bare GET with no body and no Content-Type header', () => {
    expect(buildCurl({ method: 'GET', path: '/api/devices' }, 'https://station.local')).toBe(
      `curl -X GET 'https://station.local/api/devices'`,
    );
  });

  it('adds a Content-Type header and --data for a request that carried a body', () => {
    expect(
      buildCurl({ method: 'POST', path: '/api/devices', body: '{"name":"front-gate"}' }, 'https://station.local'),
    ).toBe(
      `curl -X POST 'https://station.local/api/devices' -H 'Content-Type: application/json' --data '{"name":"front-gate"}'`,
    );
  });

  it('omits --data when the body is empty or whitespace-only', () => {
    expect(buildCurl({ method: 'POST', path: '/api/assets', body: '' }, 'https://station.local')).toBe(
      `curl -X POST 'https://station.local/api/assets'`,
    );
    expect(buildCurl({ method: 'POST', path: '/api/assets', body: '   ' }, 'https://station.local')).toBe(
      `curl -X POST 'https://station.local/api/assets'`,
    );
  });

  it('escapes a single quote in the path using the close/escape/reopen idiom', () => {
    expect(buildCurl({ method: 'GET', path: `/api/assets/it's-mine` }, 'https://h')).toBe(
      `curl -X GET 'https://h/api/assets/it'\\''s-mine'`,
    );
  });

  it('escapes single quotes inside the request body', () => {
    const result = buildCurl(
      { method: 'POST', path: '/api/devices', body: `{"name":"O'Brien's cam"}` },
      'https://h',
    );
    expect(result).toBe(`curl -X POST 'https://h/api/devices' -H 'Content-Type: application/json' --data '{"name":"O'\\''Brien'\\''s cam"}'`);
  });

  it('escapes multiple consecutive quotes correctly', () => {
    expect(buildCurl({ method: 'GET', path: `/api/''` }, 'https://h')).toBe(
      `curl -X GET 'https://h/api/'\\'''\\'''`,
    );
  });
});
