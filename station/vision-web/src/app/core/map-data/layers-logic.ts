import type { AccessLevel, LayerGrant, LayerKind, MapEventPayload, MapLayer } from '../api/models';
import { applyMapEvents, type MapEntitySpec } from './map-event-logic';

/**
 * Pure, Angular-free logic behind `core/map-data/layers-store.ts` (docs/plans/done/MAP-REWORK-PLAN.md §3/§5.2)
 * — the access-level ranking every "may I show this control?" decision in the map UI reads, the
 * layer ordering, the SSE fold, and the wholesale-grants editing reducers.
 *
 * **The ranking is advisory, never authoritative.** `myAccess` arrives already resolved server-side
 * by `MapAccessPolicy` (§3's max-of-the-matching-rules); nothing here re-derives it from role or
 * ownership. What these helpers do is let the UI *hide what the server would forbid* — the plan's
 * own §5.2 rule — while every real decision still belongs to the server, whose 403/404 surfaces as
 * a toast through the stores' shared `describeHttpError` path.
 */

// --- Access levels -------------------------------------------------------------------------------

/** Least → most privileged, matching `domain.model.AccessLevel`'s own declaration order. */
export const ACCESS_LEVELS: readonly AccessLevel[] = ['VIEW', 'CONTRIBUTE', 'MANAGE'];

const ACCESS_LABELS: Record<AccessLevel, string> = {
  VIEW: 'View',
  CONTRIBUTE: 'Contribute',
  MANAGE: 'Manage',
};

/** The one ranking — 0/1/2. Never inline an ordinal comparison at a call site; go through {@link atLeast}. */
export function accessRank(level: AccessLevel): number {
  return ACCESS_LEVELS.indexOf(level);
}

/** `undefined` (no access at all) is always below every requirement — the honest answer for a layer this viewer was never given. */
export function atLeast(level: AccessLevel | undefined, required: AccessLevel): boolean {
  return level !== undefined && accessRank(level) >= accessRank(required);
}

export function accessLevelLabel(level: AccessLevel): string {
  return ACCESS_LABELS[level];
}

/** Anything carrying a resolved access level — a `MapLayer`, or a bare `{myAccess}` a caller derived. */
export interface HasAccess {
  readonly myAccess: AccessLevel;
}

export function canView(layer: HasAccess | undefined): boolean {
  return atLeast(layer?.myAccess, 'VIEW');
}

export function canContribute(layer: HasAccess | undefined): boolean {
  return atLeast(layer?.myAccess, 'CONTRIBUTE');
}

export function canManage(layer: HasAccess | undefined): boolean {
  return atLeast(layer?.myAccess, 'MANAGE');
}

// --- Layer identity + ordering -------------------------------------------------------------------

const LAYER_KIND_LABELS: Record<LayerKind, string> = {
  COP: 'Common picture',
  TEAM: 'Team',
  PERSONAL: 'Personal',
};

export function layerKindLabel(kind: LayerKind): string {
  return LAYER_KIND_LABELS[kind];
}

/**
 * `GET /api/map/layers` already serves COP first then by name (§4.1); this reapplies that order
 * after an SSE upsert prepends a newly-created layer, so the panel never reorders itself differently
 * from a fresh page load.
 */
export function sortLayers(layers: readonly MapLayer[]): readonly MapLayer[] {
  return [...layers].sort((a, b) => {
    if ((a.kind === 'COP') !== (b.kind === 'COP')) {
      return a.kind === 'COP' ? -1 : 1;
    }
    return a.name.localeCompare(b.name, undefined, { sensitivity: 'base' });
  });
}

export function findLayer(layers: readonly MapLayer[], layerId: string | undefined): MapLayer | undefined {
  return layerId === undefined ? undefined : layers.find((layer) => layer.layerId === layerId);
}

/** The viewer's own level on `layerId`, or `undefined` for a layer they can't see (or that no longer exists). */
export function accessTo(layers: readonly MapLayer[], layerId: string | undefined): AccessLevel | undefined {
  return findLayer(layers, layerId)?.myAccess;
}

/** The layer's display name, or `undefined` — callers render an em dash rather than the raw uuid. */
export function layerName(layers: readonly MapLayer[], layerId: string | undefined): string | undefined {
  return findLayer(layers, layerId)?.name;
}

export function copLayer(layers: readonly MapLayer[]): MapLayer | undefined {
  return layers.find((layer) => layer.kind === 'COP');
}

/** The layer picker's own options — everything the viewer may actually write to. */
export function contributableLayers(layers: readonly MapLayer[]): readonly MapLayer[] {
  return layers.filter((layer) => canContribute(layer));
}

/** The rows the layer manager may expose a grants editor / rename / delete on. */
export function manageableLayers(layers: readonly MapLayer[]): readonly MapLayer[] {
  return layers.filter((layer) => canManage(layer));
}

/**
 * Which layer a new mark/drawing should default to when the operator hasn't picked one: the first
 * contributable **non-COP** layer (their team or personal layer — the COP is a promotion target, not
 * a scratchpad, per §3's own "never COP directly"), falling back to any contributable layer.
 * `undefined` means "let the server decide" — `CreateMarkRequest.layerId` is then simply omitted and
 * §3's server-side default (first TEAM layer, else an auto-created PERSONAL one) applies. That
 * fallback is what makes the palette work on a very first login, before any layer list has landed.
 */
export function defaultContributeLayerId(layers: readonly MapLayer[]): string | undefined {
  const contributable = contributableLayers(layers);
  const preferred = contributable.find((layer) => layer.kind !== 'COP') ?? contributable[0];
  return preferred?.layerId;
}

// --- SSE fold ------------------------------------------------------------------------------------

const LAYER_SPEC: MapEntitySpec<MapLayer> = {
  entity: 'layer',
  idOf: (layer) => layer.layerId,
  payloadOf: (event) => event.layer,
};

/**
 * Folds the `layer` half of a run of `map` deltas into the list, re-sorting afterwards.
 *
 * **A layer arriving over SSE never carries `grants`** (§4.3) — so an upsert would blank the grants
 * a manager just loaded over REST. This keeps the previously-known `grants` whenever the incoming
 * layer has none, which is both correct (SSE simply doesn't carry them) and honest (a real
 * grants change is still visible: the level/`myAccess`/counts all update, and the manager's next
 * `PUT`/refresh re-reads the authoritative list).
 */
export function applyLayerEvents(layers: readonly MapLayer[], events: readonly MapEventPayload[]): readonly MapLayer[] {
  const merged = applyMapEvents(layers, events, {
    ...LAYER_SPEC,
    payloadOf: (event) => {
      const incoming = event.layer;
      if (incoming === undefined || incoming.grants !== undefined) {
        return incoming;
      }
      const known = findLayer(layers, incoming.layerId);
      return known?.grants === undefined ? incoming : { ...incoming, grants: known.grants };
    },
  });
  return merged === layers ? layers : sortLayers(merged);
}

// --- Grants editing (wholesale `PUT`, so these are list reducers, not deltas) ---------------------

/** One grant's identity: a subject may appear at most once per layer, so `type:id` is the key. */
export function grantKey(grant: Pick<LayerGrant, 'subjectType' | 'subjectId'>): string {
  return `${grant.subjectType}:${grant.subjectId}`;
}

/** Adds a grant, or replaces the level of one already naming the same subject — never a duplicate row. */
export function upsertGrant(grants: readonly LayerGrant[], grant: LayerGrant): readonly LayerGrant[] {
  const key = grantKey(grant);
  const index = grants.findIndex((candidate) => grantKey(candidate) === key);
  if (index === -1) {
    return [...grants, grant];
  }
  const next = [...grants];
  next[index] = grant;
  return next;
}

export function removeGrant(
  grants: readonly LayerGrant[],
  subject: Pick<LayerGrant, 'subjectType' | 'subjectId'>,
): readonly LayerGrant[] {
  const key = grantKey(subject);
  return grants.filter((candidate) => grantKey(candidate) !== key);
}

/** Whether `subject` already appears — the pickers grey out a subject that's already granted rather than silently overwriting its level. */
export function hasGrant(
  grants: readonly LayerGrant[],
  subject: Pick<LayerGrant, 'subjectType' | 'subjectId'>,
): boolean {
  const key = grantKey(subject);
  return grants.some((candidate) => grantKey(candidate) === key);
}
