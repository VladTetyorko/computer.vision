import { Injectable, computed, inject, signal } from '@angular/core';
import { OrgFacade } from '../../core/org/org-facade';
import { flattenGroupTree, roleOptions } from '../../core/org/org-logic';
import { roleLabel } from '../../core/auth/auth-logic';
import type { CreateUserRequest, Role, UserMembership, UserSummary } from '../../core/api/models';

type Tab = 'users' | 'groups';

/**
 * `OrgSettingsPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — orchestrates `OrgFacade` plus the two
 * forms' own draft state; every read-model/command below is byte-for-byte what `OrgSettingsPage`
 * owned before this refactor. `tab` is a plain non-exclusive view toggle (a segmented tab, not an
 * overlay another route/component could ever need to stay consistent with), so it stays a plain
 * signal here rather than a `UiStore` group — see docs/plans/done/UI-ARCHITECTURE-PLAN.md's own "toggle-style
 * state that is NOT mutually exclusive... stays a plain boolean/enum" carve-out.
 */
@Injectable()
export class OrgSettingsFacade {
  readonly org = inject(OrgFacade);

  readonly tab = signal<Tab>('users');
  readonly roleOptions = roleOptions();
  readonly roleLabel = roleLabel;

  /**
   * Whether each tab's create-form panel is revealed above its roster (O1, docs/plans/active/OPERATOR-UX-6-PLAN.md
   * — see this facade's own class doc comment). Plain, non-persisted view toggles, the same carve-out
   * as {@link tab} above: neither form is an overlay another route/component needs to stay consistent
   * with, so no `UiStore` group. Independent booleans, not a shared "which form is open" enum — a
   * future third create surface (there is none today) would not need to fight either of these for
   * exclusivity, and each is only ever rendered while its own tab is active anyway.
   */
  readonly userFormOpen = signal(false);
  readonly groupFormOpen = signal(false);

  /** The group hierarchy pre-flattened for the template (`@for` can't recurse) — depth drives the indent. */
  readonly flatGroups = computed(() => flattenGroupTree(this.org.groupTree()));

  /**
   * The page bar's count chip (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2) — "how many" for whichever section
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
   * own membership column (docs/plans/done/VISUAL-REFRESH-PLAN.md F5 rule 4: at most one chip per row; this
   * row's one chip is Enabled/Disabled, the row's actual state). Was one `.chip` per membership,
   * unbounded; collapsed to plain text the same way `features/roster/roster.ts#RosterPage
   * .assetNames` already collapses a pilot's N asset assignments into one comma-joined list.
   */
  membershipsSummary(memberships: readonly UserMembership[]): string {
    return memberships.map((m) => `${this.groupName(m.groupId)} · ${roleLabel(m.role)}`).join(', ');
  }

  openUserForm(): void {
    this.userFormOpen.set(true);
  }

  /** Closed by `Cancel`, `Escape`, or a successful {@link submitUser} — see this facade's own doc comment. */
  closeUserForm(): void {
    this.userFormOpen.set(false);
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
      this.closeUserForm();
    }
  }

  async toggleEnabled(userId: string, enabled: boolean): Promise<void> {
    await this.org.setUserEnabled(userId, enabled);
  }

  // --- Manage-user panel (docs/plans/active/AUTH-ROLES-PLAN.md wave W3): edit memberships + reset ---
  // ---  a password on another user's behalf. One panel, expanded inline under its row (mirrors the
  // ---  create-user/create-group forms' own inline-panel shape, not a floating dialog) — at most
  // ---  one user managed at a time, so a plain nullable id is the whole "which row is open" state,
  // ---  same non-`UiStore` carve-out `tab`/`userFormOpen` above already use.

  readonly managingUserId = signal<string | null>(null);
  /** A working copy of the managed user's memberships — replaced wholesale on save, never a delta (mirrors `SetMembershipsRequest`'s own wire contract). */
  readonly editMemberships = signal<readonly UserMembership[]>([]);
  /** The "add a membership" row's own two pickers. */
  readonly draftGroupId = signal('');
  readonly draftRole = signal<Role>('PILOT');
  readonly savingMemberships = signal(false);

  readonly resetPasswordValue = signal('');
  readonly resettingPassword = signal(false);

  isManaging(userId: string): boolean {
    return this.managingUserId() === userId;
  }

  /** Groups not already in the working set — the add-membership picker's own options, so a user can't be given the same group twice. */
  readonly draftAddableGroups = computed(() => {
    const taken = new Set(this.editMemberships().map((m) => m.groupId));
    return this.flatGroups().filter((node) => !taken.has(node.group.id));
  });

  openManageUser(user: UserSummary): void {
    this.managingUserId.set(user.userId);
    this.editMemberships.set(user.memberships);
    this.draftGroupId.set('');
    this.draftRole.set('PILOT');
    this.resetPasswordValue.set('');
  }

  closeManageUser(): void {
    this.managingUserId.set(null);
    this.editMemberships.set([]);
    this.resetPasswordValue.set('');
  }

  addDraftMembership(): void {
    const groupId = this.draftGroupId();
    if (!groupId) {
      return;
    }
    this.editMemberships.set([...this.editMemberships(), { groupId, role: this.draftRole() }]);
    this.draftGroupId.set('');
    this.draftRole.set('PILOT');
  }

  removeDraftMembership(groupId: string): void {
    this.editMemberships.set(this.editMemberships().filter((m) => m.groupId !== groupId));
  }

  setDraftMembershipRole(groupId: string, role: Role): void {
    this.editMemberships.set(this.editMemberships().map((m) => (m.groupId === groupId ? { ...m, role } : m)));
  }

  async saveMemberships(): Promise<void> {
    const userId = this.managingUserId();
    if (!userId || this.savingMemberships()) {
      return;
    }
    this.savingMemberships.set(true);
    const updated = await this.org.setMemberships(userId, this.editMemberships());
    this.savingMemberships.set(false);
    if (updated) {
      // Re-seed from the server's own response rather than assuming the wholesale replace echoed
      // back exactly what was sent — same "single source of truth" reasoning as `submitUser`'s own
      // reset-from-`created` pattern above.
      this.editMemberships.set(updated.memberships);
    }
  }

  canResetPassword(): boolean {
    return !this.resettingPassword() && this.resetPasswordValue().length > 0;
  }

  async submitPasswordReset(): Promise<void> {
    const userId = this.managingUserId();
    if (!userId || !this.canResetPassword()) {
      return;
    }
    this.resettingPassword.set(true);
    const ok = await this.org.adminSetPassword(userId, this.resetPasswordValue());
    this.resettingPassword.set(false);
    if (ok) {
      this.resetPasswordValue.set('');
    }
  }

  openGroupForm(): void {
    this.groupFormOpen.set(true);
  }

  /** Closed by `Cancel`, `Escape`, or a successful {@link submitGroup} — see this facade's own doc comment. */
  closeGroupForm(): void {
    this.groupFormOpen.set(false);
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
      this.closeGroupForm();
    }
  }
}
