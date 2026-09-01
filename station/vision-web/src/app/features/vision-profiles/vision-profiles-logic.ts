import type {
  BindingScope,
  CvCoverageRow,
  CvModel,
  CvProfile,
  CvProfileEventRule,
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
  };
}

/** Populates a draft from `profile` as the starting point for a brand-new copy — `sourceId` stays
 * `null` (the facade POSTs a new profile), and the name is de-duplicated against `existingNames`. */
export function forkDraftFromProfile(profile: CvProfile, existingNames: readonly string[]): ProfileDraft {
  return { ...draftFromProfile(profile), sourceId: null, name: forkedProfileName(profile.name, existingNames) };
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
  if (draft.model.trim().length === 0) {
    errors.push('Choose a model.');
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
 * only, not part of this request type at all). */
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
  };
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
