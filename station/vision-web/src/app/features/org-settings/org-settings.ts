import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { OrgSettingsFacade } from './org-settings-facade';

/**
 * The org-settings surface (`/org`, docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2) — a manager/admin's
 * users-and-groups management page. Route-guarded (`core/auth/auth-guard.ts`, session required) and
 * role-gated (`core/org/org-guard.ts` — only ADMIN/MANAGER reach it; a pilot is redirected); the
 * nav link into it (`shared/ui/identity-chip.ts`) is itself only shown when `canManageOrg`, so a
 * pilot never sees the door. In dev-parity mode (`vision.auth.enabled=false`) the dev admin is
 * ADMIN, so this is always reachable exactly as before — no visibility change.
 *
 * Two sections behind a segmented tab (this app's `.segmented` idiom, `styles.css`): **Users**
 * (list + invite form + enable/disable toggle) and **Groups** (hierarchy tree + create form). Dumb
 * by convention (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — every fetch/mutation and its one-toast handling
 * lives in `OrgStore`, every pure derivation (group tree, role options) in `core/org/org-logic.ts`,
 * and all of it is orchestrated by `OrgSettingsFacade`, which this component injects exclusively.
 * Reuses the existing management-page look wholesale (`.page`/`.card`/`.btn`/`.chip`/`.segmented`/
 * `.empty`), no new colors. Responsive: the page is a single scrolling column of cards; each list row
 * and each form wraps rather than overflowing on a narrow viewport (see `org-settings.css`).
 *
 * **Roster leads, create-on-demand (O1, docs/plans/active/OPERATOR-UX-6-PLAN.md).** Each section used to
 * open on its own 5-to-7-field create form, with the roster/tree starting below it — the same
 * hierarchy inversion this cycle's E1 finding named for the replay page. Each tab's create form now
 * renders inside a `@if (…FormOpen())` panel *above* its roster, revealed by a primary `Create
 * user`/`Create group` button in the page bar's `[pageBarActions]` slot; the roster itself is always
 * the first thing visible. `OrgSettingsFacade#userFormOpen`/`groupFormOpen` are the same kind of
 * plain, non-persisted, non-mutually-exclusive view-toggle signal as `tab` (see that facade's own
 * doc comment) — closed by `Cancel`, `Escape` (bound on the `<form>` itself, bubbling from any
 * field), or a successful create.
 *
 * **Page bar (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2, docs/extracts/design/12-org.md, wave 2).** The old
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
 *
 * **`embedded` (docs/plans/active/WAREHOUSE-UX-PLAN.md §4 wave W7).** `/org` no longer routes here
 * directly (`org-settings.routes.ts` now redirects to `/manage/roster?tab=org`) — this component is
 * always reached embedded as `CrewPage`'s "Organization" tab (`features/roster/crew.ts`) instead.
 * `embedded` still defaults to `false` and the component still owns every one of its own fetches and
 * mutations unchanged — only the header changes shape: `Crew`'s own single sticky `vision-page-bar`
 * already carries the page's title/tab switcher, so a second sticky bar reading "Organization" right
 * underneath it would be pure duplicate chrome (`shared/ui/page-bar/page-bar.css`'s bar is
 * `position: sticky`, so two stacked would fight for the same scroll-pinned real estate). `embedded`
 * true swaps the `<vision-page-bar>` for a plain, non-sticky `.embedded-toolbar` row carrying the
 * identical `Users | Groups` segmented control + Create button — same `<ng-template>` fed to both
 * via `NgTemplateOutlet`, so the two never drift.
 */
@Component({
  selector: 'vision-org-settings',
  imports: [FormsModule, EmptyState, PageBar, NgTemplateOutlet],
  templateUrl: './org-settings.html',
  styleUrl: './org-settings.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [OrgSettingsFacade],
})
export class OrgSettingsPage {
  protected readonly facade = inject(OrgSettingsFacade);

  /** `true` when mounted inside `CrewPage` — see this class's own doc comment. */
  readonly embedded = input(false);
}
