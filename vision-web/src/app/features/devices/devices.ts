import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { VisionApi } from '../../core/api/vision-api';
import { FleetStore } from '../../core/fleet/fleet-store';
import { SettingsStore } from '../../core/settings/settings-store';
import { ToastService } from '../../core/toast.service';
import { UndoToastService } from '../../shared/ui/undo-toast.service';
import { describeHttpError } from '../../core/api-error';
import { Icon } from '../../shared/ui/icon';
import {
  type AssetDetails,
  type Device,
  type SettableLifecycleState,
} from '../../core/api/models';
import { isSimulatedAsset, mapSimulatedDevices, type SimulatedDeviceInfo } from './simulate-logic';
import {
  DEVICE_ACTION_LABELS,
  RESTORE_TARGET_STATE,
  buildCreateAssetRequestForDevice,
  buildDeviceRenameEdit,
  buildWarehouseRows,
  deriveCategoryOptions,
  filterRowsByArchived,
  mapDeviceOwners,
  reasonedDeviceActions,
  searchWarehouseRowsByQuery,
  type ActionAvailability,
  type CategoryOption,
  type DeviceLifecycleAction,
  type DeviceOwner,
  type WarehouseRow,
} from './devices-page-logic';

/** "Create asset from this device" renamed to its outcome (docs/UX-REWORK-PLAN.md §U-a2 §3). */
const PROMOTE_TO_ASSET_LABEL = 'Promote to asset…';

/** List = the table, grid = device cards — both read the exact same `warehouseRows()`. */
type DeviceViewMode = 'list' | 'grid';

/**
 * The Devices page (`/devices`) — the raw device table/grid: search, lifecycle actions, the
 * archived toggle, and the register/discover/simulate-adjacent "+ Add source"/"Promote to asset…"
 * flows. Split out of the old combined Devices/Warehouse page (docs/CYCLES-PLAN.md §11's asset-first
 * list moved wholesale to `features/assets/**`, and `/warehouse` itself became a two-tile launcher —
 * see `features/assets/assets.ts`/`features/warehouse/warehouse.ts`'s own class doc comments) once
 * Assets and Devices earned separate pages. This page is what's left once "browse assets" moved out:
 * the low-level device table (docs/UX-REWORK-PLAN.md §U-d's old "Advanced (raw devices)" section,
 * no longer collapsed — it's this page's entire job now), plus search and a list/grid view toggle
 * (new this cycle) on top of it.
 */
@Component({
  selector: 'vision-devices',
  imports: [FormsModule, Icon],
  templateUrl: './devices.html',
  styleUrl: './devices.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DevicesPage {
  /**
   * `?addSource=` — redirects straight to the onboarding wizard (docs/UX-REWORK-PLAN.md §U-d;
   * originally docs/MVP3-PLAN.md §C-b: the Fly cockpit's empty-state picker links here as
   * `/devices?addSource=1` rather than a bare `/devices`). Kept for link-compatibility — Fly's own
   * link (`features/fly/fly.html`, out of this task's scope) is untouched and still works, it just
   * now bounces through this page into `/add-source` instead of opening an inline card on this one.
   * Binds by name like every other query-param input in this app (`FlyPage.requestedAssetId`/
   * `watch`) — no route change needed. Any non-empty value redirects; the value itself is never read.
   */
  readonly addSource = input<string | undefined>(undefined);

  private readonly api = inject(VisionApi);
  private readonly toasts = inject(ToastService);
  private readonly undoToast = inject(UndoToastService);
  private readonly router = inject(Router);

  protected readonly fleet = inject(FleetStore);
  protected readonly settings = inject(SettingsStore);

  protected readonly promoteToAssetLabel = PROMOTE_TO_ASSET_LABEL;

  protected readonly busyDeviceId = signal<string | null>(null);

  // --- Search + view toggle (new this cycle) --------------------------------------------------
  protected readonly searchQuery = signal('');
  protected readonly viewMode = signal<DeviceViewMode>('list');

  protected setViewMode(mode: DeviceViewMode): void {
    this.viewMode.set(mode);
  }

  // --- Warehouse (docs/CYCLES-PLAN.md §8; kebab menus + poka-yoke docs/UX-REWORK-PLAN.md §U-a item 7, §U-a2) -
  // Device lifecycle: rename, activate/deactivate, archive (soft delete)/restore, and
  // device↔asset assignment. Every mutation goes through `FleetStore`'s `run()`-wrapped thin
  // wrappers (mirroring how C4 added `simulate()`) so a 404 — CW-a, the backend half, is not
  // live while this lands — degrades to exactly one toast, never a broken page. Archive is the one
  // exception (see `archiveDeviceNow` below): it bypasses `FleetStore` on purpose so this page can
  // attach an Undo action to its own toast instead of `FleetStore`'s plain one.

  protected readonly showArchived = signal(false);
  /** Populated only while `showArchived` is on — `includeDeleted=true` returns *every* device. */
  protected readonly allDevicesIncludingArchived = signal<readonly Device[]>([]);
  /**
   * Every asset the page has loaded, as full `AssetDetails` — needed for the table's "owned by"
   * column, the "Simulated" chip/stop action, and the assign/promote pickers' asset options.
   */
  protected readonly assets = signal<readonly AssetDetails[]>([]);
  /** deviceId → owning asset, across every asset the page has loaded (not just simulated ones). */
  protected readonly deviceOwners = signal<ReadonlyMap<string, DeviceOwner>>(new Map());

  /**
   * One inline row open at a time per device — a rename form or an assign picker; both need the
   * user to actually type/pick something, unlike Archive (docs/UX-REWORK-PLAN.md §U-a2 item 3b —
   * "Undo over confirm"), which now fires immediately from the kebab menu with no inline step at
   * all — see `archiveDeviceNow` below.
   */
  protected readonly rowAction = signal<{ deviceId: string; mode: 'rename' | 'assign' } | null>(
    null,
  );
  protected readonly renameDraft = signal('');
  protected readonly assignDraft = signal('');

  /** deviceId → the simulated asset owning it; empty for a device that isn't simulated. */
  protected readonly simulatedDevices = signal<ReadonlyMap<string, SimulatedDeviceInfo>>(new Map());
  protected readonly busySimulatedAssetId = signal<string | null>(null);

  protected simulatedInfo(device: Device): SimulatedDeviceInfo | undefined {
    return this.simulatedDevices().get(device.id);
  }

  private readonly warehouseDevices = computed<readonly Device[]>(() =>
    this.showArchived() ? this.allDevicesIncludingArchived() : this.fleet.devices(),
  );

  protected readonly warehouseRows = computed<readonly WarehouseRow[]>(() =>
    searchWarehouseRowsByQuery(
      filterRowsByArchived(
        buildWarehouseRows(this.warehouseDevices(), this.deviceOwners(), this.fleet.liveDeviceIds()),
        this.showArchived(),
      ),
      this.searchQuery(),
    ),
  );

  /** `true` once at least one device has loaded — distinguishes "no devices exist yet" from
   *  "search matched nothing" for the empty state. */
  protected readonly hasAnyDevices = computed(() => this.warehouseDevices().length > 0);

  /** "+ Add source" (docs/UX-REWORK-PLAN.md §U-d) — the onboarding wizard is the only way in now. */
  protected goToAddSource(): Promise<boolean> {
    return this.router.navigate(['/add-source']);
  }

  protected clearSearch(): void {
    this.searchQuery.set('');
  }

  /** Non-archived assets are always valid assign targets — a device's ownership is the only rule. */
  protected readonly assignableAssets = computed(() =>
    this.assets().filter((asset) => (asset.lifecycle ?? 'ACTIVE') !== 'DELETED'),
  );

  // --- Create asset from a device (docs/UX-QUICKWINS-PLAN.md QF-2's orphaned-device quick fix) --
  // Reached from any row whose `owner` is unassigned — surgery for a device that already exists
  // (docs/UX-REWORK-PLAN.md §U-d: "'Advanced (raw devices)' keeps working for surgery but is no
  // longer the only outcome" — the onboarding wizard is the outcome for a *brand-new* source; this
  // stays for an existing orphaned one).

  protected readonly createAssetFor = signal<Device | null>(null);
  protected readonly createAssetName = signal('');
  protected readonly createAssetCategory = signal('');
  protected readonly createAssetSubmitting = signal(false);

  /** The category picker's options — real, in-use categories when any asset has been loaded, else a small default set. */
  protected readonly categoryOptions = computed<readonly CategoryOption[]>(() => deriveCategoryOptions(this.assets()));

  protected readonly canSubmitCreateAsset = computed(
    () => !this.createAssetSubmitting() && this.createAssetCategory().trim().length > 0,
  );

  protected openCreateAssetFor(device: Device): void {
    this.createAssetFor.set(device);
    this.createAssetName.set(device.name);
    this.createAssetCategory.set(this.categoryOptions()[0]?.slug ?? '');
  }

  protected cancelCreateAsset(): void {
    this.createAssetFor.set(null);
  }

  /**
   * Assigns `device` — the existing, already-registered device, by id — to a brand-new asset via
   * `POST /api/assets`'s `deviceIds` field (docs/REALTIME-PLAN.md §4's backend follow-up batch —
   * see `buildCreateAssetRequestForDevice`'s own doc comment). No new `Device` row, no archive
   * step: `device` keeps its own id/history/connection details, just under a new owner. Navigates
   * to the new asset's detail page on success.
   */
  protected async confirmCreateAsset(): Promise<void> {
    const device = this.createAssetFor();
    if (!device || !this.canSubmitCreateAsset()) {
      return;
    }
    this.createAssetSubmitting.set(true);
    try {
      const request = buildCreateAssetRequestForDevice(
        device,
        this.createAssetName(),
        this.createAssetCategory().trim(),
      );
      const created = await this.api.createAsset(request);
      this.createAssetFor.set(null);
      this.toasts.ok(`Promoted to asset "${created.displayName}".`);
      await this.router.navigate(['/assets', created.assetId]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.createAssetSubmitting.set(false);
    }
  }

  /**
   * The per-row kebab menu (docs/UX-REWORK-PLAN.md §U-a item 7): every device lifecycle action,
   * reasoned (item 3a) — `row.owner?.deviceCount` is what lets Unassign disable itself *before* the
   * click when this device is its asset's only one, instead of only after the backend's own 409
   * (see `reasonedDeviceActions`'s own doc comment for the verified backend rule).
   */
  protected deviceActionsFor(row: WarehouseRow): readonly ActionAvailability<DeviceLifecycleAction>[] {
    return reasonedDeviceActions(row.lifecycle, !!row.owner, row.owner?.deviceCount);
  }

  protected lifecycleLabel(state: WarehouseRow['lifecycle']): string {
    switch (state) {
      case 'ACTIVE':
        return 'Active';
      case 'DEACTIVATED':
        return 'Deactivated';
      case 'DELETED':
        return 'Archived';
    }
  }

  protected deviceActionLabel(action: DeviceLifecycleAction): string {
    return DEVICE_ACTION_LABELS[action];
  }

  protected onDeviceAction(row: WarehouseRow, action: DeviceLifecycleAction): void {
    switch (action) {
      case 'rename':
        this.rowAction.set({ deviceId: row.device.id, mode: 'rename' });
        this.renameDraft.set(row.device.name);
        break;
      case 'activate':
        void this.setDeviceLifecycle(row.device, 'ACTIVE');
        break;
      case 'deactivate':
        void this.deactivateDeviceNow(row.device);
        break;
      case 'archive':
        void this.archiveDeviceNow(row.device);
        break;
      case 'restore':
        void this.setDeviceLifecycle(row.device, RESTORE_TARGET_STATE);
        break;
      case 'assign':
        this.rowAction.set({ deviceId: row.device.id, mode: 'assign' });
        this.assignDraft.set('');
        break;
      case 'unassign':
        if (row.owner) {
          void this.unassignDevice(row.device, row.owner);
        }
        break;
    }
  }

  /**
   * Archive executes immediately, no confirm dialog (docs/UX-REWORK-PLAN.md §U-a2 item 3b) — bypasses
   * `FleetStore.deleteDevice` so this page can attach its own Undo action (docs/OPS-CORE-PLAN.md
   * §Q2). Undo reuses the existing explicit-Restore path (`setDeviceLifecycle`/`fleet.setDeviceState`)
   * rather than a bespoke restore method — restoring is restoring, whichever button asked for it.
   */
  protected async archiveDeviceNow(device: Device): Promise<void> {
    this.busyDeviceId.set(device.id);
    try {
      await this.api.deleteDevice(device.id);
      this.undoToast.showUndo(`Archived "${device.name}".`, () =>
        void this.setDeviceLifecycle(device, RESTORE_TARGET_STATE),
      );
      await Promise.all([this.fleet.refresh({ quiet: true }), this.refreshWarehouse()]);
    } catch (error) {
      this.toasts.error(describeHttpError(error));
    } finally {
      this.busyDeviceId.set(null);
    }
  }

  /**
   * Deactivate now offers an Undo toast too (docs/OPS-CORE-PLAN.md §Q2), not just Archive — until
   * this landed, deactivating went straight through `setDeviceLifecycle`/`FleetStore.setDeviceState`,
   * whose own plain, action-less confirmation auto-dismissed with no way back short of re-opening
   * this row's kebab. Bypasses `FleetStore.setDeviceState` for the same reason `archiveDeviceNow`
   * bypasses `FleetStore.deleteDevice` — its toast has no Undo action to offer. `activate`/the
   * explicit `restore` kebab entry are unchanged (re-activating isn't the kind of silent-immediacy
   * step this item is about).
   */
  protected async deactivateDeviceNow(device: Device): Promise<void> {
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

  /** `null` when no inline row is open for this device — lets the template use one `@switch`. */
  protected rowActionMode(deviceId: string): 'rename' | 'assign' | null {
    const active = this.rowAction();
    return active && active.deviceId === deviceId ? active.mode : null;
  }

  protected cancelRowAction(): void {
    this.rowAction.set(null);
  }

  protected async confirmRename(device: Device): Promise<void> {
    const edit = buildDeviceRenameEdit(this.renameDraft(), device);
    if (Object.keys(edit).length === 0) {
      this.rowAction.set(null);
      return;
    }
    await this.runDeviceAction(device.id, async () => {
      const updated = await this.fleet.updateDevice(device.id, edit);
      if (updated) {
        this.rowAction.set(null);
      }
    });
  }

  protected async confirmAssign(device: Device): Promise<void> {
    const assetId = this.assignDraft();
    if (!assetId) {
      return;
    }
    await this.runDeviceAction(device.id, async () => {
      const updated = await this.fleet.assignDevice(assetId, device.id);
      if (updated) {
        this.rowAction.set(null);
      }
    });
  }

  protected async unassignDevice(device: Device, owner: DeviceOwner): Promise<void> {
    await this.runDeviceAction(device.id, () => this.fleet.unassignDevice(owner.assetId, device.id));
  }

  private async setDeviceLifecycle(device: Device, state: SettableLifecycleState): Promise<void> {
    await this.runDeviceAction(device.id, () => this.fleet.setDeviceState(device.id, state));
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

  protected async toggleShowArchived(): Promise<void> {
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
      const summaries = this.showArchived()
        ? await this.fleet.listAssetsIncludingArchived()
        : await this.api.listAssets();
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

  constructor() {
    void this.refreshWarehouse();
    if (this.addSource()) {
      void this.router.navigate(['/add-source']);
    }
  }

  // --- Simulated assets: stop action (display-only mapping in ./simulate-logic.ts) -------------

  /** The page's "Refresh" button re-reads devices/streams *and* the whole warehouse view. */
  protected async refreshAll(): Promise<void> {
    await Promise.all([this.fleet.refresh(), this.refreshWarehouse()]);
  }

  protected async stopSimulatedAsset(info: SimulatedDeviceInfo): Promise<void> {
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

  protected async start(device: Device): Promise<void> {
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

  protected async stop(device: Device): Promise<void> {
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

  protected watch(device: Device): Promise<boolean> {
    return this.router.navigate(['/live', device.id]);
  }
}
