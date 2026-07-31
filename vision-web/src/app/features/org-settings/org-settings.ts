import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { EmptyState } from '../../shared/ui/empty-state';
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
 */
@Component({
  selector: 'vision-org-settings',
  imports: [FormsModule, EmptyState],
  templateUrl: './org-settings.html',
  styleUrl: './org-settings.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [OrgSettingsFacade],
})
export class OrgSettingsPage {
  protected readonly facade = inject(OrgSettingsFacade);
}
