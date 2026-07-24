import { describe, expect, it } from 'vitest';
import { formatResponseBody, isSuccessStatus } from './debug-response';

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
