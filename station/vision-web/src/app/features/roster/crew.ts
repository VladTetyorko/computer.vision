import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { OrgSettingsPage } from '../org-settings/org-settings';
import { RosterPage } from './roster';

export type CrewTab = 'roster' | 'org';

/**
 * `/manage/roster` — **Crew** (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1/§4 wave W7, renaming "Pilots /
 * roster"). Two tabs behind `?tab=roster|org` (default `roster`), each an **existing page component
 * reused wholesale, not copied**: `<vision-roster>` (today's `/manage/roster` content verbatim) and
 * `<vision-org-settings>` (today's `/org` content verbatim) — every fetch, mutation, and pure
 * derivation stays exactly where it already lived (`RosterFacade`/`OrgSettingsFacade`); this
 * component only owns which one is mounted and the one shared page-bar above both. Both child
 * components take `[embedded]="true"`, which swaps their own sticky `<vision-page-bar>` for a plain
 * inline toolbar row — see `roster.ts`/`org-settings.ts`'s own `embedded` doc comments for why a
 * second stacked sticky bar per tab would be redundant chrome.
 *
 * **Route guard.** The single `manage/roster` route (`roster.routes.ts`) carries `orgGuard` once —
 * both tabs are ADMIN/MANAGER-only, same as today's separate `/manage/roster` and `/org` routes were.
 * `/org` itself is now a guard-only redirect to `?tab=org` (`org-settings.routes.ts`/`org-to-crew-guard.ts`).
 *
 * **`?tab=` follows `RosterFacade`'s own `?by=`/`?sel=` idiom** (`toSignal` over `queryParamMap`,
 * `router.navigate([], { relativeTo, queryParams, queryParamsHandling: 'merge', replaceUrl: true })`)
 * — an unrecognized/absent value degrades to `roster`, never a blank tab.
 */
@Component({
  selector: 'vision-crew',
  imports: [PageBar, RosterPage, OrgSettingsPage],
  templateUrl: './crew.html',
  styleUrl: './crew.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CrewPage {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  private readonly queryParamMap = toSignal(this.route.queryParamMap, {
    initialValue: this.route.snapshot.queryParamMap,
  });

  protected readonly tab = computed<CrewTab>(() => (this.queryParamMap().get('tab') === 'org' ? 'org' : 'roster'));

  protected setTab(tab: CrewTab): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }
}
