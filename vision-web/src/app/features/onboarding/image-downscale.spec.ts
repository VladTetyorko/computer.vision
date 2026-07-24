import { describe, expect, it } from 'vitest';
import { JPEG_QUALITY_STEPS, MAX_IMAGE_DIMENSION, computeTargetDimensions, isAcceptableImageType } from './image-downscale';

describe('computeTargetDimensions', () => {
  it('leaves an image already within budget unchanged', () => {
    expect(computeTargetDimensions(800, 600)).toEqual({ width: 800, height: 600 });
  });

  it('scales a too-wide landscape image down to the max dimension', () => {
    const result = computeTargetDimensions(4000, 2000, 1600);
    expect(result.width).toBe(1600);
    expect(result.height).toBe(800);
  });

  it('scales a too-tall portrait image down to the max dimension', () => {
    const result = computeTargetDimensions(2000, 4000, 1600);
    expect(result.width).toBe(800);
    expect(result.height).toBe(1600);
  });

  it('preserves aspect ratio for a non-square image at the boundary', () => {
    const result = computeTargetDimensions(3200, 1600, 1600);
    expect(result.width / result.height).toBeCloseTo(3200 / 1600, 5);
  });

  it('leaves a square image exactly at the limit unchanged', () => {
    expect(computeTargetDimensions(1600, 1600, 1600)).toEqual({ width: 1600, height: 1600 });
  });

  it('uses MAX_IMAGE_DIMENSION as the default ceiling', () => {
    const result = computeTargetDimensions(10_000, 10_000);
    expect(Math.max(result.width, result.height)).toBe(MAX_IMAGE_DIMENSION);
  });

  it('never produces a zero or negative dimension for degenerate input', () => {
    expect(computeTargetDimensions(0, 0)).toEqual({ width: 1, height: 1 });
    expect(computeTargetDimensions(-5, 100)).toEqual({ width: 1, height: 100 });
  });

  it('rounds to whole pixels', () => {
    const result = computeTargetDimensions(4001, 2000, 1600);
    expect(Number.isInteger(result.width)).toBe(true);
    expect(Number.isInteger(result.height)).toBe(true);
  });
});

describe('isAcceptableImageType', () => {
  it('accepts jpeg/png/webp', () => {
    expect(isAcceptableImageType('image/jpeg')).toBe(true);
    expect(isAcceptableImageType('image/png')).toBe(true);
    expect(isAcceptableImageType('image/webp')).toBe(true);
  });

  it('rejects everything else', () => {
    expect(isAcceptableImageType('image/gif')).toBe(false);
    expect(isAcceptableImageType('application/pdf')).toBe(false);
    expect(isAcceptableImageType('')).toBe(false);
  });
});

describe('JPEG_QUALITY_STEPS', () => {
  it('is a descending ladder of valid JPEG qualities', () => {
    expect(JPEG_QUALITY_STEPS.length).toBeGreaterThan(1);
    for (const quality of JPEG_QUALITY_STEPS) {
      expect(quality).toBeGreaterThan(0);
      expect(quality).toBeLessThanOrEqual(1);
    }
    for (let i = 1; i < JPEG_QUALITY_STEPS.length; i++) {
      expect(JPEG_QUALITY_STEPS[i]).toBeLessThan(JPEG_QUALITY_STEPS[i - 1]);
    }
  });
});
