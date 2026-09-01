import type { AssetSummary } from '../api/models';

/**
 * The `registrationNumber` attribute-key convention (docs/plans/done/UX-REWORK-PLAN.md §U-d item 3): a
 * registration/tail number is not a first-class field on `Asset`/`AssetSummary` — it rides in the
 * existing free-form `attributes` map `PATCH /api/assets/{id}` already carries (the plan's own
 * "attributes map works; registrationNumber is an attributes key by convention" pinned note), same
 * `AssetEdit#attributes` field every other attribute edit uses. Centralized here (not in either
 * `features/onboarding/**` or `features/asset-detail/**`) because both need the exact same key —
 * the onboarding wizard's Profile step sets it, the asset detail page's inline edit reads/rewrites
 * it — and this codebase has no precedent for one feature importing another feature's private
 * module (see `core/fleet/device-logic.ts`'s doc comment).
 */
export const REGISTRATION_NUMBER_ATTRIBUTE_KEY = 'registrationNumber';

/** Reads the registration/tail number out of an asset's `attributes` map, if one was ever set. */
export function registrationNumberOf(attributes: Record<string, string>): string | undefined {
  const value = attributes[REGISTRATION_NUMBER_ATTRIBUTE_KEY];
  return value && value.trim().length > 0 ? value : undefined;
}

/**
 * Builds the `attributes` map for a `CreateAssetRequest`/`AssetEdit` — omits the key entirely for a
 * blank/absent value rather than sending an empty string, the same `@JsonInclude(NON_NULL)`-style
 * trim-and-omit convention every other request builder in this app follows
 * (`core/fleet/warehouse-logic.ts#buildAssetEdit`, `core/fleet/simulation-logic.ts#buildSimulationRequest`).
 */
export function withRegistrationNumber(
  attributes: Record<string, string>,
  registrationNumber: string | undefined,
): Record<string, string> {
  const trimmed = registrationNumber?.trim();
  if (!trimmed) {
    return attributes;
  }
  return { ...attributes, [REGISTRATION_NUMBER_ATTRIBUTE_KEY]: trimmed };
}

/**
 * Removes the registration/tail number entirely — for the asset detail page's inline edit, where
 * blanking the field means "this asset no longer has one", not "leave the old value alone" (unlike
 * {@link withRegistrationNumber}'s own blank-input behavior, which is right for a *create* flow that
 * never had a value to begin with). `PATCH /api/assets/{id}`'s `attributes` field is a full
 * replacement map, not a merge (`application.AssetEdit`'s own Javadoc: "replacement attributes, or
 * null to keep the current map") — callers must pass the asset's complete current `attributes`
 * here, never a partial delta, or every other attribute silently disappears too.
 */
export function withoutRegistrationNumber(attributes: Record<string, string>): Record<string, string> {
  if (!(REGISTRATION_NUMBER_ATTRIBUTE_KEY in attributes)) {
    return attributes;
  }
  const rest = { ...attributes };
  delete rest[REGISTRATION_NUMBER_ATTRIBUTE_KEY];
  return rest;
}

/**
 * `identity.registration`, falling back to the legacy `attributes.registrationNumber` key above
 * (docs/plans/active/WAREHOUSE-UX-CONTEXT.md's own W3→W4 handoff named this migration gap: assets
 * created before wave D1 shipped only ever got the old attribute, never the new identity field, and
 * nothing backfills it server-side — see `station/vision-web/MODULE.md`'s W4 changelog entry for the
 * full writeup, including the matching backend-side V28 migration-key bug). `identity.registration`
 * wins whenever both are present — it's the field every new write (the asset detail page's own edit,
 * onboarding's Identify step) actually targets now.
 *
 * Moved here from `features/asset-detail/asset-detail-logic.ts` in wave W4
 * (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3) when the Inventory page's own Vehicles/Equipment
 * detail panel needed the identical fallback — this codebase's own "a second consumer moves shared
 * logic to `core/`" precedent (this file's neighbor `core/fleet/inventory-logic.ts`'s doc comment).
 * `features/asset-detail/asset-detail-logic.ts` re-exports this verbatim so its own pre-existing
 * import site keeps working.
 */
export function effectiveRegistration(asset: Pick<AssetSummary, 'identity' | 'attributes'>): string | undefined {
  return asset.identity?.registration?.trim() || registrationNumberOf(asset.attributes);
}
