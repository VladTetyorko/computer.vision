import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { AuthStore } from '../../core/auth/auth-store';
import { canManageOrg } from '../../core/org/org-logic';
import { Icon } from '../../shared/ui/icon';
import { NavTile } from '../../shared/ui/nav-tile';
import { SectionHeader } from '../../shared/ui/section-header';
import { TileGrid } from '../../shared/ui/tile-grid';
import { navModeById, type NavEntry } from './nav-entries';
import { tileAccent } from './tile-accent';

/**
 * `/manage` — the Manage hub launcher. Unlike `operate-hub.ts`/`monitor-hub.ts` (still a plain,
 * ungrouped `NAV_MODES` filter), this page also **groups and role-scopes** its entries
 * (docs/UX-SIMPLIFY-REVIEW.md F3) — the Manage hub used to be a flat wall of 10 tiles regardless of
 * who was looking; now everyday tiles (Assets/Add source/Pilots-roster) render first, ungrouped, and
 * the rest (Configuration/Diagnostics/Advanced — `NavEntry#group`) render as their own labelled
 * `vision-section-header` + `vision-tile-grid`, entirely hidden for anyone who isn't ADMIN/MANAGER.
 *
 * **Role gate**: `canManageOrg(topRole)` (`core/org/org-logic.ts`) — the exact same ADMIN/MANAGER
 * check `shared/ui/identity-chip.ts`'s Organization link and `core/org/org-guard.ts`'s route guard
 * already apply, reused here rather than a new role system. A plain PILOT's `/manage` renders just
 * two tiles (Assets, Add source); an ADMIN/MANAGER sees the full grouped set. **Dev-parity**: when
 * `vision.auth.enabled=false` the dev principal resolves to ADMIN (the same mechanism `org-guard.ts`'s
 * own doc comment describes), so `canManageOrg` is `true` and this hub renders exactly as before this
 * task — nothing here reads `authEnabled` directly.
 *
 * **Not a routed *feature* page** (no `<feature>-facade.ts`, same carve-out `warehouse.ts` used to
 * have and `operate-hub.ts`/`monitor-hub.ts` still have — none of the three hub pages are in
 * `architecture.spec.ts#ROUTED_PAGES`): injecting `AuthStore` directly here mirrors
 * `shared/ui/identity-chip.ts`'s own precedent for a small, non-facaded presentational component
 * reading the session for a role check, not a violation of the facade rule (which is per *routed
 * feature*, not per component).
 *
 * **Known gap, not fixed here (out of this task's own file scope):** the app-shell top bar's own
 * Manage dropdown (`app.ts`/`app.html`) reads `NAV_MODES` unfiltered — it still lists every entry
 * regardless of role. Closing that gap needs touching `app.ts`/`app.html`, which this task's brief
 * intentionally excludes (nav de-duplication + hub restructuring only). A pilot who opens the
 * dropdown can still see (and click through to a redirect from) an admin-only destination; the
 * `/manage` **hub page** itself — this file — is what F3 actually asked to make "nearly empty".
 */
@Component({
  selector: 'vision-manage-hub',
  imports: [Icon, NavTile, TileGrid, SectionHeader],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="page">
      <div class="page-head">
        <div>
          <h1><vision-icon name="manage" [size]="24" />Manage</h1>
          <p>Manage inventory — assets, sources, and the people who fly them.</p>
        </div>
      </div>

      <vision-tile-grid>
        @for (entry of core(); track entry.name) {
          <vision-nav-tile
            [icon]="entry.icon"
            [name]="entry.name"
            [description]="entry.description"
            [to]="entry.to"
            [badge]="entry.badge"
            [accent]="accentFor(entry)"
          />
        }
      </vision-tile-grid>

      @if (configuration().length > 0) {
        <section class="manage-group">
          <vision-section-header title="Configuration" subtitle="Set up once, not every day." />
          <vision-tile-grid>
            @for (entry of configuration(); track entry.name) {
              <vision-nav-tile
                [icon]="entry.icon"
                [name]="entry.name"
                [description]="entry.description"
                [to]="entry.to"
                [badge]="entry.badge"
                [accent]="accentFor(entry)"
              />
            }
          </vision-tile-grid>
        </section>
      }

      @if (diagnostics().length > 0) {
        <section class="manage-group">
          <vision-section-header title="Diagnostics" subtitle="Troubleshooting, not day-to-day management." />
          <vision-tile-grid>
            @for (entry of diagnostics(); track entry.name) {
              <vision-nav-tile
                [icon]="entry.icon"
                [name]="entry.name"
                [description]="entry.description"
                [to]="entry.to"
                [badge]="entry.badge"
                [accent]="accentFor(entry)"
              />
            }
          </vision-tile-grid>
        </section>
      }

      @if (advanced().length > 0) {
        <section class="manage-group">
          <vision-section-header
            title="Advanced"
            subtitle="Raw device plumbing — most device actions already live inside each asset's own Hardware section."
          />
          <vision-tile-grid>
            @for (entry of advanced(); track entry.name) {
              <vision-nav-tile
                [icon]="entry.icon"
                [name]="entry.name"
                [description]="entry.description"
                [to]="entry.to"
                [badge]="entry.badge"
                [accent]="accentFor(entry)"
              />
            }
          </vision-tile-grid>
        </section>
      }
    </div>
  `,
  styles: `
    h1 {
      display: flex;
      align-items: center;
      gap: var(--space-8);
    }

    .manage-group {
      margin-top: var(--space-24);
    }
  `,
})
export class ManageHub {
  private readonly auth = inject(AuthStore);

  protected readonly mode = navModeById('manage');

  /** ADMIN/MANAGER only — see this class's own doc comment. */
  protected readonly canManage = computed(() => canManageOrg(this.auth.user()?.topRole));

  private readonly visible = computed(() => this.mode.entries.filter((entry) => !entry.managerOnly || this.canManage()));

  protected readonly core = computed(() => this.visible().filter((entry) => entry.group === undefined));
  protected readonly configuration = computed(() => this.visible().filter((entry) => entry.group === 'configuration'));
  protected readonly diagnostics = computed(() => this.visible().filter((entry) => entry.group === 'diagnostics'));
  protected readonly advanced = computed(() => this.visible().filter((entry) => entry.group === 'advanced'));

  /** Accent by the entry's own fixed position in `mode.entries` — stable regardless of which group renders it. */
  protected accentFor(entry: NavEntry): string {
    return tileAccent(this.mode.entries.indexOf(entry));
  }
}
