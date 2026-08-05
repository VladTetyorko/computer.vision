import { Injectable, computed, effect, inject, signal } from '@angular/core';
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
 * Safety-net only (docs/MAP-REWORK-PLAN.md §4.3: the live channel is the primary path) — the `map`
 * topic is always-on and this store folds every mark delta in as it arrives, so this poll only
 * reconciles a connection that was briefly down/degraded, mirroring `GeofenceStore`'s own 30s
 * cadence for the identical reason.
 */
const MARKS_POLL_INTERVAL_MS = 30_000;

/**
 * `MarksStore` — the app's one shared source of truth for the marks half of the Common Operational
 * Picture (docs/MAP-REWORK-PLAN.md §5.2). **Moved here from `core/marks/` and reworked to v2**: the
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
 * <h2>Not truly optimistic</h2>
 * Every mutation awaits the API call and adopts the server's own response — never assumes a write
 * succeeded before the network says so. The one deliberate exception is {@link moveTo}'s
 * revert-on-failure touch; see its own doc comment.
 *
 * <h2>Why selection + palette live here, not on a facade</h2>
 * Unchanged from the v1 store's own reasoning: they coordinate **two independent DOM subtrees under
 * the same routed page with no parent/child relationship** — `<vision-tactical-map>` (wired in the
 * host's template) and the marks panel / `<vision-mark-palette>` (siblings). A facade signal would
 * have to be threaded through both; a root store is simply what both already inject.
 */
@Injectable({ providedIn: 'root' })
export class MarksStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly live = inject(LiveStore);
  private readonly layers = inject(LayersStore);

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

  // --- The palette (docs/MAP-REWORK-PLAN.md §5.2) ------------------------------------------------
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

  constructor() {
    void this.refresh();
    inject(PollScheduler).schedule(MARKS_POLL_INTERVAL_MS, () => this.refresh());

    effect(() => {
      const events = this.live.mapEvents();
      if (events.length <= this.processedLiveEventCount) {
        return;
      }
      const newEvents = events.slice(this.processedLiveEventCount);
      this.processedLiveEventCount = events.length;
      this.marksSignal.update((marks) => applyMarkEvents(marks, newEvents));
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

  /**
   * Drag-to-correct: PATCHes only the position. **Visually optimistic** — Leaflet has already moved
   * the symbol by the time this settles. On success the server's response is adopted. On failure
   * there is nothing to undo in this store's own data (it was never optimistically written), but the
   * *map* still shows the dragged position, so this forces a fresh array reference (content
   * unchanged) purely so the map's own `applyMarks` effect re-runs and snaps the symbol back to the
   * last-known-good position — an honest revert, not a silent multi-second drift.
   */
  async moveTo(id: string, position: GeoPosition): Promise<boolean> {
    const updated = await this.run(() =>
      this.api.patchMapMark(id, {
        latitude: position.latitude,
        longitude: position.longitude,
        altitudeMeters: position.altitudeMeters,
      }),
    );
    if (!updated) {
      this.marksSignal.update((marks) => [...marks]); // touch — see this method's own doc comment
      return false;
    }
    this.adopt(updated);
    return true;
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
