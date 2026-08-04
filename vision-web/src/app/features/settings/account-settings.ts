import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { AccountSettingsFacade } from './account-settings-facade';

/**
 * `/settings` — **Account settings** (docs/NAV-IA-REDESIGN-PLAN.md §2.5, docs/design/11-settings.md,
 * Wave 4's F7 split — "one destination, several names and doors"). Reached from exactly one door now:
 * the avatar menu's "Account settings" (`shared/ui/identity-chip.html`, untouched by this task). The
 * sidebar's Operate group no longer points here at all — `features/hubs/nav-entries.ts`'s "Detection
 * defaults" entry (already repointed ahead of this task) goes straight to `/settings/detection` — so
 * this URL now has exactly one name anywhere in the app's own UI.
 *
 * Carries only **per-account** preferences (Interface, Notifications) plus a read-only System status
 * card — see `AccountSettingsFacade`'s own doc comment for why the fleet-wide Detection profile/model
 * moved out to `DetectionSettingsPage`, and why System stays here rather than there.
 *
 * Dumb by convention (docs/UI-ARCHITECTURE-PLAN.md) — every fetch/mutation/derivation lives in
 * `AccountSettingsFacade`, which this component injects exclusively.
 */
@Component({
  selector: 'vision-account-settings',
  imports: [PageBar],
  templateUrl: './account-settings.html',
  styleUrl: './account-settings.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [AccountSettingsFacade],
})
export class AccountSettingsPage {
  protected readonly facade = inject(AccountSettingsFacade);
}
