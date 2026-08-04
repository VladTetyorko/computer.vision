import { Injectable, computed, inject, signal } from '@angular/core';
import { FleetStore } from '../../core/fleet/fleet-store';
import { BUILT_IN_PROFILES, SettingsStore } from '../../core/settings/settings-store';
import { computeDeltaFromDefaults } from './detection-settings-logic';

/**
 * `DetectionSettingsPage`'s facade (docs/UI-ARCHITECTURE-PLAN.md) — the **fleet-wide** half of what
 * used to be one combined `SettingsFacade`/`SettingsPage` (docs/NAV-IA-REDESIGN-PLAN.md §2.5,
 * docs/design/11-settings.md, Wave 4's F7 split): Detection profile, model, and the raw
 * confidence/fps knobs, all applied to *every* stream anyone starts, not just this user's own — see
 * `AccountSettingsFacade`'s own doc comment for the per-account half this used to share a page with.
 *
 * **Every edit here applies immediately — there is no separate "commit" step** (task 3's own escape
 * hatch: "if the underlying store genuinely applies instantly… make that explicit… do not build a
 * fake Save button"). `SettingsStore.effective()` (draft-over-profile) is what `WallFacade`/
 * `FleetStore.start()` read live, at the moment a stream actually starts — there is no pending value
 * this facade could lose by not being "saved" first. `docs/CV-CONTROL-PLAN.md §4` traces the same
 * path end to end: `settings.effective()` posts straight through to `StartStreamRequest`, no
 * destructuring in between. The one genuine "save" action below, `saveAs`, is a *different* verb: it
 * persists the current (already-live) draft under a name so it can be picked again later — not a
 * confirmation the value is in effect, since it already is. The dirty-state action bar
 * (`detection-settings.html`) states this plainly rather than implying a pending/unsaved state that
 * doesn't exist.
 */
@Injectable()
export class DetectionSettingsFacade {
  readonly settings = inject(SettingsStore);
  private readonly fleet = inject(FleetStore);

  readonly newProfileName = signal('');

  readonly modelOptions = computed(() => this.fleet.models());

  /** Backend defaults, for the "what does this preset change" comparison. */
  private readonly defaults = BUILT_IN_PROFILES[0];

  readonly deltaFromDefaults = computed(() =>
    computeDeltaFromDefaults(this.settings.effective(), this.defaults, (id) => this.modelLabel(id)),
  );

  /** Also used by the template to show a profile's model in its compact values line — degrades to
   * the bare id when the roster hasn't loaded yet (or no longer lists it), never a blank/fabricated
   * label (docs/CV-CONTROL-PLAN.md Wave E — the roster replaced the old closed `DetectionModelId` set). */
  modelLabel(id: string): string {
    return this.modelOptions().find((option) => option.id === id)?.displayName ?? id;
  }

  readonly customProfiles = computed(() => this.settings.customProfiles());

  onProfileChange(id: string): void {
    this.settings.selectProfile(id);
  }

  onConfidence(value: string): void {
    this.settings.adjust({ confidenceThreshold: Number(value) });
  }

  onFps(value: string): void {
    this.settings.adjust({ inferenceFps: Number(value) });
  }

  onModel(model: string): void {
    this.settings.adjust({ model });
  }

  saveAs(): void {
    this.settings.saveDraftAs(this.newProfileName());
    this.newProfileName.set('');
  }
}
