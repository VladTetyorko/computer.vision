import type { PipelineProfile, PipelineSettings } from '../../core/settings/settings-store';

/**
 * Pure, Angular-free diff between the currently effective pipeline values and the backend's own
 * shipped defaults (`BUILT_IN_PROFILES[0]`, "Balanced") — pulled out of the old combined
 * `SettingsFacade` (docs/NAV-IA-REDESIGN-PLAN.md §2.5, docs/design/11-settings.md, Wave 4's F7
 * split) so this comparison is unit-testable without Angular DI, matching this codebase's own
 * "logic, not components" precedent (`features/fly/cv-control-panel-logic.ts`,
 * `features/onboarding/drone-config-logic.ts`).
 *
 * This is a different question from the dirty-state action bar's own "changed from {{ active
 * profile }}" (`DetectionSettingsFacade` reads `SettingsStore.isCustom()`/`activeProfile()` for
 * that one directly, no logic worth extracting): this function always compares against the
 * shipped Balanced defaults regardless of which preset is currently active, so it still reports a
 * diff even when the active *profile* is Low latency/High quality and its own draft is untouched.
 *
 * `modelLabel` is injected rather than imported — the roster it resolves display names against
 * (`FleetStore.models()`) is itself Angular-only state, so this function stays framework-free by
 * taking the lookup as a parameter instead of a store.
 */
export function computeDeltaFromDefaults(
  current: PipelineSettings,
  defaults: PipelineProfile,
  modelLabel: (id: string) => string,
): string[] {
  const changes: string[] = [];
  if (current.confidenceThreshold !== defaults.confidenceThreshold) {
    changes.push(`confidence ${defaults.confidenceThreshold} → ${current.confidenceThreshold}`);
  }
  if (current.inferenceFps !== defaults.inferenceFps) {
    changes.push(`inference ${defaults.inferenceFps} → ${current.inferenceFps} fps`);
  }
  if (current.model !== defaults.model) {
    changes.push(`model ${modelLabel(defaults.model)} → ${modelLabel(current.model)}`);
  }
  return changes;
}
