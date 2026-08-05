import { Injectable, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { UndoToastService } from '../../shared/ui/undo-toast.service';
import { describeHttpError } from '../../core/api-error';
import {
  type AssetDetails,
  type Category,
  type Device,
  type SettableLifecycleState,
} from '../../core/api/models';
import { isSimulatedAsset, mapSimulatedDevices, type SimulatedDeviceInfo } from './simulate-logic';
import {
  RESTORE_TARGET_STATE,
  buildCreateAssetRequestForDevice,
  buildDeviceRenameEdit,
  buildWarehouseRows,
  deriveCategoryOptions,
  filterRowsByArchived,
  findWarehouseRowById,
  mapDeviceOwners,
  searchWarehouseRowsByQuery,
  type CategoryOption,
  type DeviceOwner,
  type WarehouseRow,
} from './devices-page-logic';

/**
 * `DevicesPage`'s facade (docs/UI-ARCHITECTURE-PLAN.md) — owns every store/service injection, the
 * warehouse-row read-model (search + `showArchived`, per the plan's own explicit "moves into the
 * facade" call-out for this exact toggle), and every command for the `/devices` table, so the page
 * component itself only injects this class.
 *
 * No behavior change from the pre-facade page: every HTTP call, toast, and silent-degrade path below
 * is carried over verbatim, just relocated.
 *
 * **Wave 3 addition (docs/NAV-IA-REDESIGN-PLAN.md §2.4, docs/design/06-devices.md)**: the `?sel=`-
 * addressable two-pane selection (`selectedId`/`selectedRow`/`selectRow`/`clearSelection`) and the
 * detail panel's clipboard copy affordance (`copyToClipboard`) — everything else predates this wave.
 *
 * **docs/VISUAL-REFRESH-PLAN.md W2** — no facade changes; the merged state indicator
 * (`devices-page-logic.ts#describeDeviceState`) is a pure read of fields `WarehouseRow` already
 * carries, called straight from `DevicesPage`.
 */
@Injectable()
export class DevicesFacade {
  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly undoToast = inject(UndoToastService);
  private readonly router = inject(Router);
  /** Scoped to this page's own route (`DevicesFacade` is provided in `DevicesPage`'s own
   *  `providers`) — see `AssetsFacade`'s identical field for why this is what `selectRow`/
   *  `clearSelection` anchor their `relativeTo` on. */
  private readonly route = inject(ActivatedRoute);

  readonly fleet = inject(FleetStore);
  readonly settings = inject(SettingsStore);

  readonly busyDeviceId = signal<string | null>(null);

  // --- Search ------------------------------------------------------------------------------------
  readonly searchQuery = signal('');

  // --- Warehouse (docs/CYCLES-PLAN.md §8; kebab menus + poka-yoke docs/UX-REWORK-PLAN.md §U-a item 7, §U-a2) -

  readonly showArchived = signal(false);
  /** Populated only while `showArchived` is on — `includeDeleted=true` returns *every* device. */
  readonly allDevicesIncludingArchived = signal<readonly Device[]>([]);
  /**
   * Every asset the page has loaded, as full `AssetDetails` — needed for the table's "owned by"
   * column, the "Simulated" chip/stop action, and the assign/promote pickers' asset options.
   */
  readonly assets = signal<readonly AssetDetails[]>([]);
  /** deviceId → owning asset, across every asset the page has loaded (not just simulated ones). */
  readonly deviceOwners = signal<ReadonlyMap<string, DeviceOwner>>(new Map());

  /** deviceId → the simulated asset owning it; empty for a device that isn't simulated. */
  readonly simulatedDevices = signal<ReadonlyMap<string, SimulatedDeviceInfo>>(new Map());
  readonly busySimulatedAssetId = signal<string | null>(null);

  private readonly warehouseDevices = computed<readonly Device[]>(() =>
    this.showArchived() ? this.allDevicesIncludingArchived() : this.fleet.devices(),
  );

  /** Every loaded device as a row, before the search box narrows it — `warehouseRows` (the table) and
   *  `selectedRow` (the two-pane detail panel) both read this, so a row found in one is
   *  reference-equal to the one found in the other. */
  private readonly allRows = computed<readonly WarehouseRow[]>(() =>
    filterRowsByArchived(buildWarehouseRows(this.warehouseDevices(), this.deviceOwners(), this.fleet.liveDeviceIds()), this.showArchived()),
  );

  readonly warehouseRows = computed<readonly WarehouseRow[]>(() => searchWarehouseRowsByQuery(this.allRows(), this.searchQuery()));

  /** `true` once at least one device has loaded — distinguishes "no devices exist yet" from
   *  "search matched nothing" for the empty state. */
  readonly hasAnyDevices = computed(() => this.warehouseDevices().length > 0);

  // --- Two-pane selection (docs/NAV-IA-REDESIGN-PLAN.md §2.4, docs/design/06-devices.md) ----------
  // `DevicesPage`'s own constructor `effect()` forwards its route-bound `sel` input straight into
  // this signal on every change (same "only a component can receive a route input" split `addSource`
  // already documents). Read against `allRows`, not the search-narrowed `warehouseRows`, so typing
  // into the search box never silently closes an already-open selection.
  readonly selectedId = signal<string | undefined>(undefined);
  readonly selectedRow = computed<WarehouseRow | undefined>(() => findWarehouseRowById(this.allRows(), this.selectedId()));

  /** A row was clicked/activated — opens the detail panel and mirrors the choice into `?sel=` so it
   *  survives refresh, Back and sharing. `replaceUrl: true` — selecting a row is browsing, not a
   *  navigation Back should undo one step at a time for. */
  selectRow(deviceId: string): void {
    this.selectedId.set(deviceId);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { sel: deviceId }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  /** The pane's close button / Esc / scrim click (`TwoPane`'s own `detailClose` output) — the pane
   *  never closes itself, so every dismissal path reaches here. */
  clearSelection(): void {
    this.selectedId.set(undefined);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { sel: null }, queryParamsHandling: 'merge', replaceUrl: true });
  }

  /**
   * The detail panel's copy affordances for the full source URI and the device UUID
   * (docs/design/06-devices.md — "the full source URI, currently cut mid-path with no way to see it")
   * — mirrors `shared/player/stream-info-panel.ts#copyViewUrl`'s own `navigator.clipboard` + toast
   * idiom exactly (that panel's the one other place this app copies a value to the clipboard). A
   * failed write (clipboard permission denied, insecure context) degrades to an error toast pointing
   * back at the value that's still on-screen — never a silent no-op, never a crash.
   */
  async copyToClipboard(value: string, what: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(value);
      this.toasts.ok(`Copied ${what}.`);
    } catch {
      this.toasts.error(`Could not copy automatically — the ${what} is still shown in the panel.`);
    }
  }

  /** Non-archived assets are always valid assign targets — a device's ownership is the only rule. */
  readonly assignableAssets = computed(() => this.assets().filter((asset) => (asset.lifecycle ?? 'ACTIVE') !== 'DELETED'));

  /** The backend's own defined-category list (`VisionApi.listCategories()`) — the picker's seed;
   *  loaded once, alongside `refreshWarehouse()`, in the constructor below. */
  private readonly categories = signal<readonly Category[]>([]);

  /** The category picker's options — every backend-defined category, plus any in-use category a
   *  loaded asset carries that isn't in that list yet (`deriveCategoryOptions`'s own union rule). */
  readonly categoryOptions = computed<readonly CategoryOption[]>(() => deriveCategoryOptions(this.assets(), this.categories()));

  constructor() {
    void this.refreshWarehouse();
    void this.loadCategories();
  }

  /** Best-effort like `refreshWarehouseAssets` — a failed fetch just leaves `categoryOptions` derived
   *  from in-use asset categories alone, same silent-degrade idiom as everywhere else in this facade. */
  private async loadCategories(): Promise<void> {
    try {
      this.categories.set(await this.api.listCategories());
    } catch {
      // Silent-degrade — see doc comment above.
    }
  }

  /** "+ Add source" (docs/UX-REWORK-PLAN.md §U-d) — the onboarding wizard is the only way in now;
   *  also where `addSource`'s query-param redirect (`devices.ts`'s own constructor) lands. */
  goToAddSource(): Promise<boolean> {
    return this.router.navigate(['/add-source']);
  }

  /**
   * Assigns `device` — the existing, already-registered device, by id — to a brand-new asset via
   * `POST /api/assets`'s `deviceIds` field. No new `Device` row, no archive step: `device` keeps its
   * own id/history/connection details, just under a new owner. Returns `true` on success so the
   * caller can close its own create-asset panel — the navigate is deliberately fire-and-forget
   * (`void`, not `await`ed) so that close happens at the same point the pre-facade page's own
   * `createAssetFor.set(null)` did (immediately after the create call resolves, not after the whole
   * navigation completes too).
   */
  async createAssetFromDevice(device: Device, displayName: string, category: string): Promise<boolean> {
    try {
      const request = buildCreateAssetRequestForDevice(device, displayName, category);
      const created = await this.api.createAsset(request);
      this.toasts.ok(`Promoted to asset "${created.displayName}".`);
      void this.router.navigate(['/assets', created.assetId]);
      return true;
    } catch (error) {
      this.toasts.error(describeHttpError(error));
      return false;
    }
  }

  /**
   * Archive executes immediately, no confirm dialog (docs/UX-REWORK-PLAN.md §U-a2 item 3b) — bypasses
   * `FleetStore.deleteDevice` so this page can attach its own Undo action (docs/OPS-CORE-PLAN.md
   * §Q2). Undo reuses the existing explicit-Restore path (`setDeviceLifecycle`).
   */
  async archiveDeviceNow(device: Device): Promise<void> {
    this.busyDeviceId.set(device.id);
    try {
      await this.api.deleteDevice(device.id);
      this.undoToast.showUndo(`Archived "${device.name}".`, () => void this.setDeviceLifecycle(device, RESTORE_TARGET_STATE));
      await Promise.all([this.fleet.refresh({ quiet: true }), this.refreshWarehouse()]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  /** Deactivate also offers an Undo toast (docs/OPS-CORE-PLAN.md §Q2) — same bypass-`FleetStore`
   *  reasoning as `archiveDeviceNow`. */
  async deactivateDeviceNow(device: Device): Promise<void> {
    this.busyDeviceId.set(device.id);
    try {
      await this.api.setDeviceState(device.id, 'DEACTIVATED');
      this.undoToast.showUndo(`Deactivated "${device.name}".`, () => void this.setDeviceLifecycle(device, 'ACTIVE'));
      await Promise.all([this.fleet.refresh({ quiet: true }), this.refreshWarehouse()]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  async setDeviceLifecycle(device: Device, state: SettableLifecycleState): Promise<void> {
    await this.runDeviceAction(device.id, () => this.fleet.setDeviceState(device.id, state));
  }

  /** `true` = ok for the caller to close its own inline row. */
  async renameDevice(device: Device, newName: string): Promise<boolean> {
    const edit = buildDeviceRenameEdit(newName, device);
    if (Object.keys(edit).length === 0) {
      return true;
    }
    let success = false;
    await this.runDeviceAction(device.id, async () => {
      success = !!(await this.fleet.updateDevice(device.id, edit));
    });
    return success;
  }

  /** `true` = ok for the caller to close its own inline row. */
  async assignDevice(device: Device, assetId: string): Promise<boolean> {
    let success = false;
    await this.runDeviceAction(device.id, async () => {
      success = !!(await this.fleet.assignDevice(assetId, device.id));
    });
    return success;
  }

  async unassignDevice(device: Device, owner: DeviceOwner): Promise<void> {
    await this.runDeviceAction(device.id, () => this.fleet.unassignDevice(owner.assetId, device.id));
  }

  /** Runs a device mutation with the row's busy indicator, then re-derives the warehouse view. */
  private async runDeviceAction(deviceId: string, action: () => Promise<unknown>): Promise<void> {
    this.busyDeviceId.set(deviceId);
    try {
      await action();
      await this.refreshWarehouse();
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  async toggleShowArchived(): Promise<void> {
    this.showArchived.update((value) => !value);
    await this.refreshWarehouse();
  }

  /**
   * Re-reads whatever the warehouse view currently needs: the archived-inclusive device list
   * (only while `showArchived` is on — it's a second, page-local fetch that deliberately never
   * touches `FleetStore`'s own `devicesSignal`, so Wall/Live keep seeing only non-archived
   * devices regardless of what this page's toggle is set to) and every asset plus its resolved
   * devices (for the "owned by" column and the "Simulated" chip alike).
   */
  private async refreshWarehouse(): Promise<void> {
    await Promise.all([
      this.showArchived() ? this.refreshArchivedDevices() : Promise.resolve(),
      this.refreshWarehouseAssets(),
    ]);
  }

  private async refreshArchivedDevices(): Promise<void> {
    const devices = await this.fleet.listDevicesIncludingArchived();
    if (devices) {
      this.allDevicesIncludingArchived.set(devices);
    }
  }

  /**
   * Loads every asset (respecting `showArchived`) plus its resolved devices, then stores the full
   * `AssetDetails` list — feeds `deviceOwners` (`mapDeviceOwners`, the "owned by" column + unassign
   * action) and `simulatedDevices` (`mapSimulatedDevices`, filtered). Best-effort like
   * `TelemetryStore`'s own asset lookups: this is enrichment for already-visible rows, not a
   * user-initiated action, so a failure degrades silently rather than raising a toast.
   */
  private async refreshWarehouseAssets(): Promise<void> {
    try {
      const summaries = this.showArchived() ? await this.fleet.listAssetsIncludingArchived() : await this.api.listAssets();
      if (!summaries) {
        return; // failure already toasted by FleetStore.run() (only reachable when showArchived)
      }

      const details = await Promise.all(summaries.map((asset) => this.api.getAsset(asset.assetId)));
      this.assets.set(details);
      this.deviceOwners.set(mapDeviceOwners(details));
      this.simulatedDevices.set(mapSimulatedDevices(details.filter(isSimulatedAsset)));
    } catch {
      // Silent-degrade — see doc comment above.
    }
  }

  /** The page's "Refresh" button re-reads devices/streams *and* the whole warehouse view. */
  async refreshAll(): Promise<void> {
    await Promise.all([this.fleet.refresh(), this.refreshWarehouse()]);
  }

  async stopSimulatedAsset(info: SimulatedDeviceInfo): Promise<void> {
    this.busySimulatedAssetId.set(info.assetId);
    try {
      const stopped = await this.fleet.stopSimulation(info.assetId);
      if (stopped) {
        this.toasts.ok(`Stopped simulation "${info.displayName}".`);
        await this.refreshWarehouse();
      }
    } finally {
      this.busySimulatedAssetId.set(null);
    }
  }

  // --- Stream actions (Advanced table rows) -------------------------------------------------

  async start(device: Device): Promise<void> {
    this.busyDeviceId.set(device.id);
    try {
      const result = await this.fleet.start(device.id, this.settings.effective());
      if (result) {
        await this.router.navigate(['/live', device.id]);
      }
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  async stop(device: Device): Promise<void> {
    const stream = this.fleet.streamFor(device.id);
    if (!stream) {
      return;
    }
    this.busyDeviceId.set(device.id);
    try {
      await this.fleet.stop(stream.streamId);
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  watch(device: Device): Promise<boolean> {
    return this.router.navigate(['/live', device.id]);
  }
}
