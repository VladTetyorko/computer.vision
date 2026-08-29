import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { AccountSettingsFacade } from './account-settings-facade';

/**
 * `/settings` — **Account settings** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.5, docs/extracts/design/11-settings.md,
 * Wave 4's F7 split — "one destination, several names and doors"). Reached from the avatar menu's
 * "Account settings" (`shared/ui/identity-chip.html`, untouched by this task) and, since
 * docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 wave W1, the sidebar's own SYSTEM footer group
 * (`nav-entries.ts`'s "Settings" entry).
 *
 * Carries **per-account** preferences (Appearance, Interface, Notifications), a read-only System
 * status card, and — new this wave — a small "Settings" link list out to the two routes that left
 * the sidebar rail entirely (Detection defaults, Controller) plus `/org` for a manager, so neither
 * page becomes unreachable once its own nav entry is gone. See `AccountSettingsFacade`'s own doc
 * comment for why the fleet-wide Detection profile/model itself stayed on `DetectionSettingsPage`
 * rather than folding into this page, and why System stays here rather than there.
 *
 * Dumb by convention (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — every fetch/mutation/derivation lives in
 * `AccountSettingsFacade`, which this component injects exclusively.
 */
@Component({
  selector: 'vision-account-settings',
  imports: [PageBar, RouterLink],
  templateUrl: './account-settings.html',
  styleUrl: './account-settings.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [AccountSettingsFacade],
})
export class AccountSettingsPage {
  protected readonly facade = inject(AccountSettingsFacade);
}
