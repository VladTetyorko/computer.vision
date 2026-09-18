import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { AuthFacade } from '../../core/auth/auth-facade';
import { PollScheduler } from '../../core/poll-scheduler';
import { DiscoveryInboxStore } from '../../core/discovery/discovery-inbox-store';
import {
  DEFAULT_DISCOVERY_INBOX_VISIBILITY,
  buildRegisterCommand,
  dismissedCandidateCount,
  newCandidateCount,
  registeredCandidateCount,
  sourceUnreachableWarnings,
  visibleCandidates,
  type DiscoveryInboxVisibility,
  type RegisterDraft,
} from '../../core/discovery/discovery-inbox-logic';
import { FoundDeviceCard } from './found-device-card';
import { AddCandidateDialog } from './add-candidate-dialog';
import { AttachCandidateDialog } from './attach-candidate-dialog';
import { Notice } from '../../shared/ui/notice';
import type { AssetSummary, Category, DiscoveryCandidate } from '../../core/api/models';

/** The clock tick behind every card's "last heard <age> ago" — 1s is plenty for an age label whose
 *  smallest unit is whole seconds; matches `NotificationBell`'s own `stopClock` cadence. */
const CLOCK_TICK_MS = 1_000;

/**
 * "Found devices" — the Inventory page's discovery-inbox section
 * (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P2, §11, wave Z2d). A non-routed
 * presentational child (`core/ui/architecture.spec.ts`'s own carve-out for pages like this one —
 * see that suite's class doc: "`pilots-card`, `wall-tile`… may still DI-share a host-provided
 * store"), so it injects `DiscoveryInboxStore`/`VisionApi` directly rather than going through a
 * page facade of its own; `InventoryPage` mounts this with no inputs at all.
 *
 * **Unobtrusively invisible when there is nothing to show** — renders nothing at all while the
 * inbox is empty (no candidates of *any* status yet), rather than a permanent empty-state card;
 * the count badge next to the section heading is the "how many need me" signal, mirroring the
 * app's other attention counts (Command's queue, the header bell).
 *
 * **Polling**: activates `DiscoveryInboxStore` once this session is known to hold `MANAGE_ORG`
 * ({@link canSeeInbox}), releases on destroy — the store's own activate/release refcount is what
 * makes "no polling when unmounted" true, not a bespoke teardown here (see that store's own class
 * doc). A pilot/viewer never activates it and never renders: the endpoint is `mayManageOrg`-only
 * and answered them `403` on every tick (docs/plans/active/INVENTORY-REWORK-CONTEXT.md §3 defect F).
 *
 * **Categories/assets are fetched lazily**, once, the first time either dialog is opened — not
 * eagerly alongside the candidates poll, since most page visits open neither dialog at all.
 *
 * **A3 (docs/plans/active/ASSET-FLOWS-PLAN.md §2)** — {@link unreachableWarnings} renders "mediamtx
 * unreachable — found devices may be incomplete" (and per-source equivalents) whenever the inbox
 * envelope's own `sources` reports one down, even with zero candidates, so an operator can tell "the
 * scanner is broken" apart from "genuinely found nothing" — see `found-devices.html`'s own outer
 * `@if` for exactly how that changes the section's visibility.
 */
@Component({
  selector: 'vision-found-devices',
  imports: [FoundDeviceCard, AddCandidateDialog, AttachCandidateDialog, Notice],
  templateUrl: './found-devices.html',
  styleUrl: './found-devices.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class FoundDevices {
  protected readonly store = inject(DiscoveryInboxStore);
  private readonly api = inject(VisionApi);
  private readonly auth = inject(AuthFacade);
  private readonly router = inject(Router);

  /**
   * `GET /api/discovery/inbox` is `mayManageOrg`-only — `DiscoveryInboxController` answers `403` to
   * everybody else (docs/plans/active/INVENTORY-REWORK-CONTEXT.md §3 defect F: every pilot and
   * viewer opening `/assets` used to fire that request and take the error path, on a 30s cadence).
   * This is a `computed`, not a constructor read, because `GET /api/auth/me` may not have resolved
   * yet when this component mounts — the poll starts the moment it does, and never at all for a
   * session that will never be allowed.
   */
  protected readonly canSeeInbox = computed(() => this.auth.can('MANAGE_ORG'));

  private readonly nowSignal = signal(Date.now());
  protected readonly now = this.nowSignal.asReadonly();

  private readonly visibility = signal<DiscoveryInboxVisibility>(DEFAULT_DISCOVERY_INBOX_VISIBILITY);
  protected readonly showRegistered = computed(() => this.visibility().showRegistered);
  protected readonly showDismissed = computed(() => this.visibility().showDismissed);

  protected readonly visible = computed(() => visibleCandidates(this.store.candidates(), this.visibility()));
  protected readonly newCount = computed(() => newCandidateCount(this.store.candidates()));
  protected readonly registeredCount = computed(() => registeredCandidateCount(this.store.candidates()));
  protected readonly dismissedCount = computed(() => dismissedCandidateCount(this.store.candidates()));

  /** A3 — see class doc's own note. */
  protected readonly unreachableWarnings = computed(() => sourceUnreachableWarnings(this.store.sources()));

  // --- Dialogs — at most one open at a time, each a plain nullable target signal (mirrors
  // `UiStore`'s "mutually exclusive overlay" shape in spirit; this component isn't a routed page
  // so `architecture.spec.ts`'s literal `signal()`-ban doesn't apply, but the same discipline —
  // opening one always closes the other — is followed by hand below). ------------------------

  protected readonly addTarget = signal<DiscoveryCandidate | null>(null);
  protected readonly attachTarget = signal<DiscoveryCandidate | null>(null);

  protected readonly categories = signal<readonly Category[]>([]);
  protected readonly assets = signal<readonly AssetSummary[]>([]);

  protected readonly dialogBusy = computed(() => {
    const id = this.addTarget()?.id ?? this.attachTarget()?.id;
    return id !== undefined && this.store.busyId() === id;
  });

  constructor() {
    // Registered at most once, and only for a session allowed to read the inbox at all — the store's
    // own refcount pairs one `activate()` with one `release()`, so the teardown mirrors that flag.
    let activated = false;
    effect(() => {
      if (this.canSeeInbox() && !activated) {
        activated = true;
        this.store.activate();
      }
    });
    const scheduler = inject(PollScheduler);
    const stopClock = scheduler.schedule(CLOCK_TICK_MS, () => this.nowSignal.set(Date.now()));
    inject(DestroyRef).onDestroy(() => {
      if (activated) {
        this.store.release();
      }
      stopClock();
    });
  }

  protected toggleShowRegistered(): void {
    this.visibility.update((value) => ({ ...value, showRegistered: !value.showRegistered }));
  }

  protected toggleShowDismissed(): void {
    this.visibility.update((value) => ({ ...value, showDismissed: !value.showDismissed }));
  }

  protected async openAdd(candidate: DiscoveryCandidate): Promise<void> {
    this.attachTarget.set(null);
    if (this.categories().length === 0) {
      this.categories.set(await this.api.listCategories().catch(() => []));
    }
    this.addTarget.set(candidate);
  }

  protected async openAttach(candidate: DiscoveryCandidate): Promise<void> {
    this.addTarget.set(null);
    if (this.assets().length === 0) {
      this.assets.set(await this.api.listAssets().catch(() => []));
    }
    this.attachTarget.set(candidate);
  }

  protected closeDialogs(): void {
    this.addTarget.set(null);
    this.attachTarget.set(null);
  }

  protected async onDismiss(candidate: DiscoveryCandidate): Promise<void> {
    await this.store.dismiss(candidate.id);
  }

  protected async onRegisterSubmit(draft: RegisterDraft): Promise<void> {
    const target = this.addTarget();
    if (!target) {
      return;
    }
    const result = await this.store.register(target.id, buildRegisterCommand(draft));
    if (result) {
      this.closeDialogs();
      await this.router.navigate(['/assets', result.assetId]);
    }
  }

  protected async onAttachSubmit(assetId: string): Promise<void> {
    const target = this.attachTarget();
    if (!target) {
      return;
    }
    const ok = await this.store.attachCandidate(target.id, assetId);
    if (ok) {
      this.closeDialogs();
    }
  }

  protected async onRestore(candidate: DiscoveryCandidate): Promise<void> {
    await this.store.restore(candidate.id);
  }
}
