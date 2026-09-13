import type { BindingScope, CvProfileSources, EffectiveCvProfile } from '../../core/api/models';

/**
 * `CvSetupModal`'s own pure logic (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.7, wave W3.4) —
 * split out into its own file, rather than added to `cv-control-panel-logic.ts`, because that file
 * is shared with `cv-control-panel.ts` (a sibling component out of this wave's file scope) and the
 * resolved-source concept below is Tuning-modal-only.
 */

/** The Tuning knobs a resolved-source line can be shown for. `'confidenceThreshold'` is included
 *  for symmetry with the Classes/Model knobs even though `CvProfileSources` has no field for it —
 *  request-time intent resolution never seeds confidence (`IntentPolicy#detectFloor`/`#rateCeiling`
 *  are computed and unconsumed as of wave W2, docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.9's
 *  "As built in W2" table) — so {@link resolvedSourceLine} never reports `'INTENT'` for it and
 *  falls straight to the profile-tier fact, exactly as honest as omitting the check entirely. */
export type TuningKnob = 'model' | 'labelFilter' | 'confidenceThreshold';

/**
 * The small "resolved from…" sentence shown next to a Tuning knob, or `null` when there is nothing
 * honest to say. Precedence, never inventing a source the wire didn't report:
 *
 * 1. `sources` reports this exact `knob` as `'INTENT'` (request-time intent resolution, wave W3.0/
 *    W3.6 — present only on the response to the `PATCH`/profile save that just resolved it, absent
 *    on a plain reload until `intent` is persisted on `CvProfile`, §4.9's "As built in W2" table) →
 *    **"Resolved from your intent pick"**.
 *
 *    Deliberately does not name the specific intent (e.g. "People"): `CvProfileSources` only
 *    reports the enum tag `'INTENT'`, never *which* intent resolved it, and this component
 *    receives no chosen-intent value of its own (no binding site for one exists yet — wave W3.3's
 *    concern, landing concurrently, not this file's). Naming the intent would mean either adding a
 *    second input this wave has nothing to wire (dead until a sibling wave lands) or fabricating a
 *    label from data this component doesn't have — both worse than the honest generic phrasing.
 *
 * 2. Otherwise `profileSource` (`EffectiveCvProfile#source`, the binding-fold tier that actually
 *    resolved the running profile) is present → **"From your &lt;tier&gt; profile"**
 *    (asset/category/organization) or **"Platform default"** for `'PLATFORM'`.
 *
 * 3. Otherwise → `null` (render nothing).
 */
export function resolvedSourceLine(
  sources: CvProfileSources | undefined,
  profileSource: EffectiveCvProfile['source'] | undefined,
  knob: TuningKnob,
): string | null {
  const intentSource = knob === 'model' ? sources?.model : knob === 'labelFilter' ? sources?.labelFilter : undefined;
  if (intentSource === 'INTENT') {
    return 'Resolved from your intent pick';
  }
  if (profileSource) {
    return profileSource === 'PLATFORM' ? 'Platform default' : `From your ${profileTierWord(profileSource)} profile`;
  }
  return null;
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
