import { humanAge } from '../telemetry/telemetry-logic';
import type { LinkCarrierKind, LinkQualityView, LinkSerialRole, LinkView, LiveEvent } from '../api/models';

/**
 * Pure derivations behind the asset-detail Links panel (docs/plans/active/LINK-PAIRING-PLAN.md §3.4,
 * wave L4) — human labels for a link's kind and health, active-first ordering, the bench-link safety
 * warning, and decoding `LINK_FAILOVER` rows out of the existing generic `LiveEvent` feed. Split out
 * per this app's own `*-logic.ts` convention — Angular-free, unit-tested without HTTP, SSE, or a
 * component. `LinksStore`/the panel component own every Angular-side effect; this file owns none.
 *
 * The `LINK_FAILOVER` decoding below mirrors `core/geofence/geofence-logic.ts#parseGeofenceBreach`
 * exactly: a frozen `Event.attributes` map, read defensively so a malformed/older-shaped event
 * degrades to "not a failover row" rather than a crash — this app's usual "unknown, not fabricated"
 * rule. Attribute names (`assetId`/`fromLinkId`/`toLinkId`/`reason`) are the plan's own frozen shape
 * (LINK-PAIRING-PLAN.md §3.4 — read directly out of the plan doc this wave, not guessed), still
 * unverified against a real emitted event since L2/L3 haven't landed in this worktree — every field
 * but `assetId` still degrades to `undefined` (rendered as "—") when absent, same as before. The two
 * ids, not labels, are what the event actually carries — `LinkFailoverRow` stays id-shaped and the
 * panel resolves each id to a label off its own current links snapshot
 * (`AssetLinksPanel#failoverLinkLabel`), falling back to the raw id for a link that has since rotated
 * out of the snapshot (e.g. after a hardware replace).
 */

/** Kind label per `(carrier, serialRole)` — `UDP` is always "Wi-Fi" (the only carrier that rides
 *  a wireless AP in this platform today); a `SERIAL` link's label further depends on which role
 *  it was wired up for. */
export function linkKindLabel(carrier: LinkCarrierKind, serialRole: LinkSerialRole): string {
  if (carrier === 'UDP') {
    return 'Wi-Fi';
  }
  switch (serialRole) {
    case 'GROUND_RADIO':
      return 'Ground radio';
    case 'BENCH':
      return 'Bench cable';
    case 'NONE':
    default:
      return 'Serial';
  }
}

/** "12s ago" / "4m 2s ago" — shares `humanAge` (`core/telemetry/telemetry-logic.ts`) with every
 *  other staleness render in the app, so a link's heartbeat age reads the same everywhere. */
export function linkHealthLabel(link: LinkView): string {
  return `${humanAge(link.heartbeatAgeSeconds)} ago`;
}

/** A short, comma-joined radio-quality summary, or `undefined` when the link carries no quality
 *  telemetry at all (a fresh/never-radio-status'd link, or a non-serial carrier) — the panel renders
 *  that as "—", never a fabricated reading. */
export function linkQualitySummary(quality: LinkQualityView | undefined): string | undefined {
  if (!quality) {
    return undefined;
  }
  const parts: string[] = [];
  if (quality.rssi !== undefined) {
    parts.push(`RSSI ${quality.rssi}dBm`);
  }
  if (quality.remoteRssi !== undefined) {
    parts.push(`remote ${quality.remoteRssi}dBm`);
  }
  if (quality.noise !== undefined) {
    parts.push(`noise ${quality.noise}dBm`);
  }
  if (quality.rxErrors !== undefined) {
    parts.push(`${quality.rxErrors} rx errors`);
  }
  if (quality.fixed === true) {
    parts.push('fixed');
  }
  return parts.length > 0 ? parts.join(', ') : undefined;
}

/**
 * Active link first, then receiving-but-not-active, then everything else — each tier alphabetical
 * by label within itself, so a re-render (a heartbeat tick, a failover) never reshuffles two links
 * that are otherwise equal, only ones whose actual state changed.
 */
export function sortedLinks(links: readonly LinkView[]): readonly LinkView[] {
  return links.slice().sort((a, b) => {
    if (a.active !== b.active) {
      return a.active ? -1 : 1;
    }
    if (a.receiving !== b.receiving) {
      return a.receiving ? -1 : 1;
    }
    return a.label.localeCompare(b.label);
  });
}

/**
 * Whether the panel must show its hard "flying on a bench cable" warning (docs/plans/active/
 * LINK-PAIRING-PLAN.md §7 — a `SERIAL`+`BENCH` link is meant for ground testing only; if it somehow
 * became the *active* link in flight, that's a safety concern worth interrupting for, not a quiet
 * badge).
 */
export function hasActiveBenchWarning(links: readonly LinkView[]): boolean {
  return links.some((link) => link.active && link.carrier === 'SERIAL' && link.serialRole === 'BENCH');
}

/** One decoded `LINK_FAILOVER` row — id-shaped, matching the plan's own frozen attributes (see this file's own doc comment). */
export interface LinkFailoverRow {
  readonly assetId: string;
  readonly at: string;
  readonly fromLinkId?: string;
  readonly toLinkId?: string;
  readonly reason?: string;
}

/** Decodes one `LiveEvent` into a `LinkFailoverRow`, or `undefined` when it isn't a (well-formed)
 *  `LINK_FAILOVER` event. */
export function parseLinkFailover(event: LiveEvent): LinkFailoverRow | undefined {
  if (event.type !== 'LINK_FAILOVER') {
    return undefined;
  }
  const assetId = event.attributes['assetId'];
  if (!assetId) {
    return undefined;
  }
  return {
    assetId,
    at: event.at,
    fromLinkId: event.attributes['fromLinkId'],
    toLinkId: event.attributes['toLinkId'],
    reason: event.attributes['reason'],
  };
}

/**
 * Every `LINK_FAILOVER` row for one asset, out of `LiveStore.liveEvents()` (newest-first — that
 * store's own contract, preserved here rather than re-sorted, so the panel's history reads
 * newest-on-top like every other event list in the app).
 */
export function failoverRowsForAsset(
  events: readonly LiveEvent[],
  assetId: string,
): readonly LinkFailoverRow[] {
  const rows: LinkFailoverRow[] = [];
  for (const event of events) {
    const row = parseLinkFailover(event);
    if (row && row.assetId === assetId) {
      rows.push(row);
    }
  }
  return rows;
}
