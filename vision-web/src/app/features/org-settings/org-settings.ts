import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { OrgSettingsFacade } from './org-settings-facade';

/**
 * The org-settings surface (`/org`, docs/U-SCOPE-PLAN.md, U-e slice 2) — a manager/admin's
 * users-and-groups management page. Route-guarded (`core/auth/auth-guard.ts`, session required) and
 * role-gated (`core/org/org-guard.ts` — only ADMIN/MANAGER reach it; a pilot is redirected); the
 * nav link into it (`shared/ui/identity-chip.ts`) is itself only shown when `canManageOrg`, so a
 * pilot never sees the door. In dev-parity mode (`vision.auth.enabled=false`) the dev admin is
 * ADMIN, so this is always reachable exactly as before — no visibility change.
 *
 * Two sections behind a segmented tab (this app's `.segmented` idiom, `styles.css`): **Users**
 * (list + invite form + enable/disable toggle) and **Groups** (hierarchy tree + create form). Dumb
 * by convention (docs/UI-ARCHITECTURE-PLAN.md) — every fetch/mutation and its one-toast handling
 * lives in `OrgStore`, every pure derivation (group tree, role options) in `core/org/org-logic.ts`,
 * and all of it is orchestrated by `OrgSettingsFacade`, which this component injects exclusively.
 * Reuses the existing management-page look wholesale (`.page`/`.card`/`.btn`/`.chip`/`.segmented`/
 * `.empty`), no new colors. Responsive: the page is a single scrolling column of cards; each list row
 * and each form wraps rather than overflowing on a narrow viewport (see `org-settings.css`).
 *
 * **Page bar (docs/NAV-IA-REDESIGN-PLAN.md §2.2, docs/design/12-org.md, wave 2).** The old
 * description was two sentences doing two different jobs: "Manage the people and groups in your
 * organization" only restated what the title "Organization" already says, so it's deleted outright;
 * "Grants are limited to your own scope" is a genuine, non-obvious fact about how this page behaves,
 * so it survives as the bar's `hint`. The `Users | Groups` segmented toggle moves into
 * `[pageBarFilters]` — it filters which list is showing, the same job every other page's filter slot
 * does — and the count chip now answers "how many" for whichever section is active
 * (`OrgSettingsFacade.barCount`/`barCountNoun`). **The invite-form-in-a-modal, the `/org/users` +
 * `/org/groups` route split, and the `?sel=` side panel are Wave 3** (that design doc's own table) —
 * this wave only touches the header, the segmented toggle's new home, and the 5-field invite row's
 * control widths (`--field-sm`/`--field-md`, `org-settings.html`) so `GROUP` stops matching `EMAIL`'s
 * width for a value a fraction as long.
 */
@Component({
  selector: 'vision-org-settings',
  imports: [FormsModule, EmptyState, PageBar],
  templateUrl: './org-settings.html',
  styleUrl: './org-settings.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [OrgSettingsFacade],
})
export class OrgSettingsPage {
  protected readonly facade = inject(OrgSettingsFacade);
}
