import { describe, expect, it } from 'vitest';
import type { LinkView, LiveEvent } from '../api/models';
import {
  failoverRowsForAsset,
  hasActiveBenchWarning,
  linkHealthLabel,
  linkKindLabel,
  linkQualitySummary,
  parseLinkFailover,
  sortedLinks,
} from './pairing-logic';

function link(partial: Partial<LinkView> = {}): LinkView {
  return {
    id: 'link-1',
    carrier: 'UDP',
    serialRole: 'NONE',
    label: 'Wi-Fi',
    active: false,
    receiving: false,
    heartbeatAgeSeconds: 0,
    ...partial,
  };
}

function liveEvent(partial: Partial<LiveEvent> = {}): LiveEvent {
  return {
    id: 'e-0',
    at: '2026-09-17T00:00:00Z',
    type: 'LINK_FAILOVER',
    message: 'failover',
    attributes: {},
    ...partial,
  };
}

describe('linkKindLabel', () => {
  it('labels UDP as Wi-Fi regardless of serialRole', () => {
    expect(linkKindLabel('UDP', 'NONE')).toBe('Wi-Fi');
  });

  it('labels a serial ground-radio link', () => {
    expect(linkKindLabel('SERIAL', 'GROUND_RADIO')).toBe('Ground radio');
  });

  it('labels a serial bench link', () => {
    expect(linkKindLabel('SERIAL', 'BENCH')).toBe('Bench cable');
  });

  it('labels a plain serial link with no role', () => {
    expect(linkKindLabel('SERIAL', 'NONE')).toBe('Serial');
  });
});

describe('linkHealthLabel', () => {
  it('renders the heartbeat age via humanAge', () => {
    expect(linkHealthLabel(link({ heartbeatAgeSeconds: 12 }))).toBe('12s ago');
  });

  it('renders a larger age with the same unit rules as humanAge', () => {
    expect(linkHealthLabel(link({ heartbeatAgeSeconds: 125 }))).toBe('2m 5s ago');
  });
});

describe('linkQualitySummary', () => {
  it('returns undefined when there is no quality at all', () => {
    expect(linkQualitySummary(undefined)).toBeUndefined();
  });

  it('returns undefined for an empty quality object', () => {
    expect(linkQualitySummary({})).toBeUndefined();
  });

  it('joins every present field', () => {
    expect(
      linkQualitySummary({ rssi: -62, remoteRssi: -70, noise: -90, rxErrors: 3, fixed: true }),
    ).toBe('RSSI -62dBm, remote -70dBm, noise -90dBm, 3 rx errors, fixed');
  });

  it('includes only the fields that are present', () => {
    expect(linkQualitySummary({ rssi: -50 })).toBe('RSSI -50dBm');
  });
});

describe('sortedLinks', () => {
  it('puts the active link first', () => {
    const links = [link({ id: 'a', label: 'A' }), link({ id: 'b', label: 'B', active: true })];
    expect(sortedLinks(links).map((l) => l.id)).toEqual(['b', 'a']);
  });

  it('puts a receiving-but-not-active link ahead of a dormant one', () => {
    const links = [link({ id: 'a', label: 'A' }), link({ id: 'b', label: 'B', receiving: true })];
    expect(sortedLinks(links).map((l) => l.id)).toEqual(['b', 'a']);
  });

  it('breaks ties alphabetically by label', () => {
    const links = [link({ id: 'b', label: 'Zeta' }), link({ id: 'a', label: 'Alpha' })];
    expect(sortedLinks(links).map((l) => l.id)).toEqual(['a', 'b']);
  });

  it('does not mutate the input array', () => {
    const links = [link({ id: 'b', label: 'Zeta' }), link({ id: 'a', label: 'Alpha' })];
    sortedLinks(links);
    expect(links.map((l) => l.id)).toEqual(['b', 'a']);
  });
});

describe('hasActiveBenchWarning', () => {
  it('is false when no link is active', () => {
    const links = [link({ carrier: 'SERIAL', serialRole: 'BENCH', active: false })];
    expect(hasActiveBenchWarning(links)).toBe(false);
  });

  it('is false when the active link is not a bench link', () => {
    const links = [link({ carrier: 'UDP', active: true })];
    expect(hasActiveBenchWarning(links)).toBe(false);
  });

  it('is true when the active link is a serial bench link', () => {
    const links = [link({ carrier: 'SERIAL', serialRole: 'BENCH', active: true })];
    expect(hasActiveBenchWarning(links)).toBe(true);
  });
});

describe('parseLinkFailover', () => {
  it('returns undefined for a non-LINK_FAILOVER event', () => {
    expect(parseLinkFailover(liveEvent({ type: 'DEVICE_ONLINE' }))).toBeUndefined();
  });

  it('returns undefined when assetId is missing', () => {
    expect(parseLinkFailover(liveEvent({ attributes: {} }))).toBeUndefined();
  });

  it('decodes a well-formed failover event', () => {
    const event = liveEvent({
      attributes: { assetId: 'asset-1', fromLinkId: 'link-wifi', toLinkId: 'link-radio', reason: 'timeout' },
    });
    expect(parseLinkFailover(event)).toEqual({
      assetId: 'asset-1',
      at: '2026-09-17T00:00:00Z',
      fromLinkId: 'link-wifi',
      toLinkId: 'link-radio',
      reason: 'timeout',
    });
  });

  it('degrades honestly when optional attributes are absent', () => {
    const event = liveEvent({ attributes: { assetId: 'asset-1' } });
    expect(parseLinkFailover(event)).toEqual({
      assetId: 'asset-1',
      at: '2026-09-17T00:00:00Z',
      fromLinkId: undefined,
      toLinkId: undefined,
      reason: undefined,
    });
  });
});

describe('failoverRowsForAsset', () => {
  it('filters to only the requested asset, preserving newest-first order', () => {
    const events = [
      liveEvent({ id: 'e-1', attributes: { assetId: 'asset-1' } }),
      liveEvent({ id: 'e-2', attributes: { assetId: 'asset-2' } }),
      liveEvent({ id: 'e-3', attributes: { assetId: 'asset-1' } }),
    ];
    const rows = failoverRowsForAsset(events, 'asset-1');
    expect(rows).toHaveLength(2);
    expect(rows[0].at).toBe(events[0].at);
  });

  it('ignores non-failover events mixed into the feed', () => {
    const events = [liveEvent({ type: 'DEVICE_OFFLINE', attributes: { assetId: 'asset-1' } })];
    expect(failoverRowsForAsset(events, 'asset-1')).toEqual([]);
  });

  it('returns an empty list when nothing matches', () => {
    expect(failoverRowsForAsset([], 'asset-1')).toEqual([]);
  });
});
