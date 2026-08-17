import type { GeoPosition, LiveEvent, ZoneKind } from '../api/models';

/**
 * Pure, Angular-free logic behind `core/geofence/geofence-store.ts` and the zones UI
 * (`features/command/**`, `shared/map/fleet-map/**`, `shared/map/live-map/**`;
 * docs/plans/done/OPS-CORE-PLAN.md §G-c) — vertex validation, the map-layer style per `ZoneKind`, the
 * KEEP_IN save-time "assets outside this zone" advisory, and deriving currently-open geofence
 * breaches from the generic `LiveEvent` feed (`core/live/live-store.ts#liveEvents`) — split out so
 * every rule is unit-testable without HTTP/Leaflet/a component, mirroring every other feature's
 * own `*-logic.ts` split (`core/map/map-logic.ts`, `core/events/events-logic.ts`, …).
 */

// --- Vertex validation (docs/plans/done/OPS-CORE-PLAN.md §G's frozen wire contract: polygon must have ≥3) --

/** A polygon needs at least this many vertices to enclose any area at all (mirrors the domain's own `GeofenceZone`). */
export const MIN_ZONE_VERTICES = 3;

export interface ZoneVertex {
  readonly latitude: number;
  readonly longitude: number;
}

/** Whether a draft polygon has enough vertices to save — the draw dialog's Save-button gate. */
export function canSaveZone(vertices: readonly ZoneVertex[]): boolean {
  return vertices.length >= MIN_ZONE_VERTICES;
}

/**
 * The poka-yoke reason a draft can't be saved yet ("Add 2 more points (need at least 3)."), or
 * `null` once it can — rendered next to the disabled Save button (`.disabled-reason`,
 * `src/styles.css`) rather than only a disabled state with no explanation.
 */
export function zoneVertexCountReason(vertices: readonly ZoneVertex[]): string | null {
  const remaining = MIN_ZONE_VERTICES - vertices.length;
  if (remaining <= 0) {
    return null;
  }
  return `Add ${remaining} more point${remaining === 1 ? '' : 's'} (need at least ${MIN_ZONE_VERTICES}).`;
}

// --- Point-in-polygon (mirrors `domain.model.GeofenceZone#contains` byte-for-byte) --------------

/**
 * Ray-casting point-in-polygon (the even-odd-crossings algorithm), mirroring the backend's own
 * `GeofenceZone#contains` exactly — see that method's own Javadoc for the same planar-approximation
 * caveat (accurate at fence scale, not valid near the poles or across the antimeridian). Used here
 * only for the KEEP_IN save-time advisory below, never for actual breach evaluation (that stays a
 * server-side, `GeofenceMonitor` concern) — this is "how many assets would this zone currently
 * flag", an informational count, not a safety decision.
 */
export function polygonContains(polygon: readonly ZoneVertex[], point: ZoneVertex): boolean {
  const x = point.longitude;
  const y = point.latitude;
  let inside = false;
  for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
    const a = polygon[i];
    const b = polygon[j];
    const edgeStraddlesY = a.latitude > y !== b.latitude > y;
    if (edgeStraddlesY) {
      const xCrossing = a.longitude + ((y - a.latitude) * (b.longitude - a.longitude)) / (b.latitude - a.latitude);
      if (x < xCrossing) {
        inside = !inside;
      }
    }
  }
  return inside;
}

/**
 * The KEEP_IN draw dialog's save-time advisory (docs/plans/done/OPS-CORE-PLAN.md §G-c: "N assets currently
 * outside this zone") — how many of `positions` (every asset with a currently-known position, from
 * `core/map/map-logic.ts#FleetMarker.position`) fall outside `polygon`. Advisory, not blocking: the
 * dialog still lets the operator save regardless (a KEEP_IN zone drawn before any asset is deployed
 * to it is completely normal). Only ever meaningful for `KEEP_IN` — callers gate on `kind` before
 * showing it, this function itself is kind-agnostic (it just counts "outside").
 */
export function assetsOutsideZoneCount(polygon: readonly ZoneVertex[], positions: readonly GeoPosition[]): number {
  if (polygon.length < MIN_ZONE_VERTICES) {
    return 0;
  }
  return positions.filter((position) => !polygonContains(polygon, position)).length;
}

// --- Map-layer style (docs/plans/done/OPS-CORE-PLAN.md §G-c: "KEEP_OUT red ~12% fill + dashed border; KEEP_IN
// accent dashed border no fill") -----------------------------------------------------------------
//
// Leaflet writes path colours as SVG presentation attributes, which do not resolve `var(--token)` —
// so, like `shared/map/tactical-map/tactical-map-logic.ts#resolveMapColors` (the identical seam,
// read that function's own comment for the full "why the live theme, why the painting element"
// case), the zone stroke/fill colour has to be a literal. It used to be a permanent hex snapshot of
// the *dark* theme's tokens (`KEEP_OUT_COLOR`/`KEEP_IN_COLOR`, frozen at import time) — correct only
// while dark was this app's default theme; once light became the default
// (docs/plans/done/VISUAL-REFRESH-PLAN.md) every keep-in zone boundary kept painting in dark-theme blue on a
// light page. `resolveZoneColors` fixes it the same way `resolveMapColors` does: read the literal
// from the live theme, via a `readVar` seam the caller supplies, so this module itself stays
// DOM-free and unit-testable with a fake reader.

/** The zone stroke colours this module draws, resolved from the live theme — see the section comment above. */
export interface ZoneColors {
  readonly keepIn: string;
  readonly keepOut: string;
}

/**
 * Last-resort literals — the exact pre-fix, dark-theme-only values (`--color-danger`/`--color-info`
 * at their dark-theme step), used only where `resolveZoneColors`'s `readVar` genuinely can't resolve
 * a token (e.g. a test with no stylesheet loaded). A resolution failure degrades to today's known
 * behaviour rather than an invalid/empty Leaflet colour string.
 */
export const FALLBACK_ZONE_COLORS: ZoneColors = {
  keepIn: '#4f8cff',
  keepOut: '#ff5d5d',
};

/**
 * Resolves {@link ZoneColors} via `readVar` (typically
 * `(name) => getComputedStyle(element).getPropertyValue(name)`, read off the element that actually
 * paints — see `TacticalMap#refreshMapColors`'s own comment for why that beats `:root`). `keepIn`
 * tracks `--color-info` (the app's one accent), `keepOut` tracks `--color-danger` — exactly the
 * roles `zoneLayerStyle`'s own doc comment names.
 */
export function resolveZoneColors(readVar: (name: string) => string | undefined): ZoneColors {
  const read = (name: string, fallback: string): string => {
    const value = readVar(name)?.trim();
    return value && value.length > 0 ? value : fallback;
  };
  return {
    keepIn: read('--color-info', FALLBACK_ZONE_COLORS.keepIn),
    keepOut: read('--color-danger', FALLBACK_ZONE_COLORS.keepOut),
  };
}

/** A plain, Leaflet-`PathOptions`-shaped style object — kept structural (not `import`ing Leaflet's own type) so this stays a pure, framework-free module. */
export interface ZoneLayerStyle {
  readonly color: string;
  readonly weight: number;
  readonly dashArray: string;
  readonly fill: boolean;
  readonly fillColor?: string;
  readonly fillOpacity?: number;
  readonly opacity: number;
}

/**
 * The zone→Leaflet-layer style mapping (docs/plans/done/OPS-CORE-PLAN.md §G-c's frozen visual spec):
 * `KEEP_OUT` — red ~12% fill, dashed border; `KEEP_IN` — accent dashed border, no fill. A disabled
 * zone (`enabled === false`) renders dimmed (halved opacity) rather than a third color — "this zone
 * exists but isn't currently enforced", not a new visual language. `colors` defaults to
 * {@link FALLBACK_ZONE_COLORS} so an existing caller that only cares about the style *shape*
 * (`geofence-zone-dialog.ts`'s own preview map, this file's own spec) keeps compiling and behaving
 * unchanged; `TacticalMap` is the one caller that passes the live-resolved colours it read at paint
 * time (see `resolveZoneColors` above).
 */
export function zoneLayerStyle(kind: ZoneKind, enabled: boolean = true, colors: ZoneColors = FALLBACK_ZONE_COLORS): ZoneLayerStyle {
  const opacity = enabled ? 0.9 : 0.4;
  if (kind === 'KEEP_OUT') {
    return { color: colors.keepOut, weight: 2, dashArray: '6 6', fill: true, fillColor: colors.keepOut, fillOpacity: enabled ? 0.12 : 0.05, opacity };
  }
  return { color: colors.keepIn, weight: 2, dashArray: '6 6', fill: false, opacity };
}

/** `KEEP_OUT` → `"KEEP-OUT"`, `KEEP_IN` → `"KEEP-IN"` — the hyphenated display form used in every message/label. */
export function zoneKindLabel(kind: ZoneKind): string {
  return kind === 'KEEP_OUT' ? 'KEEP-OUT' : 'KEEP-IN';
}

// --- Breach derivation from the generic `LiveEvent` feed (docs/plans/done/OPS-CORE-PLAN.md §G's frozen
// contract: "breach events ride the existing `event` SSE topic … EventType GEOFENCE_BREACH") -----

export type BreachDirection = 'enter' | 'exit';

/** One breach event's decoded attributes — mirrors the frozen `Event.attributes` map exactly. */
export interface GeofenceBreach {
  readonly assetId: string;
  readonly zoneId: string;
  readonly zoneName: string;
  readonly kind: ZoneKind;
  readonly direction: BreachDirection;
}

function isZoneKind(value: string | undefined): value is ZoneKind {
  return value === 'KEEP_IN' || value === 'KEEP_OUT';
}

function isBreachDirection(value: string | undefined): value is BreachDirection {
  return value === 'enter' || value === 'exit';
}

/**
 * Decodes one `LiveEvent` into a `GeofenceBreach`, or `undefined` when it isn't a (well-formed)
 * `GEOFENCE_BREACH` event — every attribute is read defensively (a malformed/older-shaped event
 * degrades to "not a breach" rather than a crash, this app's usual "unknown, not fabricated" rule).
 */
export function parseGeofenceBreach(event: LiveEvent): GeofenceBreach | undefined {
  if (event.type !== 'GEOFENCE_BREACH') {
    return undefined;
  }
  const { assetId, zoneId, zoneName, kind, direction } = event.attributes;
  if (!assetId || !zoneId || !zoneName || !isZoneKind(kind) || !isBreachDirection(direction)) {
    return undefined;
  }
  return { assetId, zoneId, zoneName, kind, direction };
}

/**
 * Every currently-open breach, derived from `LiveStore.liveEvents()` (docs/plans/done/OPS-CORE-PLAN.md §G:
 * "an 'enter' opens the concern for that asset+zone, matching 'exit' clears it"). `events` is
 * assumed **newest-first** (`LiveStore.liveEvents`'s own contract — it prepends on arrival), which
 * is what makes a single forward scan correct without needing to reverse first: the *first* time a
 * given `(assetId, zoneId)` pair is encountered while scanning newest-first is, by construction,
 * that pair's most recent event — so recording only the first-seen direction per key already
 * reconstructs "the latest known state" with no need to replay the whole history in order.
 */
export function activeGeofenceBreaches(events: readonly LiveEvent[]): readonly GeofenceBreach[] {
  const seenKeys = new Set<string>();
  const active: GeofenceBreach[] = [];
  for (const event of events) {
    const breach = parseGeofenceBreach(event);
    if (!breach) {
      continue;
    }
    const key = `${breach.assetId}::${breach.zoneId}`;
    if (seenKeys.has(key)) {
      continue;
    }
    seenKeys.add(key);
    if (breach.direction === 'enter') {
      active.push(breach);
    }
  }
  return active;
}

/** Groups `activeGeofenceBreaches`' own output by `assetId` — `command-logic.ts#buildEntityRows`'s own per-asset input shape. */
export function groupBreachesByAsset(breaches: readonly GeofenceBreach[]): ReadonlyMap<string, readonly GeofenceBreach[]> {
  const byAsset = new Map<string, GeofenceBreach[]>();
  for (const breach of breaches) {
    const existing = byAsset.get(breach.assetId);
    if (existing) {
      existing.push(breach);
    } else {
      byAsset.set(breach.assetId, [breach]);
    }
  }
  return byAsset;
}

/**
 * The attention rail's own "why" sentence for a set of active breaches on one asset (docs/plans/done/OPS-CORE-PLAN.md
 * §G-c: `geofence-breach`, the new top-rank reason) — one clause per zone, e.g. "KEEP-OUT breach —
 * North perimeter; KEEP-IN breach — Charging pad.".
 */
export function geofenceBreachReasonText(breaches: readonly GeofenceBreach[]): string {
  return `${breaches.map((breach) => `${zoneKindLabel(breach.kind)} breach — ${breach.zoneName}`).join('; ')}.`;
}

/**
 * The notification bell's own toast text for a single, freshly-arrived breach `enter` event
 * (docs/plans/done/OPS-CORE-PLAN.md §G-c: "message like 'KEEP-OUT breach — <zoneName>'") — `undefined` for an
 * `exit` (clearing a breach is relief, not a new alert worth a toast) or anything that doesn't
 * parse as a breach at all.
 */
export function geofenceBreachToastMessage(event: LiveEvent): string | undefined {
  const breach = parseGeofenceBreach(event);
  if (!breach || breach.direction !== 'enter') {
    return undefined;
  }
  return `${zoneKindLabel(breach.kind)} breach — ${breach.zoneName}`;
}
