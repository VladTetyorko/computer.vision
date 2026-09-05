import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import { AuthStore } from '../../core/auth/auth-store';
import { canManageOrg } from '../../core/org/org-logic';
import { SectionHeader } from '../../shared/ui/section-header';
import { EmptyState } from '../../shared/ui/empty-state';
import { assignmentRoleLabel } from '../../core/roster/roster-pivot-logic';
import type { AssignedPilot, AssignmentRole, UserSummary } from '../../core/api/models';

/** Stable per-file console tag, mirroring `[auth]`/`[fleet]`/`[org]`. */
const LOG_PREFIX = '[pilots]';

/**
 * The assigned-pilots card on the asset **manager** page (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2 feature
 * 2) — lists the pilots assigned to one asset with add (pick a user) / remove (unassign). Mounted
 * by `features/asset-detail/asset-detail.html` inside the manager-only "Pilots" `vision-side-panel`
 * drawer (docs/plans/done/UI-REDESIGN-PLAN.md Wave 3 — previously a `span-2` grid child of the old single-
 * column detail grid; the drawer trigger itself is separately gated by `AssetDetailPage.canManagePilots`
 * so a non-manager never even sees the affordance, not just an empty drawer behind it).
 *
 * **Role-gated inside the component, too**: renders **nothing** unless the viewer can manage the org
 * (`canManageOrg(capabilities)` — `MANAGE_ORG`, held by MANAGER/ADMIN); a pilot viewing an asset doesn't manage its roster, so
 * the host can mount this unconditionally and let the card decide — this is the belt to the host's
 * own suspenders, not a second source of truth. In dev-parity mode (`vision.auth.enabled=false`) the
 * dev admin is ADMIN, so the card shows exactly as before.
 *
 * Owns its own small state (this asset's pilots + the full user list to pick from), keyed off the
 * `assetId` input — a self-contained card, not something the big `AssetDetailPage` needs to thread
 * through. **403/404 handled explicitly** (not via a generic `run()`): a `403` on assign/unassign
 * means the asset is outside the manager's scope ("outside your scope" toast); a `404` on the pilots
 * read means the asset is unknown/out-of-scope — the card simply shows its empty state rather than a
 * scary error, matching the backend's "don't reveal existence" rule.
 *
 * **`changed` output** (docs/plans/done/UI-REDESIGN-PLAN.md Wave 4, new, optional) — emitted after a
 * successful add/remove, for a host that keeps its own separate summary of this asset's pilots in
 * sync (`features/roster/**`'s collapsed-row badges, the one other place this asset's pilot list is
 * shown besides this card itself). `AssetDetailPage`'s own drawer host doesn't bind it — nothing
 * else on that page shows a pilot summary outside this card.
 */
@Component({
  selector: 'vision-pilots-card',
  imports: [FormsModule, SectionHeader, EmptyState],
  templateUrl: './pilots-card.html',
  styleUrl: './pilots-card.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PilotsCard {
  readonly assetId = input.required<string>();

  /** Emitted after a successful assign/unassign — see this class's own doc comment. */
  readonly changed = output<void>();

  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly auth = inject(AuthStore);

  protected readonly canManage = computed(() => canManageOrg(this.auth.capabilities()));

  private readonly pilots = signal<readonly AssignedPilot[]>([]);
  private readonly users = signal<readonly UserSummary[]>([]);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly pickUserId = signal('');
  /** The seat granted to a newly-added pilot (docs/plans/active/AUTH-ROLES-PLAN.md wave W3) — defaults to
   *  `'PILOT'`, this card's own pre-existing behavior before the seat picker existed at all. */
  protected readonly pickRole = signal<AssignmentRole>('PILOT');
  protected readonly assignmentRoleLabel = assignmentRoleLabel;
  protected readonly roleOptions: readonly AssignmentRole[] = ['PILOT', 'CREW'];

  /** The assigned pilots, resolved to a display name against the user list when possible, carrying each one's own seat. */
  protected readonly assigned = computed(() => {
    const byId = new Map(this.users().map((user) => [user.userId, user]));
    return this.pilots().map((pilot) => ({
      userId: pilot.userId,
      displayName: byId.get(pilot.userId)?.displayName ?? pilot.userId.slice(0, 8),
      role: pilot.role,
    }));
  });

  /** Users not already assigned — the "add a pilot" picker's options. */
  protected readonly assignable = computed(() => {
    const taken = new Set(this.pilots().map((pilot) => pilot.userId));
    return this.users().filter((user) => user.enabled && !taken.has(user.userId));
  });

  constructor() {
    // Load once the asset id is known and the viewer is a manager — reacts to an id change (the
    // page is reused across navigations) without the host wiring anything.
    effect(() => {
      const id = this.assetId();
      if (this.canManage() && id) {
        void this.load(id);
      }
    });
  }

  private async load(assetId: string): Promise<void> {
    this.loading.set(true);
    try {
      const [pilots, users] = await Promise.all([this.api.listAssetPilots(assetId), this.api.listUsers()]);
      this.pilots.set(pilots);
      this.users.set(users);
    } catch (error) {
      // A 404 = unknown/out-of-scope asset — show empty, don't alarm. Anything else: one quiet log.
      console.warn(`${LOG_PREFIX} failed to load pilots for ${assetId}`, { error });
      this.pilots.set([]);
    } finally {
      this.loading.set(false);
    }
  }

  protected async add(): Promise<void> {
    const userId = this.pickUserId();
    if (!userId || this.busy()) {
      return;
    }
    const role = this.pickRole();
    await this.mutate(() => this.api.assignPilot(this.assetId(), userId, role), `Assigned as ${assignmentRoleLabel(role).toLowerCase()}.`);
    this.pickUserId.set('');
    this.pickRole.set('PILOT');
  }

  protected async remove(userId: string): Promise<void> {
    if (this.busy()) {
      return;
    }
    await this.mutate(() => this.api.unassignPilot(this.assetId(), userId), 'Removed pilot.');
  }

  /**
   * Changes an already-assigned pilot's seat (docs/plans/active/AUTH-ROLES-PLAN.md wave W3) — re-issues
   * the same idempotent `PUT` `assignPilot` already makes for a brand-new assignment (`VisionApi
   * .assignPilot`'s own doc comment: "idempotent"), so there is no separate "update role" endpoint
   * to call.
   */
  protected async changeRole(userId: string, role: AssignmentRole): Promise<void> {
    if (this.busy()) {
      return;
    }
    await this.mutate(() => this.api.assignPilot(this.assetId(), userId, role), `Changed seat to ${assignmentRoleLabel(role).toLowerCase()}.`);
  }

  private async mutate(action: () => Promise<void>, okMessage: string): Promise<void> {
    this.busy.set(true);
    try {
      await action();
      await this.load(this.assetId());
      this.toasts.ok(okMessage);
      this.changed.emit();
    } catch (error) {
      if (error instanceof HttpErrorResponse && error.status === 403) {
        this.toasts.error('That asset is outside your scope — you can only assign pilots to your own assets.');
      } else if (error instanceof HttpErrorResponse && error.status === 404) {
        this.toasts.error('That asset no longer exists — it may have been removed.');
      } else {
        this.toasts.error(describeHttpError(error));
      }
    } finally {
      this.busy.set(false);
    }
  }
}
