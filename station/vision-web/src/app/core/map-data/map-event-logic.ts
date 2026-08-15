import type { MapEventPayload } from '../api/models';

/**
 * The one place the `map` SSE topic's fold-into-a-list rule lives (docs/plans/done/MAP-REWORK-PLAN.md §4.3) —
 * pure, Angular-free, shared by all three `core/map-data/**` stores so "created/updated upsert,
 * cleared/deleted remove" can never drift between marks, drawings and layers.
 *
 * **Why one generic fold instead of three hand-written ones**: the three stores differ only in which
 * `entity` value is theirs, which field of the payload carries their object, and what that object's
 * id field is called (`markId`/`drawingId`/`layerId`). Everything else — arrival ordering, the
 * upsert-by-id semantics, the removal actions, tolerance of a malformed payload — is identical, and
 * it is exactly the part that must not drift, so it is written once here and unit-tested once.
 */

export type MapEntity = MapEventPayload['entity'];
export type MapAction = MapEventPayload['action'];

/** `cleared` (a mark status transition) and `deleted` both mean "drop it from the list". */
export function isRemoval(action: MapAction): boolean {
  return action === 'cleared' || action === 'deleted';
}

/** Upserts `item` by id — replaces an existing entry **in place** (order preserved), or prepends a genuinely new one (the lists are newest-first). */
export function upsertById<T>(items: readonly T[], item: T, idOf: (item: T) => string): readonly T[] {
  const id = idOf(item);
  const index = items.findIndex((candidate) => idOf(candidate) === id);
  if (index === -1) {
    return [item, ...items];
  }
  const next = [...items];
  next[index] = item;
  return next;
}

/** Drops `id` — a fresh array either way, so a signal keyed on the reference still re-runs. */
export function removeById<T>(items: readonly T[], id: string, idOf: (item: T) => string): readonly T[] {
  return items.filter((candidate) => idOf(candidate) !== id);
}

/** What one store needs to tell {@link applyMapEvent} about its own entity. */
export interface MapEntitySpec<T> {
  /** The `entity` discriminator this store owns — every other value is ignored. */
  readonly entity: MapEntity;
  /** The item's own id field (`markId`/`drawingId`/`layerId`). */
  readonly idOf: (item: T) => string;
  /** Pulls this entity's object out of the payload — `undefined` when the server sent none. */
  readonly payloadOf: (event: MapEventPayload) => T | undefined;
}

/**
 * Applies one live map delta to one store's list. Ignores every event for another entity (all three
 * stores read the *same* append-only arrival log — see `core/live/live-store.ts#mapEvents`), and
 * ignores an event whose own object is missing: §4.3 promises exactly one of `mark`/`drawing`/`layer`
 * per event, but a client must degrade to "no change" rather than throw if that promise is ever
 * broken by a partial deploy.
 */
export function applyMapEvent<T>(items: readonly T[], event: MapEventPayload, spec: MapEntitySpec<T>): readonly T[] {
  if (event.entity !== spec.entity) {
    return items;
  }
  const incoming = spec.payloadOf(event);
  if (incoming === undefined) {
    return items;
  }
  return isRemoval(event.action)
    ? removeById(items, spec.idOf(incoming), spec.idOf)
    : upsertById(items, incoming, spec.idOf);
}

/** Applies a run of deltas in arrival order — `LiveStore.mapEvents()` is chronological and each store folds only the tail it hasn't processed yet. */
export function applyMapEvents<T>(
  items: readonly T[],
  events: readonly MapEventPayload[],
  spec: MapEntitySpec<T>,
): readonly T[] {
  return events.reduce((acc, event) => applyMapEvent(acc, event, spec), items);
}

/**
 * The ids of every layer a run of deltas *deleted*. Deleting a layer cascades server-side (§3:
 * "deleting a layer deletes its marks + drawings (emit DELETED events for each)"), so the marks and
 * drawings stores normally learn about their own losses directly — this is the belt-and-braces the
 * marks/drawings stores apply on top, so a layer vanishing can never leave orphan pins on the map
 * even if the per-child events arrive late, out of order, or not at all.
 */
export function deletedLayerIds(events: readonly MapEventPayload[]): readonly string[] {
  return [
    ...new Set(
      events.filter((event) => event.entity === 'layer' && event.action === 'deleted').map((event) => event.layerId),
    ),
  ];
}

/** Drops every item that lives on one of `layerIds` — the cascade half of {@link deletedLayerIds}. */
export function dropByLayer<T extends { readonly layerId: string }>(
  items: readonly T[],
  layerIds: readonly string[],
): readonly T[] {
  if (layerIds.length === 0) {
    return items;
  }
  const dropped = new Set(layerIds);
  return items.filter((item) => !dropped.has(item.layerId));
}
