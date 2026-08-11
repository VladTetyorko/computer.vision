import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Icon } from '../../shared/ui/icon';
import { Notice } from '../../shared/ui/notice';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { DetectionSettingsFacade } from './detection-settings-facade';

/**
 * `/settings/detection` — **Detection defaults** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.5,
 * docs/extracts/design/11-settings.md, Wave 4's F7 split). Reached from exactly one door — the sidebar's
 * Operate group (`features/hubs/nav-entries.ts`'s "Detection defaults" entry, already repointed here
 * ahead of this task) — the avatar menu never linked here and still doesn't, so this URL has exactly
 * one name too, same as `/settings`'s own half of the split.
 *
 * **Blast-radius line is a `vision-notice`, not the page bar's own `?` hint** (task 2) —
 * deliberately: the hint disclosure (used on `/settings`, where the stakes are genuinely low) costs a
 * click to read, and this is the page the task calls out by name as "the dangerous one" — a changed
 * value here reaches every stream anyone starts, not just this account. The page bar's `hint` is kept
 * for the pre-existing, genuinely-instructional "presets first, knobs behind them" line instead, which
 * isn't safety-critical.
 *
 * **Hierarchy** (task 4): Model and the raw Advanced knobs render nested under the Profile picker
 * (`.nested` in `detection-settings.css`) rather than as three peer cards — the copy above them
 * already says changing either one "forks the preset into a custom profile"; nesting is what makes
 * that containment visible instead of merely stated.
 *
 * **The dirty-state action bar** (task 3) mirrors `SettingsStore.isCustom()` directly — see
 * `DetectionSettingsFacade`'s own doc comment for why its copy says "applies immediately" rather than
 * implying an unsaved/pending value.
 *
 * Dumb by convention (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — every fetch/mutation/derivation lives in
 * `DetectionSettingsFacade`, which this component injects exclusively.
 */
@Component({
  selector: 'vision-detection-settings',
  imports: [PageBar, Notice, Icon],
  templateUrl: './detection-settings.html',
  styleUrl: './detection-settings.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [DetectionSettingsFacade],
})
export class DetectionSettingsPage {
  protected readonly facade = inject(DetectionSettingsFacade);
}
