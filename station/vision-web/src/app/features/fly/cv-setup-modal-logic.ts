import type { BindingScope, CvProfileIntent, CvProfileSources, EffectiveCvProfile } from '../../core/api/models';

/**
 * `CvSetupModal`'s own pure logic (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, wave W3.4) —
 * split out into its own file, rather than added to `cv-control-panel-logic.ts`, because that file
 * is shared with `cv-control-panel.ts` (a sibling component out of this wave's file scope) and the
 * resolved-source concept below is Tuning-modal-only.
 */

/** The Tuning knobs a resolved-source line can be shown for. Every one of these is a real field on
 *  `EffectiveCvProfile#sources` (`CvKnobSources`, wave W7) — including `'confidenceThreshold'`,
 *  which could not report a genuine per-knob source before this wave (see this file's own history:
 *  pre-W7, `CvProfileSources` had no `confidenceThreshold` field at all, because request-time intent
 *  resolution never touched it). Wave W7.1's `CvProfileResolver` now seeds `confidenceThreshold`
 *  from an intent at FOLD time exactly like `model`/`labelFilter`, so this knob is no longer a
 *  documented exception — it is treated identically to the other two below. */
export type TuningKnob = 'model' | 'labelFilter' | 'confidenceThreshold';

/**
 * The small "resolved from…"/"from your … profile" sentence shown next to a Tuning knob, or `null`
 * when there is nothing honest to say. Never invents a source the wire didn't report.
 *
 * **Wave W7 — reads real per-knob provenance from the effective GET, not just from a just-completed
 * save** (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7/§8, decision E22): `effective?.sources`
 * (`CvKnobSources`) already names the exact tier — `ASSET`/`CATEGORY`/`ORGANIZATION`/`PLATFORM`/
 * `INTENT` — that supplied `knob`'s own resolved value, for *every* read of `GET
 * /api/streams/{id}/effective-profile`, not only the response to a PATCH that just applied an
 * intent. This replaces the pre-W7 two-tier approximation (a coarse whole-profile
 * `EffectiveCvProfile#source` used as a fallback for every knob alike, unable to distinguish "this
 * knob came from the asset tier" from "this knob came from the category tier the asset profile
 * itself inherited"). Precedence:
 *
 * 1. `lastConfigSources` (`PatchStreamConfigResponse#sources`, this session's own most recent
 *    hot-knob PATCH — a live fact about the *running stream*, which a profile-tier read cannot see)
 *    reports this exact `knob` as `'INTENT'` → an intent line (see below). This session-live fact
 *    still wins over the effective-profile read: the two are honest about different things (what
 *    this browser's last PATCH just did to the live pipeline vs. what the persisted profile fold
 *    currently resolves to) and a same-session PATCH is the more immediate of the two.
 * 2. Otherwise `effective.sources[knob]` (present on every effective-profile read, wave W7): `'INTENT'`
 *    → an intent line; `'PLATFORM'` → **"Platform default"**; `ASSET`/`CATEGORY`/`ORGANIZATION` →
 *    **"From your &lt;tier&gt; profile"**.
 * 3. Otherwise (neither read has landed) → `null` (render nothing).
 *
 * An intent line names the specific intent — **"Resolved from your Vehicles intent"** — whenever
 * `effective?.intent` is known (wave W7: `EffectiveCvProfile#intent` is the matched tier's own
 * persisted pick, absent only when that tier set none); the pre-W7 generic "Resolved from your
 * intent pick" is now only the honest fallback for the (should not normally happen once `intent` is
 * itself persisted, docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.9's "As built in W2" table) case
 * where a knob is flagged `'INTENT'` but no intent value came back with it.
 */
export function resolvedSourceLine(
  lastConfigSources: CvProfileSources | undefined,
  effective: EffectiveCvProfile | undefined,
  knob: TuningKnob,
): string | null {
  const liveIntentSource =
    knob === 'model' ? lastConfigSources?.model : knob === 'labelFilter' ? lastConfigSources?.labelFilter : undefined;
  if (liveIntentSource === 'INTENT') {
    return intentSourceLine(effective?.intent);
  }
  const tier = effective?.sources[knob];
  if (tier === undefined) {
    return null;
  }
  if (tier === 'INTENT') {
    return intentSourceLine(effective?.intent);
  }
  if (tier === 'PLATFORM') {
    return 'Platform default';
  }
  return `From your ${profileTierWord(tier)} profile`;
}

function intentSourceLine(intent: CvProfileIntent | undefined): string {
  return intent === undefined ? 'Resolved from your intent pick' : `Resolved from your ${describeIntentWord(intent)} intent`;
}

/** `CvProfileIntent` → the word this modal's own source line names it with — duplicates
 *  `vision-profiles-logic.ts#describeIntent`'s identical 4-case mapping rather than importing across
 *  feature folders for one small, stable switch (this codebase's feature-folder convention keeps
 *  cross-feature imports to `core/`/`shared/`, not feature-to-feature; see `MODULE.md`). */
function describeIntentWord(intent: CvProfileIntent): string {
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

function profileTierWord(tier: BindingScope): string {
  switch (tier) {
    case 'ASSET':
      return 'asset';
    case 'CATEGORY':
      return 'category';
    case 'ORGANIZATION':
      return 'organization';
  }
}
