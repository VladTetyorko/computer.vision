import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { SettingsFacade } from './settings-facade';

/**
 * docs/CV-CONTROL-PLAN.md §4 (extending docs/CV-MODELS-PLAN.md item 4) — the model picker persists
 * a choice (`SettingsStore`, same profile/draft/custom semantics as confidence/fps) that reaches a
 * running stream end to end: `StartStreamRequest` (`core/api/models.ts`) declares the `model` field
 * mirroring vision-api's own DTO, and `fleet.start()` posts `settings.effective()` straight through
 * with no destructuring in between. The roster itself is now data-driven (`GET /api/cv/models`,
 * cached by `FleetStore.models`) rather than the old hardcoded `DETECTION_MODEL_OPTIONS` array.
 *
 * Dumb by convention (docs/UI-ARCHITECTURE-PLAN.md) — every fetch/mutation/derivation lives in
 * `SettingsFacade`, which this component injects exclusively.
 */
@Component({
  selector: 'vision-settings',
  templateUrl: './settings.html',
  styleUrl: './settings.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [SettingsFacade],
})
export class SettingsPage {
  protected readonly facade = inject(SettingsFacade);
}
