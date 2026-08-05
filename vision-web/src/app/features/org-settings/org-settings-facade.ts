import { Injectable, computed, inject, signal } from '@angular/core';
import { OrgStore } from '../../core/org/org-store';
import { flattenGroupTree, roleOptions } from '../../core/org/org-logic';
import { roleLabel } from '../../core/auth/auth-logic';
import type { CreateUserRequest, Role, UserMembership } from '../../core/api/models';

type Tab = 'users' | 'groups';

/**
 * `OrgSettingsPage`'s facade (docs/UI-ARCHITECTURE-PLAN.md) — orchestrates `OrgStore` plus the two
 * forms' own draft state; every read-model/command below is byte-for-byte what `OrgSettingsPage`
 * owned before this refactor. `tab` is a plain non-exclusive view toggle (a segmented tab, not an
 * overlay another route/component could ever need to stay consistent with), so it stays a plain
 * signal here rather than a `UiStore` group — see docs/UI-ARCHITECTURE-PLAN.md's own "toggle-style
 * state that is NOT mutually exclusive... stays a plain boolean/enum" carve-out.
 */
@Injectable()
export class OrgSettingsFacade {
  readonly org = inject(OrgStore);

  readonly tab = signal<Tab>('users');
  readonly roleOptions = roleOptions();
  readonly roleLabel = roleLabel;

  /** The group hierarchy pre-flattened for the template (`@for` can't recurse) — depth drives the indent. */
  readonly flatGroups = computed(() => flattenGroupTree(this.org.groupTree()));

  /**
   * The page bar's count chip (docs/NAV-IA-REDESIGN-PLAN.md §2.2) — "how many" for whichever section
   * is showing, so it answers before the eye reaches the list below, same as every other migrated
   * page's `[count]`. Tab-dependent rather than always "users": a Groups count next to "Organization"
   * while looking at the group tree would be answering the wrong question.
   */
  readonly barCount = computed(() => (this.tab() === 'users' ? this.org.users().length : this.org.groups().length));
  readonly barCountNoun = computed(() => (this.tab() === 'users' ? 'user' : 'group'));

  // --- Invite-user form ------------------------------------------------------------------------
  readonly newUsername = signal('');
  readonly newDisplayName = signal('');
  readonly newEmail = signal('');
  readonly newPassword = signal('');
  readonly newGroupId = signal(''); // '' = no group/membership
  readonly newRole = signal<Role>('PILOT');
  readonly newEnabled = signal(true);
  readonly creatingUser = signal(false);

  // --- Create-group form -----------------------------------------------------------------------
  readonly newGroupName = signal('');
  readonly newParentId = signal(''); // '' = root group
  readonly creatingGroup = signal(false);

  constructor() {
    // Reached only past the role guard, so loading admin data here (not from the store's own
    // constructor) keeps `/api/users`/`/api/groups` off the boot path for a pilot who'd just 403.
    void this.org.refresh();
  }

  groupName(id: string): string {
    return this.org.groups().find((group) => group.id === id)?.name ?? id.slice(0, 8);
  }

  /**
   * A single, comma-joined "GroupName · Role" summary of a user's memberships — the Users list's
   * own membership column (docs/VISUAL-REFRESH-PLAN.md F5 rule 4: at most one chip per row; this
   * row's one chip is Enabled/Disabled, the row's actual state). Was one `.chip` per membership,
   * unbounded; collapsed to plain text the same way `features/roster/roster.ts#RosterPage
   * .assetNames` already collapses a pilot's N asset assignments into one comma-joined list.
   */
  membershipsSummary(memberships: readonly UserMembership[]): string {
    return memberships.map((m) => `${this.groupName(m.groupId)} · ${roleLabel(m.role)}`).join(', ');
  }

  canSubmitUser(): boolean {
    return (
      !this.creatingUser() &&
      this.newUsername().trim().length > 0 &&
      this.newDisplayName().trim().length > 0 &&
      this.newEmail().trim().length > 0 &&
      this.newPassword().length > 0
    );
  }

  async submitUser(): Promise<void> {
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

  async toggleEnabled(userId: string, enabled: boolean): Promise<void> {
    await this.org.setUserEnabled(userId, enabled);
  }

  canSubmitGroup(): boolean {
    return !this.creatingGroup() && this.newGroupName().trim().length > 0;
  }

  async submitGroup(): Promise<void> {
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
