import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
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
 *
 * **Page bar + centered form (docs/NAV-IA-REDESIGN-PLAN.md §2.2/§2.3, docs/design/11-settings.md,
 * wave 2).** `page-head`'s own description ("Presets first, knobs behind them, raw values behind
 * those…") is genuinely instructional, not a restatement of the title, so it survives as the bar's
 * `hint` rather than being deleted outright. `.page--form` centers the whole page at 880px — this is
 * a reading-and-deciding page, not a scanning one, per the design doc's own framing. **The Interface/
 * Notifications vs. Detection-profile split into `/settings`/`/settings/detection` is Wave 4** (that
 * design doc's own table); this wave only touches the header and the surrounding column width, the
 * single combined page is otherwise unchanged.
 */
@Component({
  selector: 'vision-settings',
  imports: [PageBar],
  templateUrl: './settings.html',
  styleUrl: './settings.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [SettingsFacade],
})
export class SettingsPage {
  protected readonly facade = inject(SettingsFacade);
}
