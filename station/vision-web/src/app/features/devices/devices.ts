import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { KebabMenu } from '../../shared/ui/kebab-menu';
import { EmptyState } from '../../shared/ui/empty-state';
import { PageBar } from '../../shared/ui/page-bar/page-bar';
import { TwoPane } from '../../shared/ui/two-pane/two-pane';
import { type Device } from '../../core/api/models';
import type { SimulatedDeviceInfo } from './simulate-logic';
import { DevicesFacade } from './devices-facade';
import {
  DEVICE_ACTION_LABELS,
  RESTORE_TARGET_STATE,
  describeDeviceState,
  reasonedDeviceActions,
  type ActionAvailability,
  type DeviceLifecycleAction,
  type DeviceStateDescriptor,
  type WarehouseRow,
} from './devices-page-logic';

/** "Create asset from this device" renamed to its outcome (docs/plans/done/UX-REWORK-PLAN.md §U-a2 §3). */
const PROMOTE_TO_ASSET_LABEL = 'Promote to asset…';

/**
 * The Devices page (`/devices`) — the raw device table: search, lifecycle actions, the archived
 * toggle, and the register/discover/simulate-adjacent "+ Add source"/"Promote to asset…" flows. Split
 * out of the old combined Devices/Warehouse page (docs/main/CYCLES-PLAN.md §11's asset-first list moved
 * wholesale to `features/assets/**`, and `/warehouse` itself became a two-tile launcher — see
 * `features/assets/assets.ts`/`features/warehouse/warehouse.ts`'s own class doc comments) once Assets
 * and Devices earned separate pages.
 *
 * **Layered per docs/plans/done/UI-ARCHITECTURE-PLAN.md**: every store/service injection, the warehouse-row
 * read-model, and every HTTP-backed command lives in {@link DevicesFacade}. This component is left
 * holding only: the route-bound `addSource`/`sel` inputs (only a component can receive one); the
 * "one inline row open at a time" pointer (`rowAction`) and its drafts (`renameDraft`/`assignDraft`);
 * the create-asset panel's own open/cancel + drafts (`createAssetFor`/`createAssetName`/
 * `createAssetCategory`/`createAssetSubmitting`, submitting through the facade); and a handful of
 * pure, stateless label/action-list helpers.
 *
 * **`page-head` → `vision-page-bar`** (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.2, docs/extracts/design/06-devices.md):
 * both old subtitle sentences are deleted outright — "Devices" needs no explanation, and the second
 * ("Looking for an asset instead? …") was migration signage left over from the Assets/Devices split
 * that had already outlived its purpose. Search and the archived toggle move into `[pageBarFilters]`;
 * `+ Add source`/`Refresh` into `[pageBarActions]`. The card's own `Registered devices` header is
 * dropped too — it repeated the page title and the bar's own count chip now carries the number it
 * used to show.
 *
 * **Wave 3 — one action + a panel (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4, docs/extracts/design/06-devices.md)**:
 * the table's own action cell used to render two-to-four full-size buttons, wrapping onto a second
 * line on most rows (the design doc's own named problem). It now renders exactly one primary verb
 * (Watch live / Start stream, whichever applies) plus the kebab — every other action (Stop
 * stream/simulation, rename, lifecycle, assign/unassign, Promote to asset…) moved into the kebab
 * **and** the two-pane detail panel, so nothing that used to be one click away became two. The row
 * also drops its own UUID line (the protocol chip already identifies the source) and the `List | Grid`
 * toggle is gone outright — a grid of raw devices had no use case the table didn't already serve
 * better, and this task's own file-scope grep found nothing else in the app reading `viewMode`.
 * Selecting a row opens the panel via `?sel=<deviceId>` (the facade owns it) rather than navigating —
 * there was nowhere to navigate to before this wave; now there's a panel instead.
 */
@Component({
  selector: 'vision-devices',
  imports: [FormsModule, RouterLink, PageBar, KebabMenu, EmptyState, TwoPane],
  templateUrl: './devices.html',
  styleUrl: './devices.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [DevicesFacade],
})
export class DevicesPage {
  /**
   * `?addSource=` — redirects straight to the onboarding wizard (docs/plans/done/UX-REWORK-PLAN.md §U-d).
   * Any non-empty value redirects; the value itself is never read.
   */
  readonly addSource = input<string | undefined>(undefined);

  /**
   * `?sel=<deviceId>` (docs/plans/done/NAV-IA-REDESIGN-PLAN.md §2.4) — bound the same way `addSource` above is
   * (`withComponentInputBinding()`, `app.config.ts`); forwarded into the facade by the constructor
   * `effect()` below, since only a component can receive a route input.
   */
  readonly sel = input<string | undefined>(undefined);

  protected readonly facade = inject(DevicesFacade);

  protected readonly promoteToAssetLabel = PROMOTE_TO_ASSET_LABEL;

  /**
   * One inline row open at a time per device — a rename form or an assign picker; both need the
   * user to actually type/pick something, unlike Archive (docs/plans/done/UX-REWORK-PLAN.md §U-a2 item 3b —
   * "Undo over confirm"), which fires immediately from the kebab menu with no inline step at all.
   */
  protected readonly rowAction = signal<{ deviceId: string; mode: 'rename' | 'assign' } | null>(null);
  protected readonly renameDraft = signal('');
  protected readonly assignDraft = signal('');

  protected simulatedInfo(device: Device): SimulatedDeviceInfo | undefined {
    return this.facade.simulatedDevices().get(device.id);
  }

  /**
   * docs/plans/active/OPERATOR-UX-7-PLAN.md finding D1 — the names of the other active devices
   * sharing this one's exact protocol+uri, or `undefined` when it has the endpoint to itself.
   */
  protected endpointConflictNames(device: Device): readonly string[] | undefined {
    return this.facade.conflictingEndpoints().get(device.id);
  }

  /**
   * The table row/detail panel's one merged state indicator (docs/plans/done/VISUAL-REFRESH-PLAN.md F5 — "at
   * most one chip per row"): replaces the old separate Lifecycle + Live/Stopped chips. See
   * `devices-page-logic.ts#describeDeviceState`'s own doc comment for the priority order.
   */
  protected deviceState(row: WarehouseRow): DeviceStateDescriptor {
    return describeDeviceState(row);
  }

  protected clearSearch(): void {
    this.facade.searchQuery.set('');
  }

  // --- Create asset from a device (docs/plans/done/UX-QUICKWINS-PLAN.md QF-2's orphaned-device quick fix) --

  protected readonly createAssetFor = signal<Device | null>(null);
  protected readonly createAssetName = signal('');
  protected readonly createAssetCategory = signal('');
  protected readonly createAssetSubmitting = signal(false);

  protected readonly canSubmitCreateAsset = computed(() => !this.createAssetSubmitting() && this.createAssetCategory().trim().length > 0);

  protected openCreateAssetFor(device: Device): void {
    this.createAssetFor.set(device);
    this.createAssetName.set(device.name);
    this.createAssetCategory.set(this.facade.categoryOptions()[0]?.slug ?? '');
  }

  protected cancelCreateAsset(): void {
    this.createAssetFor.set(null);
  }

  protected async confirmCreateAsset(): Promise<void> {
    const device = this.createAssetFor();
    if (!device || !this.canSubmitCreateAsset()) {
      return;
    }
    this.createAssetSubmitting.set(true);
    try {
      const ok = await this.facade.createAssetFromDevice(device, this.createAssetName(), this.createAssetCategory().trim());
      if (ok) {
        this.createAssetFor.set(null);
      }
    } finally {
      this.createAssetSubmitting.set(false);
    }
  }

  /**
   * The per-row kebab menu (docs/plans/done/UX-REWORK-PLAN.md §U-a item 7): every device lifecycle action,
   * reasoned (item 3a) — `row.owner?.deviceCount` is what lets Unassign disable itself *before* the
   * click when this device is its asset's only one, instead of only after the backend's own 409.
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
        void this.facade.setDeviceLifecycle(row.device, 'ACTIVE');
        break;
      case 'deactivate':
        void this.facade.deactivateDeviceNow(row.device);
        break;
      case 'archive':
        void this.facade.archiveDeviceNow(row.device);
        break;
      case 'restore':
        void this.facade.setDeviceLifecycle(row.device, RESTORE_TARGET_STATE);
        break;
      case 'assign':
        this.rowAction.set({ deviceId: row.device.id, mode: 'assign' });
        this.assignDraft.set('');
        break;
      case 'unassign':
        if (row.owner) {
          void this.facade.unassignDevice(row.device, row.owner);
        }
        break;
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
    const ok = await this.facade.renameDevice(device, this.renameDraft());
    if (ok) {
      this.rowAction.set(null);
    }
  }

  protected async confirmAssign(device: Device): Promise<void> {
    const assetId = this.assignDraft();
    if (!assetId) {
      return;
    }
    const ok = await this.facade.assignDevice(device, assetId);
    if (ok) {
      this.rowAction.set(null);
    }
  }

  constructor() {
    if (this.addSource()) {
      void this.facade.goToAddSource();
    }

    // ?sel= round-trips through the facade's own selectedId signal — forwarded unconditionally
    // (including `undefined`), the same reasoning `AssetsPage`'s identical effect documents.
    effect(() => {
      this.facade.selectedId.set(this.sel());
    });
  }

  protected copyDeviceUri(device: Device): void {
    void this.facade.copyToClipboard(device.uri, 'source URI');
  }

  protected copyDeviceId(device: Device): void {
    void this.facade.copyToClipboard(device.id, 'device ID');
  }
}
