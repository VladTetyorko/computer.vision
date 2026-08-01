import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import type { CreateMarkRequest, GeoPosition, Mark, MarkKind, MarkStatus, PatchMarkRequest } from '../api/models';
import { describeHttpError } from '../api-error';
import { ToastService } from '../toast.service';
import { PollScheduler } from '../poll-scheduler';
import { LiveStore } from '../live/live-store';
import { applyMarkEvents } from './mark-logic';

/**
 * Safety-net only (docs/TACTICAL-MARKS-PLAN.md §5: "the live channel is the primary path") — the
 * `marks` live topic is always-on and this store folds every delta in as it arrives, so this poll
 * exists purely to reconcile a connection that was ever briefly down/degraded, mirroring
 * `GeofenceStore`'s own 30s cadence for the identical reason.
 */
const MARKS_POLL_INTERVAL_MS = 30_000;

/** The kind + position captured after a map click while {@link MarksStore#pendingKind} was armed, awaiting the label/note confirm step. */
export interface MarkDraft {
  readonly kind: MarkKind;
  readonly position: GeoPosition;
}

/**
 * `MarksStore` — the app's one shared source of truth for the tactical-marks operational picture
 * (docs/TACTICAL-MARKS-PLAN.md M5), `providedIn: 'root'` and started at boot like `GeofenceStore`:
 * marks back both the Fly cockpit's map inset + "Mark target"/drawer and Command's map + marks
 * panel, and every page should see the same list without each standing up its own poller.
 *
 * <h2>Initial GET + live deltas, not poll-only (mirrors the frozen contract, diverges from GeofenceStore)</h2>
 * Unlike `GeofenceStore` (pure 30s poll, zones are near-static reference data), marks are pushed
 * live over the `marks` `GET /api/live` topic — but that topic is deliberately **not**
 * snapshot-on-connect (see `core/api/models.ts#MarkEvent`'s own doc comment), so this store always
 * does its own `GET /api/marks` first (`refresh()`, called once at construction and again by the
 * safety-net poll) and then folds `LiveStore.markEvents()` arrivals on top via
 * `mark-logic.ts#applyMarkEvents` — `created`/`updated` upsert by id, `cleared` removes ("clients
 * drop the pin"). A live delta that arrives for a mark this store hasn't GET-ed yet (a narrow race
 * right after boot) still upserts correctly — `applyMarkEvents` treats "unknown id" as "add it".
 *
 * <h2>Not truly optimistic, same discipline as GeofenceStore</h2>
 * Every mutation awaits the API call and adopts the server's own response — never assumes a write
 * succeeded before the network says so. The one deliberate exception is `moveTo`'s own revert-on-
 * failure trick — see that method's own doc comment.
 *
 * <h2>Selection + create-by-map-click live here too, not just domain data</h2>
 * `selectedMarkId`/`selected` (picking a mark, e.g. for the bearing/distance readout) and
 * `pendingKind`/`draft` (the create-by-map-click two-step: arm a kind, then the next map click
 * captures a position awaiting a label) are UI-orchestration state, not server data — but they live
 * on this store rather than a facade because they must coordinate **two independent DOM subtrees
 * under the same routed page that have no parent/child relationship**: the shared map component
 * (`<vision-live-map>`/`<vision-fleet-map>`, wired directly in `fly.html`/`command.html`) and the
 * marks panel (`features/fly/marks-panel.ts`/`features/command/marks-panel.ts`, a sibling). Zones
 * never needed this — `GeofenceZoneDialog` draws on its own private, self-contained mini-map, never
 * the shared page map — so this is a deliberate divergence from that precedent, not an oversight
 * (docs/TACTICAL-MARKS-PLAN.md M5's own "flag any place the layering forced a different shape").
 */
@Injectable({ providedIn: 'root' })
export class MarksStore {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly live = inject(LiveStore);

  private readonly marksSignal = signal<readonly Mark[]>([]);
  readonly marks = this.marksSignal.asReadonly();

  private readonly loadedSignal = signal(false);
  /** `true` once the first `refresh()` has settled (success or failure) — distinguishes "loading" from "genuinely empty". */
  readonly loaded = this.loadedSignal.asReadonly();

  // --- Selection (the bearing/distance readout's own source) -------------------------------------
  private readonly selectedMarkIdSignal = signal<string | undefined>(undefined);
  readonly selectedMarkId = this.selectedMarkIdSignal.asReadonly();
  readonly selected = computed(() => this.marksSignal().find((mark) => mark.id === this.selectedMarkIdSignal()));

  // --- Create-by-map-click (docs/TACTICAL-MARKS-PLAN.md M5) ---------------------------------------
  private readonly pendingKindSignal = signal<MarkKind | null>(null);
  /** Non-null while armed for the next map click — the marks panel's "New target/hazard/…" buttons set this. */
  readonly pendingKind = this.pendingKindSignal.asReadonly();

  private readonly draftSignal = signal<MarkDraft | null>(null);
  /** The captured click, awaiting a label/note confirm step in the marks panel. */
  readonly draft = this.draftSignal.asReadonly();

  /** How many live deltas (`LiveStore.markEvents()`, a chronological append-only log) this store has already folded in. */
  private processedLiveEventCount = 0;

  constructor() {
    void this.refresh(); // one-time initial fetch, mirrors `GeofenceStore`'s own constructor precedent.
    inject(PollScheduler).schedule(MARKS_POLL_INTERVAL_MS, () => this.refresh());

    // Folds every `marks` live arrival in as it lands. `LiveStore.markEvents()` only ever grows
    // (chronological append, capped) — reading only the tail past `processedLiveEventCount` avoids
    // reprocessing the same delta twice on the next run. Mirrors `EventsStore`'s own `applyIncoming`
    // posture: runs regardless of poll/live transport state, so nothing is dropped.
    effect(() => {
      const events = this.live.markEvents();
      if (events.length <= this.processedLiveEventCount) {
        return;
      }
      const newEvents = events.slice(this.processedLiveEventCount);
      this.processedLiveEventCount = events.length;
      this.marksSignal.update((marks) => applyMarkEvents(marks, newEvents));
    });
  }

  async refresh(): Promise<void> {
    try {
      const marks = await this.api.listMarks();
      this.marksSignal.set(marks);
    } catch {
      // Silent-degrade, like every other background poller in this app.
    } finally {
      this.loadedSignal.set(true);
    }
  }

  // --- Selection -----------------------------------------------------------------------------

  /** Selects `id`, or deselects if it's already selected — one toggle used by both the map's marker click and the panel's row click. */
  select(id: string): void {
    this.selectedMarkIdSignal.set(this.selectedMarkIdSignal() === id ? undefined : id);
  }

  deselect(): void {
    this.selectedMarkIdSignal.set(undefined);
  }

  // --- Create (map click) -----------------------------------------------------------------------

  /** Creates a mark; `null` on failure (a toast already explains why). Selects the new mark on success. */
  async create(request: CreateMarkRequest): Promise<Mark | null> {
    try {
      const created = await this.api.createMark(request);
      this.marksSignal.update((marks) => applyMarkEvents(marks, [{ action: 'created', mark: created }]));
      this.selectedMarkIdSignal.set(created.id);
      return created;
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return null;
    }
  }

  // --- Create-by-map-click two-step: arm a kind, capture the next click, confirm/cancel ----------

  /** Arms `kind` for the next map click (the marks panel's "New target/hazard/…" buttons). */
  beginPlacement(kind: MarkKind): void {
    this.pendingKindSignal.set(kind);
    this.draftSignal.set(null);
  }

  cancelPlacement(): void {
    this.pendingKindSignal.set(null);
  }

  /**
   * The map's own `(mapClicked)` passthrough — a no-op unless a kind is currently armed (an
   * ordinary click with nothing pending is just panning/inspecting the map, not "place a mark
   * here"). Captures the position and disarms, leaving `draft` for the marks panel to render its
   * label/note confirm step.
   */
  handleMapClick(position: GeoPosition): void {
    const kind = this.pendingKindSignal();
    if (!kind) {
      return;
    }
    this.draftSignal.set({ kind, position });
    this.pendingKindSignal.set(null);
  }

  cancelDraft(): void {
    this.draftSignal.set(null);
  }

  /** Confirms the pending draft with a label/note, creating the mark; `null` if there was no draft or the create failed. */
  async confirmDraft(label: string, note?: string): Promise<Mark | null> {
    const draft = this.draftSignal();
    if (!draft) {
      return null;
    }
    const created = await this.create({ kind: draft.kind, label, note, position: draft.position });
    if (created) {
      this.draftSignal.set(null);
    }
    return created;
  }

  // --- Geolocate (cockpit "Mark target") ----------------------------------------------------------

  /**
   * Drops a `DETECTION`-sourced mark projected from `assetId`'s freshest telemetry (the cockpit's
   * one-tap "Mark target" action) — an honest estimate, draggable/editable to correct
   * (docs/TACTICAL-MARKS-PLAN.md §1). `kind`/`label` omitted defaults server-side to
   * `TARGET`/`"Contact"`. 400s if the asset has no/incomplete telemetry — surfaced as a toast, same
   * as every other failure here.
   */
  async geolocate(assetId: string, kind?: MarkKind, label?: string): Promise<Mark | null> {
    try {
      const created = await this.api.geolocateMark({ assetId, kind, label });
      this.marksSignal.update((marks) => applyMarkEvents(marks, [{ action: 'created', mark: created }]));
      this.selectedMarkIdSignal.set(created.id);
      return created;
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return null;
    }
  }

  // --- Annotate / clear / drag-to-correct (all PATCH; server 403s a non-creator, non-manager edit) --

  /** Edits label/note/kind (annotation) — any in-scope viewer may attempt this; a 403 (not this mark's creator or a manager) surfaces as a friendly toast, per the backend's own gate. */
  async annotate(id: string, edit: { kind?: MarkKind; label?: string; note?: string }): Promise<boolean> {
    return this.applyPatch(id, edit);
  }

  /** Status → `CLEARED`. Creator-or-manager only server-side; a 403 elsewhere surfaces as a toast. */
  async clear(id: string): Promise<boolean> {
    return this.applyPatch(id, { status: 'CLEARED' satisfies MarkStatus });
  }

  /**
   * Drag-to-correct: PATCHes only `position`. **Visually optimistic** — Leaflet has already moved
   * the marker by the time this promise settles (the map component's own `dragend` handler emits
   * the new position, this method is only called after the gesture already happened). On success,
   * the server's own response is adopted (same as every other mutation). On failure, there is
   * nothing to "undo" in this store's own data (it was never optimistically written here) — but the
   * *map* still shows the dragged position, so this forces a fresh array reference (content
   * unchanged) purely so `<vision-live-map>`/`<vision-fleet-map>`'s own `applyMarks` effect re-runs
   * and re-applies `setLatLng` to the still-correct, last-known-good position from this store — an
   * honest revert, not a silent multi-second drift until the next poll.
   */
  async moveTo(id: string, position: GeoPosition): Promise<boolean> {
    try {
      const updated = await this.api.patchMark(id, { position });
      this.marksSignal.update((marks) => applyMarkEvents(marks, [{ action: 'updated', mark: updated }]));
      return true;
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      this.marksSignal.update((marks) => [...marks]); // touch — see this method's own doc comment
      return false;
    }
  }

  private async applyPatch(id: string, edit: PatchMarkRequest): Promise<boolean> {
    try {
      const updated = await this.api.patchMark(id, edit);
      this.marksSignal.update((marks) => applyMarkEvents(marks, [{ action: updated.status === 'CLEARED' ? 'cleared' : 'updated', mark: updated }]));
      if (updated.status === 'CLEARED' && this.selectedMarkIdSignal() === id) {
        this.selectedMarkIdSignal.set(undefined);
      }
      return true;
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return false;
    }
  }

  // --- Delete --------------------------------------------------------------------------------

  /** Creator-or-manager only server-side; a 403 elsewhere surfaces as a toast, the mark stays in the list. */
  async remove(id: string): Promise<void> {
    try {
      await this.api.deleteMark(id);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return;
    }
    this.marksSignal.update((marks) => marks.filter((mark) => mark.id !== id));
    if (this.selectedMarkIdSignal() === id) {
      this.selectedMarkIdSignal.set(undefined);
    }
  }
}
