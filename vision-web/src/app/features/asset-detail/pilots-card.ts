import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { VisionApi } from '../../core/api/vision-api';
import { describeHttpError } from '../../core/api-error';
import { ToastService } from '../../core/toast.service';
import { AuthStore } from '../../core/auth/auth-store';
import { canManageOrg } from '../../core/org/org-logic';
import { SectionHeader } from '../../shared/ui/section-header';
import { EmptyState } from '../../shared/ui/empty-state';
import type { AssignedPilot, UserSummary } from '../../core/api/models';

/** Stable per-file console tag, mirroring `[auth]`/`[fleet]`/`[org]`. */
const LOG_PREFIX = '[pilots]';

/**
 * The assigned-pilots card on the asset **manager** page (docs/U-SCOPE-PLAN.md, U-e slice 2 feature
 * 2) — lists the pilots assigned to one asset with add (pick a user) / remove (unassign). Mounted
 * by `features/asset-detail/asset-detail.html` inside the manager-only "Pilots" `vision-side-panel`
 * drawer (docs/UI-REDESIGN-PLAN.md Wave 3 — previously a `span-2` grid child of the old single-
 * column detail grid; the drawer trigger itself is separately gated by `AssetDetailPage.canManagePilots`
 * so a non-manager never even sees the affordance, not just an empty drawer behind it).
 *
 * **Role-gated inside the component, too**: renders **nothing** unless the viewer can manage the org
 * (`canManageOrg(topRole)` — ADMIN/MANAGER); a pilot viewing an asset doesn't manage its roster, so
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

  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly auth = inject(AuthStore);

  protected readonly canManage = computed(() => canManageOrg(this.auth.user()?.topRole));

  private readonly pilots = signal<readonly AssignedPilot[]>([]);
  private readonly users = signal<readonly UserSummary[]>([]);
  protected readonly loading = signal(false);
  protected readonly busy = signal(false);
  protected readonly pickUserId = signal('');

  /** The assigned pilots, resolved to a display name against the user list when possible. */
  protected readonly assigned = computed(() => {
    const byId = new Map(this.users().map((user) => [user.userId, user]));
    return this.pilots().map((pilot) => ({
      userId: pilot.userId,
      displayName: byId.get(pilot.userId)?.displayName ?? pilot.userId.slice(0, 8),
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
    await this.mutate(() => this.api.assignPilot(this.assetId(), userId), 'Assigned pilot.');
    this.pickUserId.set('');
  }

  protected async remove(userId: string): Promise<void> {
    if (this.busy()) {
      return;
    }
    await this.mutate(() => this.api.unassignPilot(this.assetId(), userId), 'Removed pilot.');
  }

  private async mutate(action: () => Promise<void>, okMessage: string): Promise<void> {
    this.busy.set(true);
    try {
      await action();
      await this.load(this.assetId());
      this.toasts.ok(okMessage);
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
