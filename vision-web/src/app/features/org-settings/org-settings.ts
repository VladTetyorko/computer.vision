import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { OrgStore } from '../../core/org/org-store';
import { flattenGroupTree, roleOptions } from '../../core/org/org-logic';
import { roleLabel } from '../../core/auth/auth-logic';
import { EmptyState } from '../../shared/ui/empty-state';
import type { CreateUserRequest, Role } from '../../core/api/models';

type Tab = 'users' | 'groups';

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
 * by convention — every fetch/mutation and its one-toast handling lives in `OrgStore`; every pure
 * derivation (group tree, role options) in `core/org/org-logic.ts`. Reuses the existing
 * management-page look wholesale (`.page`/`.card`/`.btn`/`.chip`/`.segmented`/`.empty`), no new
 * colors. Responsive: the page is a single scrolling column of cards; each list row and each form
 * wraps rather than overflowing on a narrow viewport (see `org-settings.css`).
 */
@Component({
  selector: 'vision-org-settings',
  imports: [FormsModule, EmptyState],
  templateUrl: './org-settings.html',
  styleUrl: './org-settings.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrgSettingsPage {
  protected readonly org = inject(OrgStore);

  protected readonly tab = signal<Tab>('users');
  protected readonly roleOptions = roleOptions();
  protected readonly roleLabel = roleLabel;

  /** The group hierarchy pre-flattened for the template (`@for` can't recurse) — depth drives the indent. */
  protected readonly flatGroups = computed(() => flattenGroupTree(this.org.groupTree()));

  // --- Invite-user form ------------------------------------------------------------------------
  protected readonly newUsername = signal('');
  protected readonly newDisplayName = signal('');
  protected readonly newEmail = signal('');
  protected readonly newPassword = signal('');
  protected readonly newGroupId = signal(''); // '' = no group/membership
  protected readonly newRole = signal<Role>('PILOT');
  protected readonly newEnabled = signal(true);
  protected readonly creatingUser = signal(false);

  // --- Create-group form -----------------------------------------------------------------------
  protected readonly newGroupName = signal('');
  protected readonly newParentId = signal(''); // '' = root group
  protected readonly creatingGroup = signal(false);

  constructor() {
    // Reached only past the role guard, so loading admin data here (not from the store's own
    // constructor) keeps `/api/users`/`/api/groups` off the boot path for a pilot who'd just 403.
    void this.org.refresh();
  }

  protected groupName(id: string): string {
    return this.org.groups().find((group) => group.id === id)?.name ?? id.slice(0, 8);
  }

  protected canSubmitUser(): boolean {
    return (
      !this.creatingUser() &&
      this.newUsername().trim().length > 0 &&
      this.newDisplayName().trim().length > 0 &&
      this.newEmail().trim().length > 0 &&
      this.newPassword().length > 0
    );
  }

  protected async submitUser(): Promise<void> {
    if (!this.canSubmitUser()) {
      return;
    }
    const groupId = this.newGroupId();
    const request: CreateUserRequest = {
      username: this.newUsername().trim(),
      displayName: this.newDisplayName().trim(),
      email: this.newEmail().trim(),
      password: this.newPassword(),
      enabled: this.newEnabled(),
      // A role only means something within a group, so a membership is sent only when one is picked.
      memberships: groupId ? [{ groupId, role: this.newRole() }] : undefined,
    };
    this.creatingUser.set(true);
    const created = await this.org.createUser(request);
    this.creatingUser.set(false);
    if (created) {
      this.newUsername.set('');
      this.newDisplayName.set('');
      this.newEmail.set('');
      this.newPassword.set('');
      this.newGroupId.set('');
      this.newRole.set('PILOT');
      this.newEnabled.set(true);
    }
  }

  protected async toggleEnabled(userId: string, enabled: boolean): Promise<void> {
    await this.org.setUserEnabled(userId, enabled);
  }

  protected canSubmitGroup(): boolean {
    return !this.creatingGroup() && this.newGroupName().trim().length > 0;
  }

  protected async submitGroup(): Promise<void> {
    if (!this.canSubmitGroup()) {
      return;
    }
    const parentId = this.newParentId();
    this.creatingGroup.set(true);
    const created = await this.org.createGroup({
      name: this.newGroupName().trim(),
      parentGroupId: parentId ? parentId : undefined,
    });
    this.creatingGroup.set(false);
    if (created) {
      this.newGroupName.set('');
      this.newParentId.set('');
    }
  }
}
