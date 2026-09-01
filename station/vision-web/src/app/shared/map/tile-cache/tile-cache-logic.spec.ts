import { describe, expect, it } from 'vitest';
import { planEviction, tileCacheKey, type TileCacheEntryMeta, tileHost } from './tile-cache-logic';

function entry(partial: Partial<TileCacheEntryMeta>): TileCacheEntryMeta {
  return { key: 'k', size: 1_000, lastAccessedAt: 0, ...partial };
}

describe('tileCacheKey', () => {
  it('joins layer/z/x/y', () => {
    expect(tileCacheKey('night', 4, 2, 9)).toBe('night/4/2/9');
    expect(tileCacheKey('night', 4, 2, 9, 'tile.openstreetmap.org')).toBe('night@tile.openstreetmap.org/4/2/9');
    expect(tileHost('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png')).toBe('basemaps.cartocdn.com');
    expect(tileHost('https://tile.openstreetmap.org/{z}/{x}/{y}.png')).toBe('tile.openstreetmap.org');
  });

  it('keys the same z/x/y differently per layer', () => {
    expect(tileCacheKey('night', 4, 2, 9)).not.toBe(tileCacheKey('satellite', 4, 2, 9));
  });
});

describe('planEviction', () => {
  it('evicts nothing when comfortably under the cap', () => {
    const existing = [entry({ key: 'a', size: 1_000, lastAccessedAt: 1 })];
    expect(planEviction(existing, 'b', 1_000, 1_000_000)).toEqual([]);
  });

  it('evicts the oldest-accessed entries first once over the cap', () => {
    const existing = [
      entry({ key: 'oldest', size: 400, lastAccessedAt: 1 }),
      entry({ key: 'middle', size: 400, lastAccessedAt: 2 }),
      entry({ key: 'newest', size: 400, lastAccessedAt: 3 }),
    ];
    // cap 1000: existing total 1200 + incoming 100 = 1300, needs to shed >= 300 -> evicts oldest (400)
    expect(planEviction(existing, 'incoming', 100, 1_000)).toEqual(['oldest']);
  });

  it('keeps evicting until back under the cap, oldest to newest', () => {
    const existing = [
      entry({ key: 'a', size: 500, lastAccessedAt: 1 }),
      entry({ key: 'b', size: 500, lastAccessedAt: 2 }),
      entry({ key: 'c', size: 500, lastAccessedAt: 3 }),
    ];
    expect(planEviction(existing, 'incoming', 500, 900)).toEqual(['a', 'b', 'c']);
  });

  it('excludes an existing entry sharing the incoming key from the accounting (overwrite, not double count)', () => {
    const existing = [
      entry({ key: 'same', size: 900, lastAccessedAt: 1 }),
      entry({ key: 'other', size: 50, lastAccessedAt: 2 }),
    ];
    // If 'same' were double-counted, the 900+50+incoming would look far over cap and evict 'other' too.
    expect(planEviction(existing, 'same', 900, 1_000)).toEqual([]);
  });

  it('evicts everything, without throwing, when a single incoming tile alone exceeds the cap', () => {
    const existing = [entry({ key: 'a', size: 10, lastAccessedAt: 1 })];
    expect(planEviction(existing, 'huge', 5_000, 1_000)).toEqual(['a']);
  });

  it('returns nothing to evict for an empty cache', () => {
    expect(planEviction([], 'first', 10, 1_000)).toEqual([]);
  });

  it('defaults to TILE_CACHE_MAX_BYTES when no cap is given', () => {
    expect(planEviction([], 'first', 10)).toEqual([]);
  });
});
