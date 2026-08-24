import { Injectable, computed, inject, signal } from '@angular/core';
import { VisionApi } from '../api/vision-api';
import { rulesFrom } from './control-action-logic';
import type {
  ControlCatalog,
  ControlProfile,
  CreateControlProfileRequest,
  UpdateControlProfileRequest,
} from '../api/models';

/**
 * `ControlProfileStore` — the operator's controller layouts and the catalogue they are built from
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md decisions C6/C8).
 *
 * <h2>Two consumers, one load</h2>
 * The `/manage/controller` setup page edits these layouts; the Fly cockpit's Controller drawer reads
 * the active one so a bound switch can fire without engaging a session (decision C3). Both need the
 * same two documents, so they share one root-provided store rather than each fetching — and
 * {@link load} is idempotent, so a drawer opening after the setup page has already loaded costs
 * nothing.
 *
 * **`providedIn: 'root'`**, unlike the per-host `TelemetryStore`/`GeoStore`: what an operator's
 * transmitter is mapped to is not scoped to a route, and re-fetching it every time a drawer opens
 * would be re-reading a document that only changes when this same store changes it.
 *
 * <h2>Writes reload rather than patch</h2>
 * Every mutation re-reads the list. Activation in particular is a server-side move — activating one
 * profile deactivates whichever other held the flag for that vehicle kind (`ControlProfileService`)
 * — so a locally-patched list would be a guess about a rule the backend owns. The lists are small
 * and the writes are rare; correctness is worth the round trip.
 */
@Injectable({ providedIn: 'root' })
export class ControlProfileStore {
  private readonly api = inject(VisionApi);

  private readonly profilesSignal = signal<readonly ControlProfile[]>([]);
  private readonly catalogSignal = signal<ControlCatalog | undefined>(undefined);
  private readonly loadedSignal = signal(false);
  private readonly loadingSignal = signal(false);

  /** Every layout this operator has, saved and built-in, newest saved first (server order). */
  readonly profiles = this.profilesSignal.asReadonly();
  /** `undefined` until the first successful load — every picker that reads it renders empty, not wrong. */
  readonly catalog = this.catalogSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  /** Whether a load has ever completed — distinguishes "no profiles" from "not asked yet". */
  readonly loaded = this.loadedSignal.asReadonly();

  /** The catalogue's own danger flags and switch levels, for `ControlActionDispatcher`. */
  readonly rules = computed(() => rulesFrom(this.catalogSignal()));

  /**
   * Loads the layouts and the catalogue, once.
   *
   * @param force re-read even if a load already completed — what a setup page passes after a write
   * @throws whatever the requests throw; callers own the message an operator sees
   */
  async load(force = false): Promise<void> {
    if (this.loadingSignal() || (this.loadedSignal() && !force)) {
      return;
    }
    this.loadingSignal.set(true);
    try {
      const [profiles, catalog] = await Promise.all([this.api.controlProfiles(), this.api.controlCatalog()]);
      this.profilesSignal.set(profiles);
      this.catalogSignal.set(catalog);
      this.loadedSignal.set(true);
    } finally {
      this.loadingSignal.set(false);
    }
  }

  /** Starts a copy of the built-in for a vehicle kind, and returns it for the caller to select. */
  async create(request: CreateControlProfileRequest): Promise<ControlProfile> {
    const created = await this.api.createControlProfile(request);
    await this.reload();
    return created;
  }

  /** Replaces one saved layout wholesale (the endpoint takes no partial write — see its DTO). */
  async update(id: string, request: UpdateControlProfileRequest): Promise<ControlProfile> {
    const updated = await this.api.updateControlProfile(id, request);
    await this.reload();
    return updated;
  }

  /** Makes one layout the one a session on its vehicle kind engages with. */
  async activate(id: string): Promise<void> {
    await this.api.activateControlProfile(id);
    await this.reload();
  }

  async delete(id: string): Promise<void> {
    await this.api.deleteControlProfile(id);
    await this.reload();
  }

  private async reload(): Promise<void> {
    this.profilesSignal.set(await this.api.controlProfiles());
  }
}
