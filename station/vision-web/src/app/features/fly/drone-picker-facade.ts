import { DestroyRef, Injectable, computed, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../../core/api/vision-api';
import { AuthFacade } from '../../core/auth/auth-facade';
import { hasCapability } from '../../core/auth/auth-logic';
import { PollScheduler } from '../../core/poll-scheduler';
import { LiveFacade } from '../../core/live/live-facade';
import { isLiveAvailable } from '../../core/live/live-fallback-logic';
import { readPersistedFlag, writePersistedFlag } from '../../core/panel-state';
import { pickerEmptyStateCopy } from './fly-logic';
import { groupAndSort, myAssignedAssets, type PickerGroups } from './drone-picker-logic';
import type { AssetSummary } from '../../core/api/models';

const LOG_PREFIX = '[drone-picker]';

/** Asset list re-read at this cadence — keeps a still-open picker's cards fresh (a card flipping
 * Offline→Streaming while the operator is looking at it). Unchanged from the pre-split page's own
 * `ASSET_POLL_INTERVAL_MS`. */
const ASSET_POLL_INTERVAL_MS = 5_000;

/** `hideSimulated`'s persisted key (docs/plans/active/OPERATOR-UX-3-PLAN.md §2 T1 — "a `Hide
 * simulated` toggle … persisted in `localStorage`"), namespaced under `vision.fly.*` alongside
 * `cockpit-facade.ts`'s own `MAP_VISIBLE_KEY`. */
const HIDE_SIMULATED_KEY = 'vision.fly.hideSimulated';

/**
 * `DronePickerPage`'s facade (docs/plans/done/UI-ARCHITECTURE-PLAN.md) — `/fly`, the drone chooser
 * (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.5 F12, docs/extracts/design/01-fly.md). Split out of the old combined
 * `FlyFacade` (docs/plans/done/MVP3-PLAN.md §C-b) when the cockpit gained its own addressable route
 * (`/fly/:assetId`) — see `cockpit-facade.ts`'s own doc comment for the cockpit's half.
 *
 * **Deliberately thin**: every "does this visit need the picker at all" decision now happens one
 * layer up, in `fly-redirect-guard.ts`, before this component (and therefore this facade) ever
 * mounts — a bare `/fly` navigation only ever gets here once the guard has already confirmed there
 * is genuinely nothing to skip straight into. So unlike the old `FlyFacade#initPicker`, this facade
 * holds no `?asset=`/remembered-asset resolution, no redirect, nothing route-input-shaped at all —
 * only the picker grid's own list-and-render concern.
 *
 * **No `EventsStore` activation** (unlike the old combined page, which activated it regardless of
 * which branch was showing): the picker renders no events ticker — only `CockpitPage` does — and
 * with the guard now skipping this page entirely whenever there's a live remembered drone, a bare
 * picker visit is typically brief. Narrowing this page's own dependencies to what it actually
 * displays is a deliberate simplification of the split, not an oversight.
 *
 * **`AuthFacade` (docs/plans/done/OPS-UX-PLAN.md §2 A2)** — the one addition this wave makes. `emptyState`
 * feeds `drone-picker.html`'s empty leg entirely from `fly-logic.ts#pickerEmptyStateCopy`: `scopeKind`
 * decides the wording (`ASSIGNED_ASSETS` ⇒ PILOT copy vs the MANAGER/ADMIN copy), `capabilities`
 * decides the CTA, `memberships` names the PILOT's group (docs/plans/active/AUTH-ROLES-PLAN.md §3.2,
 * wave W2 — moved off `topRole`). No new HTTP call — `listAssets()` is already visibility-scoped, so
 * an empty response for a PILOT already means "nothing assigned to you" (see that function's own doc
 * comment).
 *
 * **Poll gated on `LiveFacade` (docs/plans/done/SCALE-100-PLAN.md §5 S6, item 1)** — mirrors
 * `core/fleet/fleet-store.ts#FleetStore`'s identical transport-switch effect: the 5s poll pauses
 * while `LiveFacade` reports an open connection and resumes, refetching immediately, the moment it
 * drops. The `fleet` topic (`List<AssetSummaryResponse>`) is exactly this picker's own domain, so a
 * snapshot arriving while the poll is paused is applied straight onto {@link pickerAssets} — the
 * grid stays live-fresh rather than merely frozen at whatever the poll last fetched, the same
 * "apply the moment one arrives, independent of poll state" idiom `FleetStore.applyDevicesSnapshot`
 * uses for its own `devices` topic.
 *
 * **T1 (docs/plans/active/OPERATOR-UX-3-PLAN.md finding T1, §2 T1)** added {@link groups} (replacing
 * the old flat `orderedPickerAssets`) and the persisted {@link hideSimulated} toggle — see each
 * field's own doc comment.
 *
 * **B4 (docs/plans/active/ASSET-FLOWS-PLAN.md §3 WB2)** added {@link myAssigned} — `GET
 * /api/me/assignments` read once from the constructor (an assignment changes rarely enough, an
 * admin action rather than flight telemetry, that a session-lifetime-stale read is harmless, the
 * same "fetch once" posture `core/ops/thresholds-store.ts` uses for equally rarely-changing config).
 * A failed read is logged and otherwise ignored — {@link myAssignedAssets} already degrades an empty
 * assignment set to "don't show the section", so this facade needs no separate error signal of its
 * own; the picker looks exactly as it did before B4.
 */
@Injectable()
export class DronePickerFacade {
  private readonly api = inject(VisionApi);
  private readonly auth = inject(AuthFacade);
  private readonly scheduler = inject(PollScheduler);
  private readonly live = inject(LiveFacade);

  /** Skeleton card count while the first `listAssets()` call is in flight. */
  readonly skeletonRows = [1, 2, 3] as const;
  readonly pickerAssets = signal<readonly AssetSummary[] | undefined>(undefined);
  readonly pickerError = signal(false);

  /**
   * T1 (docs/plans/active/OPERATOR-UX-3-PLAN.md §2 T1) — "your vehicles" vs. "simulated", each
   * independently sorted (`drone-picker-logic.ts#groupAndSort`). Replaces the pre-T1 flat
   * `orderedPickerAssets` (`fly-logic.ts#sortAssetsForPicker`, still used unchanged by
   * `cockpit-facade.ts#orderedSwitcherAssets` for the cockpit header's own drone switcher — that
   * one stays a flat list, out of this wave's scope). `Date.now()` read directly inside this
   * `computed()` (not stored as its own signal) mirrors `assetLastSeen`/`assetPosition`'s existing
   * per-render-cycle freshness in the pre-T1 `DronePickerPage` — it recomputes whenever
   * `pickerAssets` changes (the 5s poll or a live `fleet` push), the same cadence the "ago" labels
   * already refreshed at.
   */
  readonly groups = computed<PickerGroups>(() => groupAndSort(this.pickerAssets() ?? [], Date.now()));

  /** Total across both groups — `drone-picker.html`'s page-bar count chip and its "no assets at
   * all" empty-state branch (unaffected by the `hideSimulated` toggle, which only hides simulated
   * cards from view, not from this count). */
  readonly totalPickerAssets = computed(() => this.groups().yours.length + this.groups().simulated.length);

  /** Whether the "Simulated" group's cards are collapsed (docs/plans/active/OPERATOR-UX-3-PLAN.md §2
   * T1's "Hide simulated" toggle) — persisted per operator, mirrors `cockpit-facade.ts#mapVisible`'s
   * own `readPersistedFlag`/`writePersistedFlag` idiom exactly (`core/panel-state.ts`, already used
   * identically by `live-facade.ts`/`cockpit-facade.ts`/`SidebarFacade`/`ThemeFacade` — this app's one
   * `localStorage` persistence mechanism for a single boolean/string preference). The group header
   * itself (with its own count) always stays visible; only the card grid beneath it collapses. */
  readonly hideSimulated = signal(readPersistedFlag(HIDE_SIMULATED_KEY, false));

  /** The empty leg's whole view model — see this class's own doc comment. */
  readonly emptyState = computed(() =>
    pickerEmptyStateCopy(this.auth.scopeKind(), this.auth.capabilities(), this.auth.user()?.memberships ?? []),
  );

  /**
   * Gates the **grouped** "Your vehicles" leg's own empty-line `Add source ›` link — the D7 defect
   * (docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md §1/§2.3/§3.5): `/add-source` is `orgGuard`-gated by
   * design (registering inventory is a manager act), but the un-grouped empty-picker leg above
   * ({@link emptyState}'s own `showAddSource`) already gated its identical link and this one never
   * did, so a PILOT with no assignments yet but at least one *simulated* asset in view saw a link
   * guaranteed to bounce them. Mirrors `fly-logic.ts#pickerEmptyStateCopy`'s `showAddSource` exactly
   * (`hasCapability(capabilities, 'MANAGE_ORG')`) rather than inventing a second capability check —
   * one gate, read the same way everywhere it's needed.
   */
  readonly canManageOrg = computed(() => hasCapability(this.auth.capabilities(), 'MANAGE_ORG'));

  /** `GET /api/me/assignments`'s own row set, as ids — see class doc's B4 note. Empty until the
   *  fetch resolves, and stays empty forever on a failed fetch — {@link myAssigned} then degrades to
   *  `[]`, the flat pre-B4 picker. */
  private readonly assignedAssetIdsSignal = signal<ReadonlySet<string>>(new Set());

  /** "My assigned" section (B4) — real assets from {@link groups}'s own `yours` list that the
   *  session's own `GET /api/me/assignments` names, in that list's existing freshness order. */
  readonly myAssigned = computed(() => myAssignedAssets(this.groups().yours, this.assignedAssetIdsSignal()));

  /** `null` until the poll is actually paused/resumed for the first time — see `applyTransport`. */
  private stopPollFn: (() => void) | null = null;

  constructor() {
    void this.refresh();
    void this.refreshAssignments();
    this.stopPollFn = this.schedulePoll();

    effect(() => {
      this.applyTransport(isLiveAvailable(this.live.connectionState()));
    });

    effect(() => {
      const snapshot = this.live.fleet();
      if (snapshot !== undefined) {
        this.pickerAssets.set(snapshot);
        this.pickerError.set(false);
      }
    });

    effect(() => writePersistedFlag(HIDE_SIMULATED_KEY, this.hideSimulated()));

    inject(DestroyRef).onDestroy(() => this.stopPolling());
  }

  retryPicker(): void {
    void this.refresh();
  }

  toggleHideSimulated(): void {
    this.hideSimulated.update((hidden) => !hidden);
  }

  /**
   * Switches whether the local 5s poll is running — mirrors `FleetStore#applyTransport` exactly.
   * `liveAvailable` pauses the poll; its absence resumes it, refetching immediately first (the grid
   * may be stale from however long the live connection was up). A no-op when the poll is already in
   * the requested state (`stopPollFn`'s own nullness tracks that).
   */
  private applyTransport(liveAvailable: boolean): void {
    if (liveAvailable) {
      this.stopPolling();
      return;
    }
    if (this.stopPollFn !== null) {
      return; // already polling
    }
    void this.refresh();
    this.stopPollFn = this.schedulePoll();
  }

  private schedulePoll(): () => void {
    return this.scheduler.schedule(ASSET_POLL_INTERVAL_MS, () => this.refresh());
  }

  private stopPolling(): void {
    this.stopPollFn?.();
    this.stopPollFn = null;
  }

  private async refresh(): Promise<void> {
    try {
      const assets = await this.api.listAssets();
      this.pickerAssets.set(assets);
      this.pickerError.set(false);
    } catch {
      // Only the *first* load is a genuine dead end (nothing to show at all) — a background
      // refresh hiccup on an already-populated grid stays on the stale list silently, matching
      // every other poller in this app.
      if (this.pickerAssets() === undefined) {
        this.pickerError.set(true);
      }
    }
  }

  /** B4 (docs/plans/active/ASSET-FLOWS-PLAN.md §3 WB2) — fetched once, not polled; see class doc's
   *  own "fetch once" note. A failed read is logged and left at the empty default — never surfaced
   *  as a picker-blocking error, since {@link myAssigned} already degrades gracefully to "no
   *  section" (the exact same picker an operator saw before this wave). */
  private async refreshAssignments(): Promise<void> {
    try {
      const assignments = await this.api.myAssignments();
      this.assignedAssetIdsSignal.set(new Set(assignments.map((assignment) => assignment.assetId)));
    } catch (error) {
      console.warn(`${LOG_PREFIX} could not read /api/me/assignments — showing the flat list`, { error });
    }
  }
}
