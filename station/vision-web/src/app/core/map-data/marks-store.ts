import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';
import { VisionApi } from '../api/vision-api';
import type {
  Affiliation,
  GeoPosition,
  GeolocateMarkRequest,
  MapMark,
  MarkKind,
  PatchMarkRequest,
  VerificationState,
} from '../api/models';
import { describeHttpError } from '../api-error';
import { ToastService } from '../toast.service';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
import { isLiveAvailable } from '../live/live-fallback-logic';
import { LayersStore } from './layers-store';
import {
  DEFAULT_MARK_PALETTE,
  type MarkDraft,
  type MarkPalette,
  applyMarkEvents,
  createMarkRequest,
  editMarkRequest,
  reconcilePaletteLayer,
  toTacticalMarks,
  withPaletteAffiliation,
  withPaletteKind,
  withPaletteLayer,
} from './mark-logic';

/**
 * Safety-net only (docs/plans/done/MAP-REWORK-PLAN.md §4.3: the live channel is the primary path) — the `map`
 * topic is always-on and this store folds every mark delta in as it arrives, so this poll only
 * reconciles a connection that is genuinely down, mirroring `GeofenceStore`'s own 30s cadence for
 * the identical reason.
 *
 * **Gated on live, not unconditional** (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1) — this
 * poll now runs **only** while `activeConsumers > 0` **and** `LiveStore` is not `'open'`. While live
 * is open the `map` topic already delivers every delta for free, so scheduling this poll on top
 * would just be a redundant `GET` every 30s; see {@link applyTransport} for the exact state table.
 */
const MARKS_POLL_INTERVAL_MS = 30_000;

/**
 * `MarksStore` — the app's one shared source of truth for the marks half of the Common Operational
 * Picture (docs/plans/done/MAP-REWORK-PLAN.md §5.2). **Moved here from `core/marks/` and reworked to v2**: the
 * old store's `pendingKind` two-step is now a full {@link MarkPalette} (kind × affiliation × layer),
 * the old `marks` SSE topic is the scoped `map` topic, and verification/promotion are new.
 * `providedIn: 'root'` and started at boot, exactly as before: marks back the Fly cockpit's map
 * inset and drawer, Command's map and panel, `/live` and asset detail's insets — every page should
 * see the same picture without each standing up its own poller.
 *
 * <h2>Visibility is not this store's job</h2>
 * `GET /api/map/marks` already returns only marks on layers this viewer may see (§3), and the `map`
 * topic is filtered per connection (§4.3). Nothing here filters for visibility; the only filtering
 * this store does is the panels' own UNVERIFIED **view** chip, a pure list filter over data the
 * viewer already has.
 *
 * <h2>Initial GET + live deltas, not poll-only</h2>
 * The `map` topic is deliberately not snapshot-on-connect, so this store always does its own initial
 * `GET` (`refresh()`, at construction and on the safety-net poll) and folds
 * `LiveStore.mapEvents()` arrivals on top via `mark-logic.ts#applyMarkEvents` — `created`/`updated`
 * upsert by id, `cleared`/`deleted` remove, and a deleted *layer* takes its marks with it. A delta
 * for a mark this store hasn't GET-ed yet (a narrow race right after boot) still upserts correctly.
 *
 * <h2>Not optimistic at all</h2>
 * Every mutation awaits the API call and adopts the server's own response — never assumes a write
 * succeeded before the network says so. The one exception used to be `moveTo`, whose drag gesture
 * had already moved the Leaflet symbol before the PATCH settled; marks are no longer draggable, so
 * that revert-on-failure touch is gone with it.
 *
 * <h2>Why selection + palette live here, not on a facade</h2>
 * Unchanged from the v1 store's own reasoning: they coordinate **two independent DOM subtrees under
 * the same routed page with no parent/child relationship** — `<vision-tactical-map>` (wired in the
 * host's template) and the marks panel / `<vision-mark-palette>` (siblings). A facade signal would
 * have to be threaded through both; a root store is simply what both already inject.
 *
 * <h2>`providedIn: 'root'` leaks arming across pages unless it resets itself</h2>
 * A page-provided facade dies with its route; this store does not — arm "place mark" on `/command`,
 * navigate to `/fly/:assetId` without disarming first, and the cockpit's own map inset inherits the
 * still-armed state with nothing on screen explaining why. `armed`/`draft`/`palette` are *interaction*
 * state (what the operator is mid-doing), not *data* (the marks themselves, which correctly stay
 * shared across every page — that is this store's entire reason to be `root`), so only those three
 * reset, on every navigation whose **path** actually changes (a query-param-only navigation, e.g.
 * `CommandFacade`'s `?asset=` sync, does not — see `resetOnRouteChange`'s own doc comment). Modeled on
 * `core/ui/overlay-store.ts#GlobalOverlayStore`'s identical `Router.events` + `NavigationEnd` seam, the
 * only other place in this app a `root` store has to fence its own state off from routing.
 *
 * <h2>Polling is demand-gated (ALWAYS-ON-FLOW-PLAN.md §4 Wave C3), never "since app boot"</h2>
 * `providedIn: 'root'` means this store, once constructed, outlives every route — but before this
 * wave its constructor started the 30s safety-net poll unconditionally, so visiting **any** map
 * surface once left it polling for the rest of the browser session, on every later route, including
 * ones with no map on screen at all (the defect this wave fixes: `/command`, `/fly`, `/live`,
 * `/assets/:assetId` and `/crew/:assetId` all inject this store — directly, or via
 * `shared/map/map-controls/**` — and none of them previously released it). {@link activate}/
 * {@link release} are the fix: every direct injector of this store (a routed page's own facade *and*
 * a non-routed presentational child like `MarksPanel`/`MarkPalette`, per this file's own "why
 * selection + palette live here" doc section above) calls `activate()` in its constructor and
 * `release()` from its own `DestroyRef.onDestroy` — mirroring `core/events/events-store.ts#activate`'s
 * identical ref-counted shape (the nearest existing precedent for a `providedIn:'root'` store whose
 * poll must track live demand rather than run forever) and `core/live/live-store.ts`'s per-topic
 * ref-counting for the counting idiom itself. The initial `GET` **moves under `activate()` too, not
 * just the poll** — a `void refresh()` in the constructor would still fire once per first-ever
 * construction regardless of whether anything is mounted to show the result, and would give a
 * consumer that activates long after boot (e.g. the first time `/crew/:assetId`'s Map tools drawer is
 * ever opened in a session) an arbitrarily stale list instead of a fresh one. Folding live `map`-topic
 * deltas (the `effect()` below) stays unconditional regardless of `activeConsumers` — it's an
 * in-memory fold with no network cost, and keeping the cursor advancing means a consumer that
 * reactivates after a long gap doesn't replay deltas `refresh()`'s own fresh `GET` already supersedes.
 *
 * <h2>...and now also gated on live itself (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1)</h2>
 * Wave C3 above made the poll track *demand*; this wave makes it also track *transport* — the two
 * axes compose in {@link applyTransport}, called both by the reconnect-driven `effect()` in the
 * constructor and by `activate()` itself (rather than `activate()` scheduling the poll directly, as
 * it used to): `activeConsumers > 0 && !isLiveAvailable(...)` is the only state that ever runs the
 * 30s poll now. `liveGated` remembers which side of that live/poll line this store was last actually
 * on, so a genuine transition *into* live reconciles once (the `map` topic is not
 * snapshot-on-connect, so whatever changed while this store was polling — or before its first
 * activation at all — needs one authoritative `GET` before trusting deltas alone), while a call that
 * finds nothing changed is a pure no-op — see that method's own doc comment for the full table.
 */
@Injectable({ providedIn: 'root' })
export class MarksStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly live = inject(LiveStore);
  private readonly layers = inject(LayersStore);
  private readonly scheduler = inject(PollScheduler);

  private readonly marksSignal = signal<readonly MapMark[]>([]);
  /** Every ACTIVE mark on a layer this viewer may see, newest first. */
  readonly marks = this.marksSignal.asReadonly();

  private readonly loadedSignal = signal(false);
  /** `true` once the first `refresh()` has settled (success or failure) — tells "loading" from "genuinely empty". */
  readonly loaded = this.loadedSignal.asReadonly();

  /** `<vision-tactical-map>`'s `[marks]` — the display projection (`mark-logic.ts#toTacticalMark`), computed once for every host. */
  readonly displayMarks = computed(() => toTacticalMarks(this.marksSignal()));

  // --- Selection -----------------------------------------------------------------------------
  private readonly selectedMarkIdSignal = signal<string | undefined>(undefined);
  readonly selectedMarkId = this.selectedMarkIdSignal.asReadonly();
  readonly selected = computed(() => this.marksSignal().find((mark) => mark.markId === this.selectedMarkIdSignal()));

  // --- The palette (docs/plans/done/MAP-REWORK-PLAN.md §5.2) ------------------------------------------------
  private readonly paletteSignal = signal<MarkPalette>(DEFAULT_MARK_PALETTE);
  /** What the next mark will be: kind × affiliation × layer. Always present — arming is a separate flag. */
  readonly palette = this.paletteSignal.asReadonly();

  private readonly armedSignal = signal(false);
  /** `true` while the next map click means "place a mark here" — drives `[interactionMode]="'mark'"`. */
  readonly armed = this.armedSignal.asReadonly();

  /** §5.2's own name for it: the palette *while armed*, `null` otherwise. `armed` + `palette` are the two halves. */
  readonly pendingPalette = computed<MarkPalette | null>(() => (this.armedSignal() ? this.paletteSignal() : null));

  private readonly draftSignal = signal<MarkDraft | null>(null);
  /** The captured click, awaiting the label/confirm step in `<vision-mark-palette>`. */
  readonly draft = this.draftSignal.asReadonly();

  /** How many live map deltas this store has already folded in (see `LayersStore`'s identical cursor). */
  private processedLiveEventCount = 0;

  /** The last `NavigationEnd`'s path (no query/hash) — `null` until the first event. See `resetOnRouteChange`. */
  private lastRoutePath: string | null = null;

  /** How many live consumers currently need this store's data — see the class doc's "Polling is demand-gated". */
  private activeConsumers = 0;
  private stopPollFn: (() => void) | null = null;

  /**
   * `false` while this store is (or should be) relying on the safety-net poll rather than live —
   * see {@link applyTransport}'s own doc comment for the full state table this tracks. Starts
   * `false` so this store's very first `applyTransport` call — whichever way `liveAvailable`
   * resolves — is always treated as a genuine transition (never a spurious no-op before this store
   * has ever actually fetched anything).
   */
  private liveGated = false;

  constructor() {
    effect(() => {
      const events = this.live.mapEvents();
      if (events.length <= this.processedLiveEventCount) {
        return;
      }
      const newEvents = events.slice(this.processedLiveEventCount);
      this.processedLiveEventCount = events.length;
      this.marksSignal.update((marks) => applyMarkEvents(marks, newEvents));
    });

    // Re-evaluates poll-vs-live whenever `LiveStore` (re)connects or drops
    // (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3 D1) — mirrors `FleetStore`/
    // `EventsStore`'s identical reconnect-driven effect.
    effect(() => {
      this.applyTransport(isLiveAvailable(this.live.connectionState()));
    });

    // Keeps the palette pointing at a layer this viewer may actually write to: the layer list lands
    // after this store is constructed, and a layer can be deleted (or a grant revoked) mid-session.
    // `reconcilePaletteLayer` returns the identical object when nothing needs to change, so this
    // never loops.
    effect(() => {
      const contributableIds = this.layers.contributable().map((layer) => layer.layerId);
      const fallback = this.layers.defaultLayerId();
      this.paletteSignal.update((palette) => reconcilePaletteLayer(palette, contributableIds, fallback));
    });

    inject(Router)
      .events.pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe((event) => this.resetOnRouteChange(event.urlAfterRedirects));
  }

  /**
   * Disarms/discards the create flow on a genuine page change — see this class's own "leaks arming
   * across pages" doc comment for the repro this closes. Compares the URL's **path only** (query and
   * hash stripped) against the last navigation: `CommandFacade`'s own `?asset=` URL sync (BUG 4) fires
   * a `NavigationEnd` on every selection with an unchanged path, and must not disarm an operator who
   * is simultaneously mid-placing a mark and clicking through assets in the roster. The very first
   * `NavigationEnd` after boot never resets (`lastRoutePath` starts `null`) — everything is already at
   * its default then anyway.
   */
  private resetOnRouteChange(url: string): void {
    const path = url.split('?')[0].split('#')[0];
    if (this.lastRoutePath !== null && path !== this.lastRoutePath) {
      this.armedSignal.set(false);
      this.draftSignal.set(null);
      this.paletteSignal.set(DEFAULT_MARK_PALETTE);
    }
    this.lastRoutePath = path;
  }

  /**
   * Registers demand — call once from a consumer's own constructor (a routed page's facade, or a
   * non-routed presentational child like `MarksPanel` that injects this store directly). The first
   * `activate()` since the last full `release()` routes through {@link applyTransport} with the
   * current transport (this store never destructs, so nothing else would ever refresh a long-stale
   * list) — a fresh `GET` happens immediately unless live is already open, in which case there is
   * nothing to poll for yet. Any further concurrent consumer just bumps the count.
   */
  activate(): void {
    this.activeConsumers++;
    if (this.activeConsumers > 1) {
      return;
    }
    this.applyTransport(isLiveAvailable(this.live.connectionState()));
  }

  /** The matching teardown — call from the consumer's own `DestroyRef.onDestroy`. Stops the poll once nothing is left. */
  release(): void {
    if (this.activeConsumers === 0) {
      return; // defensive — a mismatched release should never go negative
    }
    this.activeConsumers--;
    if (this.activeConsumers === 0 && this.stopPollFn !== null) {
      this.stopPollFn();
      this.stopPollFn = null;
    }
  }

  /**
   * D1's frozen gate (docs/plans/active/LIVE-POLL-RETIREMENT-PLAN.md §3): the safety-net poll runs
   * **only** while `activeConsumers > 0` **and** live is unavailable.
   *
   * | `activeConsumers` | `liveAvailable` | previous (`liveGated`) | Action |
   * |---|---|---|---|
   * | `0` | any | any | stop poll; no refresh |
   * | `>0` | `true` | `false` (poll) | stop poll; refresh once (the reconcile) |
   * | `>0` | `true` | `true` (live) | nothing |
   * | `>0` | `false` | `true` (live) | refresh once, then start poll |
   * | `>0` | `false` | `false` (poll) | nothing (already polling) |
   *
   * Called both by the reconnect-driven `effect()` above and by `activate()` itself.
   */
  private applyTransport(liveAvailable: boolean): void {
    if (this.activeConsumers === 0) {
      this.stopPolling();
      // Forget the transport mode too. A live outage that starts *and ends* while nothing is
      // mounted delivers no deltas and leaves no trace, so a stale `liveGated` would make the next
      // `activate()` skip its reconcile and show data missing everything the outage swallowed.
      // Clearing it here also restores `activate()`'s documented "first consumer re-fetches"
      // contract, which the live gate would otherwise have quietly weakened.
      this.liveGated = false;
      return;
    }
    if (liveAvailable) {
      if (!this.liveGated) {
        this.stopPolling();
        void this.refresh();
        this.liveGated = true;
      }
      return;
    }
    this.liveGated = false;
    if (this.stopPollFn !== null) {
      return; // already polling
    }
    void this.refresh();
    this.stopPollFn = this.scheduler.schedule(MARKS_POLL_INTERVAL_MS, () => this.refresh());
  }

  private stopPolling(): void {
    this.stopPollFn?.();
    this.stopPollFn = null;
  }

  async refresh(): Promise<void> {
    try {
      this.marksSignal.set(await this.api.listMapMarks());
    } catch {
      // Silent-degrade, like every other background poller in this app.
    } finally {
      this.loadedSignal.set(true);
    }
  }

  // --- Selection -----------------------------------------------------------------------------

  /** Selects `id`, or deselects if it's already selected — one toggle shared by the map's symbol click and the panel's row click. */
  select(id: string): void {
    this.selectedMarkIdSignal.set(this.selectedMarkIdSignal() === id ? undefined : id);
  }

  deselect(): void {
    this.selectedMarkIdSignal.set(undefined);
  }

  // --- Palette + create-by-map-click ---------------------------------------------------------

  setKind(kind: MarkKind): void {
    this.paletteSignal.update((palette) => withPaletteKind(palette, kind));
  }

  setAffiliation(affiliation: Affiliation): void {
    this.paletteSignal.update((palette) => withPaletteAffiliation(palette, affiliation));
  }

  /** `undefined` = "let the server pick" (§3's default layer) — the picker's own empty option. */
  setLayer(layerId: string | undefined): void {
    this.paletteSignal.update((palette) => withPaletteLayer(palette, layerId));
  }

  /** Arms the next map click. Abandons any uncommitted draft — the operator clearly changed their mind. */
  arm(): void {
    this.armedSignal.set(true);
    this.draftSignal.set(null);
  }

  disarm(): void {
    this.armedSignal.set(false);
  }

  /**
   * The map's own `(mapClicked)` passthrough — a no-op unless armed (an ordinary click with nothing
   * pending is panning/inspecting, not "place a mark here"). Captures the position with a snapshot
   * of the palette and disarms, leaving `draft` for the palette to render its label step.
   */
  handleMapClick(position: GeoPosition): void {
    if (!this.armedSignal()) {
      return;
    }
    this.draftSignal.set({ palette: this.paletteSignal(), position });
    this.armedSignal.set(false);
  }

  cancelDraft(): void {
    this.draftSignal.set(null);
  }

  /** Confirms the pending draft with a label/note; `null` if there was no draft or the create failed. */
  async confirmDraft(label: string, note?: string): Promise<MapMark | null> {
    const draft = this.draftSignal();
    if (!draft) {
      return null;
    }
    const created = await this.run(() => this.api.createMapMark(createMarkRequest(draft, label, note)));
    if (created) {
      this.adopt(created);
      this.selectedMarkIdSignal.set(created.markId);
      this.draftSignal.set(null);
    }
    return created;
  }

  // --- Geolocate (the cockpit's "Mark target") -------------------------------------------------

  /**
   * Drops a `DETECTION`-sourced mark projected from `assetId`'s freshest telemetry — an honest
   * estimate, draggable to correct. Sends the current palette's kind/affiliation/layer alongside
   * `assetId` (§5.2: "keeps geolocate … with palette fields"); every one of those is optional on the
   * wire, so an operator who never touched the palette still gets the server's `TARGET`/`"Contact"`
   * defaults. 400s when the asset has no/incomplete telemetry — surfaced as a toast like every other
   * failure here, never as a fabricated pin.
   */
  async geolocate(assetId: string, overrides: Partial<GeolocateMarkRequest> = {}): Promise<MapMark | null> {
    const palette = this.paletteSignal();
    const created = await this.run(() =>
      this.api.geolocateMapMark({
        assetId,
        layerId: palette.layerId,
        kind: palette.kind,
        affiliation: palette.affiliation,
        ...overrides,
      }),
    );
    if (created) {
      this.adopt(created);
      this.selectedMarkIdSignal.set(created.markId);
    }
    return created;
  }

  // --- Edit / clear / move / delete (the server gates each; a 403 becomes a toast) ---------------

  /** Edits label/note/kind/affiliation through the same palette the create step uses. */
  async annotate(id: string, palette: MarkPalette, label: string, note?: string): Promise<boolean> {
    return this.applyPatch(id, editMarkRequest(palette, label, note));
  }

  /** Status → `CLEARED`. The creator may do this while UNVERIFIED; after that only a layer manager (§3). */
  async clear(id: string): Promise<boolean> {
    return this.applyPatch(id, { status: 'CLEARED' });
  }

  // --- Verify / promote (managers only server-side; the UI hides both without MANAGE) ------------

  /** A manager's CONFIRM/REJECT decision on an unverified mark. */
  async verify(id: string, decision: Exclude<VerificationState, 'UNVERIFIED'>): Promise<boolean> {
    const updated = await this.run(() => this.api.verifyMapMark(id, { decision }));
    if (updated) {
      this.adopt(updated);
      this.toasts.ok(decision === 'CONFIRMED' ? `Confirmed "${updated.label}".` : `Rejected "${updated.label}".`);
    }
    return updated !== null;
  }

  /**
   * Moves the mark onto the shared common picture (default target: the COP layer) and stamps it
   * CONFIRMED. `targetLayerId` omitted is the normal path — the button says "Promote to common
   * picture" and means exactly that.
   */
  async promote(id: string, targetLayerId?: string): Promise<boolean> {
    const updated = await this.run(() => this.api.promoteMapMark(id, { targetLayerId }));
    if (updated) {
      this.adopt(updated);
      this.toasts.ok(`Promoted "${updated.label}" to the common picture.`);
    }
    return updated !== null;
  }

  /** The creator (while unverified) or a layer manager, server-side; a 403 elsewhere surfaces as a toast and the mark stays. */
  async remove(id: string): Promise<void> {
    const ok = await this.run(async () => {
      await this.api.deleteMapMark(id);
      return true;
    });
    if (!ok) {
      return;
    }
    this.marksSignal.update((marks) => marks.filter((mark) => mark.markId !== id));
    if (this.selectedMarkIdSignal() === id) {
      this.selectedMarkIdSignal.set(undefined);
    }
  }

  private async applyPatch(id: string, edit: PatchMarkRequest): Promise<boolean> {
    const updated = await this.run(() => this.api.patchMapMark(id, edit));
    if (!updated) {
      return false;
    }
    this.adopt(updated);
    return true;
  }

  /** Folds one server response into the list through the same reducer the live topic uses — a CLEARED mark drops out and gets deselected. */
  private adopt(mark: MapMark): void {
    const action = mark.status === 'CLEARED' ? 'cleared' : 'updated';
    this.marksSignal.update((marks) => applyMarkEvents(marks, [{ entity: 'mark', action, layerId: mark.layerId, mark }]));
    if (action === 'cleared' && this.selectedMarkIdSignal() === mark.markId) {
      this.selectedMarkIdSignal.set(undefined);
    }
  }

  /** One explained toast per failure — the same seam `LayersStore`/`OrgStore`/`FleetStore` all use. */
  private async run<T>(action: () => Promise<T>): Promise<T | null> {
    try {
      return await action();
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return null;
    }
  }
}
