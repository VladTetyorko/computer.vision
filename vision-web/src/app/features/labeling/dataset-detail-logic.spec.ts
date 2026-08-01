import { describe, expect, it } from 'vitest';
import { canExportDataset, formatBytes, streamCaptureLabel } from './dataset-detail-logic';

describe('streamCaptureLabel', () => {
  const stream = { streamId: 'stream-1234567890', deviceId: 'dev-1' };

  it("uses the device's own name when resolvable", () => {
    expect(streamCaptureLabel(stream, [{ id: 'dev-1', name: 'North camera' }])).toBe('North camera');
  });

  it('falls back to a short stream id when the device is unknown', () => {
    expect(streamCaptureLabel(stream, [])).toBe('Stream stream-1');
  });
});

describe('canExportDataset', () => {
  it('is false with no labeled samples', () => {
    expect(canExportDataset({ sampleCounts: { PENDING: 3, LABELED: 0, DISCARDED: 1 } })).toBe(false);
  });

  it('is true once at least one sample is labeled', () => {
    expect(canExportDataset({ sampleCounts: { PENDING: 3, LABELED: 1, DISCARDED: 1 } })).toBe(true);
  });

  it('is false for a null dataset (not yet loaded)', () => {
    expect(canExportDataset(null)).toBe(false);
  });
});

describe('formatBytes', () => {
  it('renders sub-1KB sizes in bytes', () => {
    expect(formatBytes(512)).toBe('512 B');
  });

  it('renders KB/MB/GB with one decimal place', () => {
    expect(formatBytes(2048)).toBe('2.0 KB');
    expect(formatBytes(18_234_123)).toBe('17.4 MB');
    expect(formatBytes(3 * 1024 * 1024 * 1024)).toBe('3.0 GB');
  });
});
