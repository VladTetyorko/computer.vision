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

// --- Profile-card display (wave W7, decision E22: a knob a profile leaves unset shows as
// "Inherited," never a fabricated concrete value — §3.5 honesty rule) --------------------------

/** The profile-card grid's own fallback text for any knob `profile` itself leaves unset. */
export const INHERITED_KNOB_LABEL = 'Inherited';

/** `profile.model` for the card grid — `undefined` (or, defensively, blank) reads as {@link
 *  INHERITED_KNOB_LABEL}. */
export function describeOptionalModel(model: string | undefined): string {
  return model && model.trim().length > 0 ? model : INHERITED_KNOB_LABEL;
}

/** `profile.confidenceThreshold`/`profile.inferenceFps` for the card grid. */
export function describeOptionalNumber(value: number | undefined): string {
  return value === undefined ? INHERITED_KNOB_LABEL : String(value);
}

/** `profile.detectionEnabled`'s own three-way card state — the card's chip already distinguishes
 *  On/Off by color; the third, unset state gets its own label rather than being folded into "Off"
 *  (an inherited profile might well resolve to detection being ON once the fold actually runs). */
export type DetectionCardState = 'ON' | 'OFF' | 'INHERITED';

/** `profile.detectionEnabled` → the card's own three-way state. */
export function describeDetectionCardState(value: boolean | undefined): DetectionCardState {
  if (value === undefined) {
    return 'INHERITED';
  }
  return value ? 'ON' : 'OFF';
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
 * **Wave W7 — a profile is a patch** (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7/§8, decision
 * E22): every knob below is now individually inherit-capable, mirroring `CvProfile`'s own now-
 * nullable fields — `undefined` (or, for {@link model}, blank text) means "leave this knob unset,
 * inherit from the tier below," exactly like the wire contract itself. Three different encodings
 * are used, deliberately, one per knob shape:
 *
 * - **Blank text = inherit** ({@link model}): a model has no legitimate "explicit blank" value of
 *   its own, so plain blank text is unambiguous and needs no extra state.
 * - **A separate `*Inherit` flag** ({@link labelFilterInherit}/{@link labelDenyFilterInherit}): an
 *   EXPLICIT empty list (`[]`, "keep/deny nothing") is itself a real, meaningful value distinct from
 *   "inherit" (`UpdateStreamConfigRequest#labelDenyFilter`'s own doc comment already establishes this
 *   for the sibling live-PATCH field), so blank text alone cannot safely carry both meanings the way
 *   {@link model}'s does — CLAUDE.md rule 10 forbids overloading one control's "empty" state to mean
 *   two different things.
 * - **A dedicated inherit sentinel on the `<select>` itself** ({@link trackingMode} via {@link
 *   trackingModeSelectValue}, {@link trackingEngineId} via {@link engineSelectValue}, {@link
 *   detectionEnabled} via {@link detectionSelectValue}): each of these already has its own real,
 *   explicit "off"/"default" value distinct from unset (`'OFF'` is a real tracking mode; `''` is a
 *   real "deployment default" engine; `false` is a real "detection off"), so the option list itself
 *   grows one more entry rather than pairing the control with a checkbox.
 *
 * **Tracking is inherited per KNOB, not as one whole-group toggle** — a deliberate choice, not the
 * only one available: `TrackingKnobPatch` (the domain patch this maps onto) is itself nullable
 * field-by-field, so an operator who wants to override only the tracking *mode* can do exactly that
 * and leave capability level/cadence inherited, rather than being forced to either restate every
 * other knob's current effective value (fragile — a later change to a lower tier's own defaults
 * would silently stop flowing through) or lose a partial override entirely. `KnobSources.tracking`
 * itself is still one provenance value for the group as a whole (Java's own doc comment) — a coarser
 * READ-side fact that does not constrain how granular this editor's WRITE side may be.
 *
 * `labelFilterText`/`labelDenyFilterText` hold the allow/deny lists as one comma-or-newline-separated
 * string for a plain `<textarea>` — {@link parseLabelList}/{@link formatLabelList} are the only two
 * places that cross between this and `CvProfile#labelFilter`'s `readonly string[]`.
 *
 * `existingEventRule` is the loaded profile's own `eventRule`, shown read-only in the editor (§5.1:
 * start-time only, never part of `CvProfileRequest` — there is nothing to submit here even if a
 * control existed) — `null` when creating a brand-new profile or when a loaded profile leaves the
 * whole group unset (inherited), since this UI has no control for it either way (honesty rule §3.5
 * "no control without a backend endpoint").
 *
 * `intent` (added wave W3.6, docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, corrected wave W7) is
 * the operator's "what am I looking for" pick — `''` means no intent chosen, {@link
 * draftToRequest}'s exact "omit `intent` entirely" sentinel. Persisted with the profile and resolved
 * at FOLD time now (wave W7.1's `CvProfileResolver`), not at save time — see {@link draftToRequest}'s
 * own doc comment. Never inferred from a loaded profile's own `intent` in {@link draftFromProfile}/
 * {@link forkDraftFromProfile}: a freshly-opened editor always starts with no intent selected, even
 * for a profile that already carries a persisted one, matching this app's existing "the intent picker
 * is a one-shot action, not a persistent radio state" behavior (unchanged by this wave).
 */
export interface ProfileDraft {
  readonly sourceId: string | null;
  readonly name: string;
  readonly description: string;
  readonly model: string;
  readonly confidenceThreshold: number | undefined;
  readonly inferenceFps: number | undefined;
  readonly labelFilterText: string;
  readonly labelFilterInherit: boolean;
  readonly labelDenyFilterText: string;
  readonly labelDenyFilterInherit: boolean;
  readonly detectionEnabled: boolean | undefined;
  readonly trackingMode: TrackingMode | undefined;
  readonly trackingEngineId: string | undefined;
  readonly trackingCapabilityLevel: number | undefined;
  readonly trackingVerifyEveryMillis: number | undefined;
  readonly trackingFollowFps: number | undefined;
  readonly existingEventRule: CvProfileEventRule | null;
  readonly intent: CvProfileIntent | '';
}

/** A fresh draft for "New profile" — every knob starts unset/inherited (decision E22's own default:
 *  a profile that overrides nothing is a safe no-op, falling all the way through to the platform's
 *  own default `PipelineConfig`), not pre-filled with a plausible-looking concrete value. Corrected
 *  from the pre-W7 shape, which pre-seeded a concrete `defaultModel`/`confidenceThreshold: 0.35`/etc.
 *  — those were never a documented requirement, only a leftover of the old wholesale-profile model
 *  where every knob had to be concrete for the record to be valid at all. */
export function emptyProfileDraft(): ProfileDraft {
  return {
    sourceId: null,
    name: '',
    description: '',
    model: '',
    confidenceThreshold: undefined,
    inferenceFps: undefined,
    labelFilterText: '',
    labelFilterInherit: true,
    labelDenyFilterText: '',
    labelDenyFilterInherit: true,
    detectionEnabled: undefined,
    trackingMode: undefined,
    trackingEngineId: undefined,
    trackingCapabilityLevel: undefined,
    trackingVerifyEveryMillis: undefined,
    trackingFollowFps: undefined,
    existingEventRule: null,
    intent: '',
  };
}

/** Populates a draft from `profile` for in-place editing — `sourceId` set, so the facade PUTs. Every
 *  field round-trips `profile`'s own per-knob inherit state exactly: a knob `profile` itself leaves
 *  unset shows as "Inherit" in the editor, never a fabricated concrete value. */
export function draftFromProfile(profile: CvProfile): ProfileDraft {
  const tracking = profile.tracking;
  return {
    sourceId: profile.id,
    name: profile.name,
    description: profile.description,
    model: profile.model ?? '',
    confidenceThreshold: profile.confidenceThreshold,
    inferenceFps: profile.inferenceFps,
    labelFilterText: profile.labelFilter ? formatLabelList(profile.labelFilter) : '',
    labelFilterInherit: profile.labelFilter === undefined,
    labelDenyFilterText: profile.labelDenyFilter ? formatLabelList(profile.labelDenyFilter) : '',
    labelDenyFilterInherit: profile.labelDenyFilter === undefined,
    detectionEnabled: profile.detectionEnabled,
    trackingMode: tracking?.mode,
    trackingEngineId: tracking?.engineId,
    trackingCapabilityLevel: tracking?.capabilityLevel,
    trackingVerifyEveryMillis: tracking?.verifyEveryMillis,
    trackingFollowFps: tracking?.followFps,
    existingEventRule: profile.eventRule ?? null,
    // Never inferred from `profile.intent` — see this interface's own doc comment on `intent`.
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
 * `CvProfileRequest#model` blank means "leave this knob unset" (decision E22) — a blank `model`
 * paired with a chosen `intent` is exactly what asks `CvProfileResolver` to seed `model` from that
 * intent, now at FOLD time rather than at save time (**corrected wave W7** — the Java-side request no
 * longer resolves this itself; see `CvProfileRequest`'s own doc comment in `models.ts`). Picking an
 * intent must still be able to blank the model select's own value for that to happen. The **"no
 * intent" → "an intent" transition** blanks `draft.model` (deferring it to the platform/intent,
 * matching what an operator who has never touched Model would expect from picking an intent); an
 * **intent → a *different* intent** transition leaves `draft.model` exactly as it is, so an
 * operator's own explicit model choice — made any time after the first intent pick — stays sticky
 * even if the intent selection changes again, matching the plan's own acceptance wording. This is the
 * one and only place `intent` and `model` interact; every other transition (picking a concrete model,
 * in either intent state) goes through {@link VisionProfilesFacade.patchDraft} untouched, per that
 * facade's own single-field-at-a-time contract.
 */
export function applyIntentToDraft(draft: ProfileDraft, intent: CvProfileIntent | ''): ProfileDraft {
  const model = draft.intent === '' && intent !== '' ? '' : draft.model;
  return { ...draft, intent, model };
}

// --- Tri-state `<select>` encodings (wave W7, decision E22) --------------------------------------
// A plain HTML checkbox/blank-text cannot represent "inherit" for a knob that already has its own
// real, explicit "off"/"default" value distinct from unset — see `ProfileDraft`'s own doc comment for
// the full reasoning. Each pair below is one knob's own sentinel(s) plus the two pure conversions
// the template uses at its `[ngModel]`/`(ngModelChange)` boundary; `ProfileDraft` itself always holds
// the plain, already-inherit-capable domain type (`TrackingMode | undefined`, `string | undefined`,
// `boolean | undefined`), never one of these wire-adjacent sentinel strings.

/** `<select name="trackingMode">`'s own inherit sentinel — a real `TrackingMode` never equals this
 *  string, so it round-trips unambiguously alongside the three real modes. */
export const TRACKING_MODE_SELECT_INHERIT = 'INHERIT';

/** `draft.trackingMode` → the tracking-mode `<select>`'s own string value. */
export function trackingModeSelectValue(mode: TrackingMode | undefined): string {
  return mode ?? TRACKING_MODE_SELECT_INHERIT;
}

/** The inverse of {@link trackingModeSelectValue}. */
export function parseTrackingModeSelectValue(value: string): TrackingMode | undefined {
  return value === TRACKING_MODE_SELECT_INHERIT ? undefined : (value as TrackingMode);
}

/** `<select name="trackingEngine">`'s own two sentinels: {@link ENGINE_SELECT_INHERIT} for "leave
 *  this knob unset" and {@link ENGINE_SELECT_DEPLOYMENT_DEFAULT} for the real, explicit `''` engine
 *  id ("use the deployment default engine"). Every other option value is a real tracker id from
 *  `GET /api/cv/trackers`, which can never collide with either sentinel string. */
export const ENGINE_SELECT_INHERIT = '__inherit__';
export const ENGINE_SELECT_DEPLOYMENT_DEFAULT = '__deployment_default__';

/** `draft.trackingEngineId` → the engine `<select>`'s own string value. */
export function engineSelectValue(engineId: string | undefined): string {
  if (engineId === undefined) {
    return ENGINE_SELECT_INHERIT;
  }
  return engineId === '' ? ENGINE_SELECT_DEPLOYMENT_DEFAULT : engineId;
}

/** The inverse of {@link engineSelectValue}. */
export function parseEngineSelectValue(value: string): string | undefined {
  if (value === ENGINE_SELECT_INHERIT) {
    return undefined;
  }
  return value === ENGINE_SELECT_DEPLOYMENT_DEFAULT ? '' : value;
}

/** `<select name="detectionEnabled">`'s own three-way values — a plain checkbox has only two states
 *  and cannot represent "inherit" as a third (CLAUDE.md rule 10: never overload one control's
 *  unchecked state to mean two different things). */
export const DETECTION_SELECT_INHERIT = 'INHERIT';
export const DETECTION_SELECT_ON = 'ON';
export const DETECTION_SELECT_OFF = 'OFF';

/** `draft.detectionEnabled` → the detection `<select>`'s own string value. */
export function detectionSelectValue(value: boolean | undefined): string {
  if (value === undefined) {
    return DETECTION_SELECT_INHERIT;
  }
  return value ? DETECTION_SELECT_ON : DETECTION_SELECT_OFF;
}

/** The inverse of {@link detectionSelectValue}. */
export function parseDetectionSelectValue(value: string): boolean | undefined {
  if (value === DETECTION_SELECT_ON) {
    return true;
  }
  return value === DETECTION_SELECT_OFF ? false : undefined;
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
 * named/explicit here since a confidence/fps range isn't expressible with a plain HTML `required`).
 *
 * **Wave W7 — validates only SET knobs** (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7/§8,
 * decision E22): an unset (inherited) knob has nothing of this draft's own to be wrong — its real
 * value comes from a lower tier the fold, not this form, resolves — so every range check below is
 * gated on the knob actually being present. **`model` is no longer required at all**, with or without
 * an intent chosen: a profile that leaves every knob unset is a valid, if inert, patch (decision
 * E22's own default — see {@link emptyProfileDraft}'s doc comment). Corrected from the pre-W7 rule,
 * which required a concrete `model` unless an intent was picked — a leftover of the old
 * wholesale-profile model where every knob had to be concrete for the record to be valid at all.
 */
export function validateDraft(draft: ProfileDraft): readonly string[] {
  const errors: string[] = [];
  if (draft.name.trim().length === 0) {
    errors.push('Name is required.');
  }
  // Mirrors `CvProfileSpec`'s own `IllegalArgumentException` for `Intent.CUSTOM` with a null/empty
  // `labelFilter` — caught here so the operator sees it before submitting, not as a raw 400 from the
  // server. An inherited (unset) label filter fails this exact same way a request-sent empty list
  // would: `CvProfileSpec` throws on CUSTOM whenever `labelFilter` is null OR empty, and "inherit"
  // sends null.
  if (draft.intent === 'CUSTOM' && (draft.labelFilterInherit || parseLabelList(draft.labelFilterText).length === 0)) {
    errors.push('Custom intent needs at least one class in the label filter.');
  }
  if (draft.confidenceThreshold !== undefined && !(draft.confidenceThreshold >= 0 && draft.confidenceThreshold <= 1)) {
    errors.push('Confidence threshold must be between 0 and 1.');
  }
  if (draft.inferenceFps !== undefined && !(draft.inferenceFps >= 1)) {
    errors.push('Inference rate must be at least 1 fps.');
  }
  if (draft.trackingMode !== undefined && draft.trackingMode !== 'OFF') {
    if (draft.trackingCapabilityLevel !== undefined && !(draft.trackingCapabilityLevel >= 0)) {
      errors.push('Tracking capability level cannot be negative.');
    }
    if (draft.trackingMode === 'FOLLOW') {
      if (draft.trackingVerifyEveryMillis !== undefined && !(draft.trackingVerifyEveryMillis >= 0)) {
        errors.push('Verify interval cannot be negative.');
      }
      if (draft.trackingFollowFps !== undefined && !(draft.trackingFollowFps >= 0)) {
        errors.push('Follow rate cannot be negative.');
      }
    }
  }
  return errors;
}

/** Builds the exact `CvProfileRequest` body for `createCvProfile`/`updateCvProfile` — the only place
 * a `ProfileDraft` turns back into the wire shape. `eventRule` is never included (§5.1: start-time
 * only, not part of this request type at all).
 *
 * **Wave W7 — omits every unset knob** (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7/§8, decision
 * E22), mirroring `CvProfileRequest`'s own now-optional fields: an unset knob is sent as `undefined`
 * (an absent JSON key, never a fabricated concrete value) rather than the draft's own placeholder.
 * `model` blank means unset — see `ProfileDraft`'s own doc comment for why that encoding is safe here
 * specifically. `labelFilter`/`labelDenyFilter` send `undefined` whenever their own `*Inherit` flag is
 * set, **regardless of the textarea's own text** — otherwise {@link parseLabelList} runs as before,
 * so an explicitly-emptied textarea still round-trips as the real "keep/deny nothing" `[]` value it
 * always has. `tracking` is sent as `undefined` (the whole group left unset) only when every one of
 * its five knobs is unset; otherwise each unset sub-knob is simply absent from the object, which is
 * this wave's own genuinely per-knob tracking inherit (see `ProfileDraft`'s doc comment for why
 * tracking is not a single whole-group toggle).
 *
 * `intent` is omitted entirely (not sent as `null`) when `draft.intent === ''` — "no intent chosen"
 * — exactly {@link CvProfileRequest#intent}'s own "skip intent resolution entirely" sentinel.
 */
export function draftToRequest(draft: ProfileDraft): CvProfileRequest {
  return {
    name: draft.name.trim(),
    description: draft.description.trim(),
    model: draft.model.trim() === '' ? undefined : draft.model.trim(),
    confidenceThreshold: draft.confidenceThreshold,
    inferenceFps: draft.inferenceFps,
    labelFilter: draft.labelFilterInherit ? undefined : parseLabelList(draft.labelFilterText),
    labelDenyFilter: draft.labelDenyFilterInherit ? undefined : parseLabelList(draft.labelDenyFilterText),
    detectionEnabled: draft.detectionEnabled,
    tracking: draftTrackingPatch(draft),
    intent: draft.intent === '' ? undefined : draft.intent,
  };
}

/** {@link draftToRequest}'s own `tracking` sub-builder — `undefined` (the whole group unset) only
 *  when every one of the five tracking knobs is itself unset; otherwise an object carrying exactly
 *  the knobs `draft` sets, leaving the rest absent (per-knob inherit within the group). */
function draftTrackingPatch(draft: ProfileDraft): CvProfileTracking | undefined {
  if (
    draft.trackingMode === undefined &&
    draft.trackingEngineId === undefined &&
    draft.trackingCapabilityLevel === undefined &&
    draft.trackingVerifyEveryMillis === undefined &&
    draft.trackingFollowFps === undefined
  ) {
    return undefined;
  }
  return {
    mode: draft.trackingMode,
    engineId: draft.trackingEngineId,
    capabilityLevel: draft.trackingCapabilityLevel,
    verifyEveryMillis: draft.trackingVerifyEveryMillis,
    followFps: draft.trackingFollowFps,
  };
}

/**
 * The save-toast text for `VisionProfilesFacade.saveDraft()` — names exactly which knob(s) `saved`'s
 * own `sources` reports as left to `intent`, e.g. `'"Vehicles patrol" saved — model left to your
 * Vehicles intent.'`. Never claims a knob was intent-seeded when its own `sources` entry is absent
 * (§3.5 honesty rule) — `intent === ''` alone (impossible to pair with a non-`undefined` `sources`
 * entry in practice, since the server can only have flagged a field against an intent that was
 * actually sent, but checked explicitly here too so this function never *names* an intent it cannot
 * point to) falls back to the plain saved/created message, same as reporting nothing deferred.
 *
 * **Wording corrected wave W7** (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7/§8, decision E22):
 * this used to say "resolved from" — accurate under the pre-W7 contract, where the save request
 * itself computed and stored a concrete value. Now `intent` is persisted unresolved and
 * `CvProfileResolver` only seeds the knob later, at FOLD time (wave W7.1) — `saved.sources.model
 * === 'INTENT'` means "this knob is left unset, and will be resolved from your intent whenever this
 * profile's tier is read/applied," not "already resolved to some concrete value here." "Resolved
 * from" would misstate that as a past, completed act.
 */
export function saveOutcomeMessage(saved: CvProfile, intent: CvProfileIntent | '', isUpdate: boolean): string {
  const verb = isUpdate ? 'saved' : 'created';
  const plain = `"${saved.name}" ${verb}.`;
  if (intent === '') {
    return plain;
  }
  const deferredKnobs: string[] = [];
  if (saved.sources.model === 'INTENT') {
    deferredKnobs.push('model');
  }
  if (saved.sources.labelFilter === 'INTENT') {
    deferredKnobs.push('label filter');
  }
  if (deferredKnobs.length === 0) {
    return plain;
  }
  return `"${saved.name}" ${verb} — ${deferredKnobs.join(' and ')} left to your ${describeIntent(intent)} intent.`;
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
