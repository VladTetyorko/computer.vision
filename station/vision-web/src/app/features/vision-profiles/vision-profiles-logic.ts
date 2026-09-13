import type {
  BindingScope,
  CvCoverageRow,
  CvModel,
  CvProfile,
  CvProfileEventRule,
  CvProfileIntent,
  CvProfileRequest,
  CvProfileTracking,
  Membership,
  TrackingMode,
} from '../../core/api/models';
import { pluralize } from '../../shared/ui/text-logic';

/**
 * Pure logic behind `/vision/profiles` (docs/plans/active/CV-SETTINGS-PLAN.md §4's UI sketch, wave
 * W6) — framework-free, vitest-covered (`vision-profiles-logic.spec.ts`), kept separate from
 * `VisionProfilesFacade` per this codebase's own "extract testable logic into a pure `*-logic.ts`"
 * convention (`MODULE.md`).
 */

// --- Listing & row-level gates ---------------------------------------------------------------

/**
 * Built-in profiles first (alphabetical among themselves), then custom ones (alphabetical) — the
 * same "the one you can't break sits up top, as a reference" ordering `ModelsPage` gives its own
 * Live-model-first roster, applied here to "the ones nobody can accidentally break".
 */
export function sortProfilesForDisplay(profiles: readonly CvProfile[]): readonly CvProfile[] {
  return [...profiles].sort((a, b) => {
    if (a.builtIn !== b.builtIn) {
      return a.builtIn ? -1 : 1;
    }
    return a.name.localeCompare(b.name);
  });
}

/** A built-in profile is never edited in place (§3.4) — only a non-built-in profile the caller may manage. */
export function canEditProfile(profile: CvProfile, canManage: boolean): boolean {
  return canManage && !profile.builtIn;
}

/** Forking is how a built-in profile becomes customizable — available to any manager on any profile,
 * built-in or not (a custom profile can also be forked, as a quick starting point for a variant). */
export function canForkProfile(_profile: CvProfile, canManage: boolean): boolean {
  return canManage;
}

/** Only a non-built-in profile the caller may manage can be deleted — mirrors {@link canEditProfile}. */
export function canDeleteProfile(profile: CvProfile, canManage: boolean): boolean {
  return canManage && !profile.builtIn;
}

// --- Draft editing (create / edit / fork) ----------------------------------------------------

/**
 * The editor's own working copy of a profile — a plain, patchable object rather than individual
 * signals (`VisionProfilesFacade.draft`/`patchDraft`), so the template can bind every field with one
 * `[ngModel]`/`(ngModelChange)` pair per control without the facade growing one signal per field.
 *
 * `sourceId` is `null` while creating a brand-new profile (via "New profile" or "Fork") and the
 * source profile's own id while editing one in place — {@link draftToRequest} never reads it, it is
 * purely how the facade decides POST vs. PUT on submit.
 *
 * `labelFilterText`/`labelDenyFilterText` hold the allow/deny lists as one comma-or-newline-separated
 * string for a plain `<textarea>` — {@link parseLabelList}/{@link formatLabelList} are the only two
 * places that cross between this and `CvProfile#labelFilter`'s `readonly string[]`.
 *
 * `existingEventRule` is the loaded profile's own `eventRule`, shown read-only in the editor (§5.1:
 * start-time only, never part of `CvProfileRequest` — there is nothing to submit here even if a
 * control existed) — `null` when creating a brand-new profile, since none exists yet until the
 * backend assigns one at creation and a later read brings it back (§8 leaves "what the server
 * defaults eventRule to on create" to the backend; this UI has no control for it either way, honesty
 * rule §3.5 "no control without a backend endpoint").
 *
 * `intent` (added wave W3.6, docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7) is the operator's "what
 * am I looking for" pick — `''` means no intent chosen, {@link draftToRequest}'s exact "omit `intent`
 * entirely" sentinel, and is what every existing create/edit/fork path still produces when the
 * intent picker is left alone, so today's fully-explicit flow has zero behavior change. Never
 * inferred from a loaded profile's `sources` in {@link draftFromProfile}/{@link forkDraftFromProfile}
 * — that provenance is create/update-response-only, not persisted on the profile itself (§4.7 "as
 * built in W2"), so a freshly-opened editor always starts with no intent selected, even for a
 * profile originally created via one.
 */
export interface ProfileDraft {
  readonly sourceId: string | null;
  readonly name: string;
  readonly description: string;
  readonly model: string;
  readonly confidenceThreshold: number;
  readonly inferenceFps: number;
  readonly labelFilterText: string;
  readonly labelDenyFilterText: string;
  readonly detectionEnabled: boolean;
  readonly trackingMode: TrackingMode;
  readonly trackingEngineId: string;
  readonly trackingCapabilityLevel: number;
  readonly trackingVerifyEveryMillis: number;
  readonly trackingFollowFps: number;
  readonly existingEventRule: CvProfileEventRule | null;
  readonly intent: CvProfileIntent | '';
}

/** A fresh draft for "New profile" — `defaultModel` is the first roster entry (or `''` if the
 * roster hasn't loaded, which the editor's own required-field validation then catches). */
export function emptyProfileDraft(defaultModel: string): ProfileDraft {
  return {
    sourceId: null,
    name: '',
    description: '',
    model: defaultModel,
    confidenceThreshold: 0.35,
    inferenceFps: 2,
    labelFilterText: '',
    labelDenyFilterText: '',
    detectionEnabled: true,
    trackingMode: 'OFF',
    trackingEngineId: '',
    trackingCapabilityLevel: 0,
    trackingVerifyEveryMillis: 2000,
    trackingFollowFps: 15,
    existingEventRule: null,
    intent: '',
  };
}

/** Populates a draft from `profile` for in-place editing — `sourceId` set, so the facade PUTs. */
export function draftFromProfile(profile: CvProfile): ProfileDraft {
  return {
    sourceId: profile.id,
    name: profile.name,
    description: profile.description,
    model: profile.model,
    confidenceThreshold: profile.confidenceThreshold,
    inferenceFps: profile.inferenceFps,
    labelFilterText: formatLabelList(profile.labelFilter),
    labelDenyFilterText: formatLabelList(profile.labelDenyFilter),
    detectionEnabled: profile.detectionEnabled,
    trackingMode: profile.tracking.mode,
    trackingEngineId: profile.tracking.engineId,
    trackingCapabilityLevel: profile.tracking.capabilityLevel,
    trackingVerifyEveryMillis: profile.tracking.verifyEveryMillis,
    trackingFollowFps: profile.tracking.followFps,
    existingEventRule: profile.eventRule,
    // Never inferred from `profile.sources` — see this interface's own doc comment on `intent`.
    intent: '',
  };
}

/** Populates a draft from `profile` as the starting point for a brand-new copy — `sourceId` stays
 * `null` (the facade POSTs a new profile), and the name is de-duplicated against `existingNames`. */
export function forkDraftFromProfile(profile: CvProfile, existingNames: readonly string[]): ProfileDraft {
  return { ...draftFromProfile(profile), sourceId: null, name: forkedProfileName(profile.name, existingNames) };
}

/**
 * Applies an intent pick to `draft` (the editor's intent `<select>`, §4.7 wave W3.6).
 *
 * `CvProfileRequest#model` is always sent, and the server only seeds it from intent when the
 * request's own `model` is **blank** (`CvProfileRequest#toSpec()`, Java) — so picking an intent must
 * be able to blank the model select's own value. The **"no intent" → "an intent" transition** blanks
 * `draft.model` (deferring it to the platform, matching what an operator who has never touched Model
 * would expect from picking an intent); an **intent → a *different* intent** transition leaves
 * `draft.model` exactly as it is, so an operator's own explicit model choice — made any time after
 * the first intent pick — stays sticky even if the intent selection changes again, matching the
 * plan's own acceptance wording. This is the one and only place `intent` and `model` interact; every
 * other transition (picking a concrete model, in either intent state) goes through {@link
 * VisionProfilesFacade.patchDraft} untouched, per that facade's own single-field-at-a-time contract.
 */
export function applyIntentToDraft(draft: ProfileDraft, intent: CvProfileIntent | ''): ProfileDraft {
  const model = draft.intent === '' && intent !== '' ? '' : draft.model;
  return { ...draft, intent, model };
}

/** `"mast-cams"` → `"Copy of mast-cams"`, then `"Copy of mast-cams (2)"`, `"Copy of mast-cams (3)"`, …
 * against whatever names already exist — never silently overwrites an existing profile by name collision. */
export function forkedProfileName(sourceName: string, existingNames: readonly string[]): string {
  const taken = new Set(existingNames.map((name) => name.trim().toLowerCase()));
  const base = `Copy of ${sourceName}`;
  if (!taken.has(base.toLowerCase())) {
    return base;
  }
  let n = 2;
  while (taken.has(`${base} (${n})`.toLowerCase())) {
    n += 1;
  }
  return `${base} (${n})`;
}

/** Splits a comma- and/or newline-separated free-text list into trimmed, deduped, non-empty entries
 * — the allow/deny label lists' one text-entry control (no per-chip add/remove UI this wave). */
export function parseLabelList(text: string): readonly string[] {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const raw of text.split(/[,\n]/)) {
    const value = raw.trim();
    if (value.length === 0 || seen.has(value)) {
      continue;
    }
    seen.add(value);
    result.push(value);
  }
  return result;
}

/** The inverse of {@link parseLabelList} — joins for display/editing as `"a, b, c"`. */
export function formatLabelList(labels: readonly string[]): string {
  return labels.join(', ');
}

/** Every human-readable validation problem with `draft`, or `[]` when it is submittable — the
 * editor's Save button disables while this is non-empty, and the errors render as one `vision-notice`
 * rather than per-field messages (this page's forms are short enough that a single list reads fine,
 * matching `org-settings.html`'s own required-attribute-only validation for its simpler forms, but
 * named/explicit here since a confidence/fps range isn't expressible with a plain HTML `required`). */
export function validateDraft(draft: ProfileDraft): readonly string[] {
  const errors: string[] = [];
  if (draft.name.trim().length === 0) {
    errors.push('Name is required.');
  }
  // A blank model is only valid while an intent is chosen (the server resolves it from that intent,
  // `CvProfileRequest#toSpec()`) — see `applyIntentToDraft`'s own doc comment. With no intent chosen,
  // today's exact rule holds unchanged: a concrete model is required.
  if (draft.intent === '' && draft.model.trim().length === 0) {
    errors.push('Choose a model.');
  }
  // Mirrors `IntentPolicyResolver.resolve`'s own `IllegalArgumentException` for `Intent.CUSTOM` with
  // an empty/absent label filter — caught here so the operator sees it before submitting, not as a
  // raw 400 from the server.
  if (draft.intent === 'CUSTOM' && parseLabelList(draft.labelFilterText).length === 0) {
    errors.push('Custom intent needs at least one class in the label filter.');
  }
  if (!(draft.confidenceThreshold >= 0 && draft.confidenceThreshold <= 1)) {
    errors.push('Confidence threshold must be between 0 and 1.');
  }
  if (!(draft.inferenceFps >= 1)) {
    errors.push('Inference rate must be at least 1 fps.');
  }
  if (draft.trackingMode !== 'OFF') {
    if (!(draft.trackingCapabilityLevel >= 0)) {
      errors.push('Tracking capability level cannot be negative.');
    }
    if (!(draft.trackingVerifyEveryMillis >= 0)) {
      errors.push('Verify interval cannot be negative.');
    }
    if (!(draft.trackingFollowFps >= 0)) {
      errors.push('Follow rate cannot be negative.');
    }
  }
  return errors;
}

/** Builds the exact `CvProfileRequest` body for `createCvProfile`/`updateCvProfile` — the only place
 * a `ProfileDraft` turns back into the wire shape. `eventRule` is never included (§5.1: start-time
 * only, not part of this request type at all).
 *
 * `intent` is omitted entirely (not sent as `null`) when `draft.intent === ''` — "no intent chosen"
 * — exactly {@link CvProfileRequest#intent}'s own "skip intent resolution entirely" sentinel.
 * `labelFilter` needs no equivalent special-casing for "leave this to the platform": {@link
 * parseLabelList}`('')` already returns `[]`, which is already the exact empty-list sentinel
 * `CvProfileRequest#toSpec()` checks for, so an untouched label-filter textarea already round-trips
 * correctly with zero changes here. */
export function draftToRequest(draft: ProfileDraft): CvProfileRequest {
  const tracking: CvProfileTracking = {
    mode: draft.trackingMode,
    engineId: draft.trackingEngineId,
    capabilityLevel: draft.trackingCapabilityLevel,
    verifyEveryMillis: draft.trackingVerifyEveryMillis,
    followFps: draft.trackingFollowFps,
  };
  return {
    name: draft.name.trim(),
    description: draft.description.trim(),
    model: draft.model,
    confidenceThreshold: draft.confidenceThreshold,
    inferenceFps: draft.inferenceFps,
    labelFilter: parseLabelList(draft.labelFilterText),
    labelDenyFilter: parseLabelList(draft.labelDenyFilterText),
    detectionEnabled: draft.detectionEnabled,
    tracking,
    intent: draft.intent === '' ? undefined : draft.intent,
  };
}

/**
 * The save-toast text for `VisionProfilesFacade.saveDraft()` — names exactly which knob(s) `saved`'s
 * own `sources` reports as resolved from `intent`, e.g. `'"Vehicles patrol" saved — model resolved
 * from your Vehicles intent.'`. Never claims a knob was intent-resolved when its own `sources` entry
 * is absent (§3.5 honesty rule) — `intent === ''` alone (impossible to pair with a non-`undefined`
 * `sources` entry in practice, since the server can only have seeded a field from an intent that was
 * actually sent, but checked explicitly here too so this function never *names* an intent it cannot
 * point to) falls back to the plain saved/created message, same as reporting nothing resolved.
 */
export function saveOutcomeMessage(saved: CvProfile, intent: CvProfileIntent | '', isUpdate: boolean): string {
  const verb = isUpdate ? 'saved' : 'created';
  const plain = `"${saved.name}" ${verb}.`;
  if (intent === '') {
    return plain;
  }
  const resolvedKnobs: string[] = [];
  if (saved.sources.model === 'INTENT') {
    resolvedKnobs.push('model');
  }
  if (saved.sources.labelFilter === 'INTENT') {
    resolvedKnobs.push('label filter');
  }
  if (resolvedKnobs.length === 0) {
    return plain;
  }
  return `"${saved.name}" ${verb} — ${resolvedKnobs.join(' and ')} resolved from your ${describeIntent(intent)} intent.`;
}

/** `CvProfileIntent` → the editor's own picker label, reused for the save-toast's "your … intent" clause. */
export function describeIntent(intent: CvProfileIntent): string {
  switch (intent) {
    case 'PEOPLE':
      return 'People';
    case 'VEHICLES':
      return 'Vehicles';
    case 'EVERYTHING':
      return 'Everything';
    case 'CUSTOM':
      return 'Custom';
  }
}

// --- Detection policy (D7, §4.7: "cv.detection-policy = ALWAYS gets a control on the asset binding")

/**
 * `Asset#attributes` key `DetectionPolicy.ATTRIBUTE_KEY` stores the per-asset always-run choice
 * under — mirrors `perception.domain.model.DetectionPolicy` (docs/plans/active/ALWAYS-ON-FLOW-PLAN.md
 * wave D1) verbatim: kept here, not in `core/fleet/asset-attributes.ts` (that file's own precedent
 * for an attribute-map key constant), since this page is `cv.detection-policy`'s only web reader and
 * this codebase's own rule for shared logic is "a **second** consumer moves it to `core/`"
 * (`core/fleet/device-logic.ts`'s doc comment, cited by that file too).
 */
export const CV_DETECTION_POLICY_ATTRIBUTE_KEY = 'cv.detection-policy';

/**
 * Reads whether `attributes` currently sets always-on detection — mirrors
 * `DetectionPolicy#fromAttributeValue`'s own parse exactly (trimmed, case-insensitive, only the
 * literal `"always"` reads as on; an absent, blank, or unrecognized value all fail closed to `false`,
 * matching that enum's own "a garbage value silently defaulting to today's behavior is the safe
 * failure" reasoning).
 */
export function isDetectionAlways(attributes: Record<string, string>): boolean {
  return attributes[CV_DETECTION_POLICY_ATTRIBUTE_KEY]?.trim().toLowerCase() === 'always';
}

/**
 * Merges the always-on choice into `attributes` — never a replacement map (`AssetEdit#attributes`
 * is a **wholesale replacement**, `PATCH /api/assets/{id}`'s own doc comment: "replacement attribute
 * map … or absent to keep the current one"), so every caller must pass the asset's complete current
 * `attributes` here, exactly like `core/fleet/asset-attributes.ts#withoutRegistrationNumber`'s own
 * doc comment warns for its own key. Writes the canonical lowercase form (`DetectionPolicy
 * #toAttributeValue()`) explicitly for both states — `"always"` or `"on-view"` — rather than deleting
 * the key on `false`, so a read of the map always shows this asset's own explicit choice rather than
 * a value that only means something by its absence.
 */
export function withDetectionPolicy(attributes: Record<string, string>, always: boolean): Record<string, string> {
  return { ...attributes, [CV_DETECTION_POLICY_ATTRIBUTE_KEY]: always ? 'always' : 'on-view' };
}

// --- Model roster honesty (§3.5 rule 4: "Missing on worker" — never a silent fallback) --------

/** `undefined`/`'PRESENT'` availability reads as present (an older roster entry that predates this
 * field says nothing either way, which is not the same as a confirmed absence) — only an explicit
 * `'MISSING'` renders the warning. */
export function isModelMissingOnWorker(model: Pick<CvModel, 'availability'>): boolean {
  return model.availability === 'MISSING';
}

// --- Bindings & coverage (no `GET /api/cv/bindings` in §5.2 — derived from coverage instead) ---

/** One profile's binding footprint, derived from `GET /api/cv/coverage`'s resolved rows — the only
 * source available (§5.2 has `PUT`/`DELETE /api/cv/bindings` but no read). A binding that currently
 * resolves on zero assets (e.g. a `CATEGORY` bound with no asset of that category yet) is invisible
 * to this derivation — documented limitation, not a bug: there is nothing to count. */
export interface ProfileBindingSummary {
  readonly profileId: string;
  readonly assetCount: number;
  readonly bySource: Readonly<Partial<Record<BindingScope | 'PLATFORM', number>>>;
}

/** Groups `rows` by `profileId`, counting how many assets currently resolve to each profile and via
 * which binding scope. Every profile referenced by at least one coverage row gets an entry;
 * a profile with none simply has no key in the returned map — {@link bindingSummaryLabel} handles
 * that "not currently resolving for any asset" case. */
export function summarizeProfileBindings(rows: readonly CvCoverageRow[]): ReadonlyMap<string, ProfileBindingSummary> {
  const byProfile = new Map<string, { assetCount: number; bySource: Partial<Record<BindingScope | 'PLATFORM', number>> }>();
  for (const row of rows) {
    let entry = byProfile.get(row.profileId);
    if (!entry) {
      entry = { assetCount: 0, bySource: {} };
      byProfile.set(row.profileId, entry);
    }
    entry.assetCount += 1;
    entry.bySource[row.source] = (entry.bySource[row.source] ?? 0) + 1;
  }
  const result = new Map<string, ProfileBindingSummary>();
  for (const [profileId, entry] of byProfile) {
    result.set(profileId, { profileId, assetCount: entry.assetCount, bySource: entry.bySource });
  }
  return result;
}

/** One line for a profile card — "Not currently resolving for any asset" when {@link summary} is
 * `undefined`/empty, otherwise `"N assets"` with a parenthetical breakdown when more than one source
 * contributed (e.g. `"5 assets (3 category, 2 asset override)"`). */
export function bindingSummaryLabel(summary: ProfileBindingSummary | undefined): string {
  if (!summary || summary.assetCount === 0) {
    return 'Not currently resolving for any asset';
  }
  const sources = Object.entries(summary.bySource) as [BindingScope | 'PLATFORM', number][];
  if (sources.length <= 1) {
    return pluralize(summary.assetCount, 'asset');
  }
  const breakdown = sources
    .sort((a, b) => b[1] - a[1])
    .map(([source, count]) => `${count} ${describeBindingSourceNoun(source)}`)
    .join(', ');
  return `${pluralize(summary.assetCount, 'asset')} (${breakdown})`;
}

function describeBindingSourceNoun(source: BindingScope | 'PLATFORM'): string {
  switch (source) {
    case 'ORGANIZATION':
      return 'org default';
    case 'CATEGORY':
      return 'category';
    case 'ASSET':
      return 'asset override';
    case 'PLATFORM':
      return 'platform default';
  }
}

/** The coverage table's "Filters" column text — `"2 allowed, 1 denied"`, or `"—"` when the resolved
 * profile carries neither list (matching this table's own empty-cell convention, §5's style guide). */
export function describeCoverageFilters(row: Pick<CvCoverageRow, 'labelFilter' | 'labelDenyFilter'>): string {
  const parts: string[] = [];
  if (row.labelFilter.length > 0) {
    parts.push(`${row.labelFilter.length} allowed`);
  }
  if (row.labelDenyFilter.length > 0) {
    parts.push(`${row.labelDenyFilter.length} denied`);
  }
  return parts.length === 0 ? '—' : parts.join(', ');
}

/** The coverage table's "Source" column text. */
export function describeCoverageSource(source: BindingScope | 'PLATFORM'): string {
  switch (source) {
    case 'ORGANIZATION':
      return 'Organization default';
    case 'CATEGORY':
      return 'Category default';
    case 'ASSET':
      return 'Asset override';
    case 'PLATFORM':
      return 'Platform default (unbound)';
  }
}

/** Where a coverage row's `Clear` action should write, or `null` when there is nothing sensible for
 * *this row* to clear: `'PLATFORM'` means no binding exists anywhere for this asset (nothing to
 * clear), and `'ORGANIZATION'` is deliberately excluded here too — clearing an org default from one
 * row in a fleet-wide table would affect every other asset falling back to it, an org-wide action
 * that belongs to the dedicated "Clear organization default" control, not a per-row one. */
export function coverageRowClearTarget(row: CvCoverageRow): { readonly scopeKind: BindingScope; readonly scopeId: string } | null {
  if (row.source === 'CATEGORY') {
    return { scopeKind: 'CATEGORY', scopeId: row.categoryId };
  }
  if (row.source === 'ASSET') {
    return { scopeKind: 'ASSET', scopeId: row.assetId };
  }
  return null;
}

// --- Organization-scope helpers ----------------------------------------------------------------

/**
 * The caller's own org `groupId`, for the "Clear organization default" action and as the implicit
 * scope target when binding kind is `ORGANIZATION`. Reads `memberships[0]` — a documented
 * single-org simplification (this app's membership model already allows more than one, but the
 * profile-binding UI has no org switcher this wave); `null` with no memberships at all (an unbounded
 * principal with no explicit membership row would read this way) — `ORGANIZATION` binding is simply
 * unavailable to it, an honest "no org to bind" rather than a fabricated id.
 */
export function primaryGroupId(memberships: readonly Membership[]): string | null {
  return memberships[0]?.groupId ?? null;
}
