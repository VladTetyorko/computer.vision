/**
 * The `registrationNumber` attribute-key convention (docs/UX-REWORK-PLAN.md §U-d item 3): a
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
