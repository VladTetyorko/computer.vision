import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LayersStore } from '../../../core/map-data/layers-store';
import { AuthFacade } from '../../../core/auth/auth-facade';
import { OrgFacade } from '../../../core/org/org-facade';
import {
  ACCESS_LEVELS,
  accessLevelLabel,
  canManage,
  layerKindLabel,
  removeGrant,
  upsertGrant,
} from '../../../core/map-data/layers-logic';
import type { BuiltinRow, LayerRow } from '../tactical-map/tactical-map-logic';
import type { MapLayerDef } from '../tile-cache/leaflet-loader';
import type { MapLayerId } from '../../../core/settings/settings-facade';
import { Icon } from '../../ui/icon';
import { Notice } from '../../ui/notice';
import type { AccessLevel, GrantSubjectType, LayerGrant, LayerKind, MapLayer } from '../../../core/api/models';

/** One option in either subject picker — users and groups reduced to the two fields the editor needs. */
interface SubjectOption {
  readonly id: string;
  readonly name: string;
}

/**
 * `<vision-layer-manager>` — create layers, rename/delete the ones you manage, and grant access to
 * users and groups (docs/plans/done/MAP-REWORK-PLAN.md §5.2's "layer manager"). Shared by the Fly cockpit's Map
 * drawer and Command's Layers panel.
 *
 * **Deviation from §5.2, flagged (updated by MAP-UX-RESEARCH.md M1).** The plan places this "from
 * the data-layer panel": the map's own built-in panel would grow a grants editor on MANAGE rows.
 * `<vision-tactical-map>`'s grants editor never happened for the reason still true today — two
 * subject pickers, a level select and a create-layer form do not fit a 14rem overlay pinned to a map
 * corner (frontend-style §7: overlay controls are *small* light cards) — so the *management* surface
 * stayed this sibling panel. What changed under M1: the map's own corner panel and this drawer were
 * **both** labeled "Layers", open at the same time, a few centimeters apart
 * (`docs/conclusions/MAP-UX-RESEARCH.md` §1.1) — not wrong individually, but the same word pointing at two
 * different things reads as exactly the "settings are overwhelming" complaint that research
 * document was written to chase down. Fix: the map's per-layer eye toggles (visibility) and basemap
 * picker now render as this drawer's own **"Show on map"** and **"Basemap"** sections, above
 * **"Manage layers"** (the create/rename/delete/grants content below, unchanged) — driven by
 * {@link builtinLayerRows}/{@link dataLayerRows}/{@link basemaps}/{@link activeBasemapId}, forwarded
 * by the host from its own `viewChild(TacticalMap)` (see `cockpit.ts`/`command.ts`). The map's own
 * corner panel still exists — relabeled "Basemap", `map` icon, basemap-only — so a viewer who only
 * wants to swap tiles doesn't have to open this drawer; but it no longer says "Layers", and no
 * capability moved without staying reachable somewhere. `hiddenLayers` itself is not duplicated
 * here: this component holds no view-visibility state of its own, only the rows the host's own
 * `TacticalMap` instance computed, and emits {@link toggleLayerVisibility}/{@link basemapChanged}
 * for the host to call straight back into that same instance's own `toggleLayer`/`setBasemap`.
 *
 * **What each viewer sees.** Every visible layer gets a row with its name, kind, the viewer's own
 * resolved access, and its mark/drawing counts. Rename, Delete and the Access editor render only on
 * rows where the server resolved MANAGE (§3); the COP layer additionally never offers rename or
 * delete, because the server refuses both — showing them would be a lie. Create is split the same
 * way: **Personal** is offered to anyone, **Team** only when this account actually has a group it
 * manages, since a PILOT's `POST` with `kind: TEAM` would always 403.
 *
 * **Honest degrade on the subject pickers.** `/api/users` and `/api/groups` are an admin surface — a
 * MANAGER who may legitimately grant access to their own layer can still get a 403 listing users.
 * `OrgFacade` is therefore loaded lazily and quietly (no toast) the first time an Access editor
 * opens, and when it comes back empty the editor says so and still lets existing grants be
 * re-levelled or removed, rather than pretending there is nobody to add or blocking the whole panel.
 *
 * **Dev parity** (`vision.auth.enabled=false`): the dev admin resolves to `scopeKind: 'UNBOUNDED'`,
 * so the Team-layer path resolves its group list from `OrgFacade.groups()` (unbounded for that
 * account) and every management control is available exactly as it is for a real admin — the app
 * behaves as before with zero auth.
 *
 * A non-routed presentational child, so it injects its root stores directly
 * (`architecture.spec.ts`'s own carve-out).
 */
@Component({
  selector: 'vision-layer-manager',
  imports: [FormsModule, Icon, Notice],
  templateUrl: './layer-manager.html',
  styleUrl: './layer-manager.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LayerManager {
  protected readonly layers = inject(LayersStore);
  private readonly auth = inject(AuthFacade);
  protected readonly org = inject(OrgFacade);

  constructor() {
    // ALWAYS-ON-FLOW-PLAN.md §4 Wave C3 — see `MarksPanel`'s identical constructor comment.
    this.layers.activate();
    inject(DestroyRef).onDestroy(() => this.layers.release());
  }

  // --- "Show on map" / "Basemap" (M1 fold) --------------------------------------------------------
  // These mirror `<vision-tactical-map>`'s own public `builtinLayerRows`/`dataRows`/`basemaps`/
  // `activeBasemapId` — the host reads its own map instance (`viewChild(TacticalMap)`) and passes
  // the current values straight through. `false`/`[]` defaults mean an unwired host (or a test)
  // just renders the "Manage layers" section as before — an honest empty degrade, not a crash.

  /** Whether a `TacticalMap` instance actually exists for the host to control right now (it may not — the map inset can be hidden, or Command can have zero assets). Gates the whole "Show on map"/"Basemap" section. */
  readonly mapAvailable = input(false);
  readonly builtinLayerRows = input<readonly BuiltinRow[]>([]);
  readonly dataLayerRows = input<readonly LayerRow[]>([]);
  readonly basemaps = input<readonly MapLayerDef[]>([]);
  readonly activeBasemapId = input<MapLayerId | null>(null);

  /**
   * `MapToolsCapabilities.layers` (`docs/plans/active/COMMAND-MAP-FLOW-PLAN.md` §3.2/§3.2.1) —
   * `true` (default, Command's `'manage'` tier) renders "Manage layers" (create/rename/delete/
   * grants) below "Show on map"/"Basemap"; `false` (`/fly`, `/live`, `/assets/:id` — the `'view'`
   * tier) hides that whole section, since administration was the research's own "wrong surface"
   * finding for every host but Command. `mapAvailable` stays the separate, orthogonal gate for
   * "Show on map"/"Basemap" — a host can offer management with no map instance to preview against,
   * or a preview with no management rights.
   */
  readonly manage = input(true);

  /** An eye toggle was clicked — the host forwards this to its `TacticalMap`'s own `toggleLayer`. */
  readonly toggleLayerVisibility = output<string>();
  /** A basemap was picked — the host forwards this to its `TacticalMap`'s own `setBasemap`. */
  readonly basemapChanged = output<MapLayerId>();

  protected readonly levels = ACCESS_LEVELS;
  protected readonly accessLevelLabel = accessLevelLabel;
  protected readonly layerKindLabel = layerKindLabel;
  protected readonly canManage = canManage;

  protected readonly busy = signal(false);

  // --- Create ------------------------------------------------------------------------------------

  /**
   * M2 (docs/conclusions/MAP-UX-RESEARCH.md): Fly's own host input, `true` on the cockpit's Map drawer,
   * left `false` (default) on Command — Command's Layers panel is a manager's whole job here, and
   * keeps the create flow unconditional. On Fly, a mid-flight pilot who isn't managing anything and
   * already has somewhere to put a mark has no reason to see a create-a-layer form by default; see
   * {@link showCreateTrigger}.
   */
  readonly compactCreate = input(false);

  /** Whether this viewer manages at least one visible layer already — the same MANAGE resolution the rename/delete/Access controls below gate on ({@link canManage}), rolled up across the whole list. */
  protected readonly managesAnyLayer = computed(() => this.layers.layers().some((layer) => canManage(layer)));

  /**
   * M2's actual gate. `compactCreate()` only ever *narrows* visibility, never adds a restriction
   * Command doesn't already avoid: hidden only when all three hold — this is the Fly host, the
   * viewer manages nothing yet, and at least one layer already exists for their marks to land on
   * (so hiding the trigger doesn't strand them with nowhere to contribute).
   */
  protected readonly showCreateTrigger = computed(
    () => !this.compactCreate() || this.managesAnyLayer() || this.layers.layers().length === 0,
  );

  protected readonly creating = signal(false);
  protected readonly newName = signal('');
  protected readonly newKind = signal<Exclude<LayerKind, 'COP'>>('PERSONAL');
  protected readonly newGroupId = signal('');

  /**
   * Groups this account may own a TEAM layer in. A MANAGER's own memberships are the honest answer
   * (`MeResponse.memberships` always ships with the session, no extra request); an ADMIN is
   * unbounded, so their list comes from `OrgFacade` once it has loaded, falling back to memberships
   * so the control still works before/without that admin-only fetch.
   */
  protected readonly teamGroups = computed<readonly SubjectOption[]>(() => {
    const me = this.auth.user();
    if (!me) {
      return [];
    }
    const managed = me.memberships
      .filter((membership) => membership.role !== 'PILOT')
      .map((membership) => ({ id: membership.groupId, name: membership.groupName }));
    if (this.auth.scopeKind() !== 'UNBOUNDED') {
      return managed;
    }
    const all = this.org.groups().map((group) => ({ id: group.id, name: group.name }));
    return all.length > 0 ? all : managed;
  });

  /** Hides the Team option entirely for an account whose `POST` would always 403 (§3's create rule). */
  protected readonly canCreateTeamLayer = computed(() => this.teamGroups().length > 0);

  protected readonly canSubmitCreate = computed(() => {
    if (this.newName().trim().length === 0 || this.busy()) {
      return false;
    }
    return this.newKind() === 'PERSONAL' || this.newGroupId().length > 0;
  });

  // --- Grants editor ------------------------------------------------------------------------------
  private readonly editingGrantsForSignal = signal<string | null>(null);
  /** Which layer's Access editor is open — at most one at a time, so the panel never becomes a wall of forms. */
  readonly editingGrantsFor = this.editingGrantsForSignal.asReadonly();

  /** The working copy of that layer's grants; `PUT` is wholesale, so this is the full list that will be sent. */
  protected readonly draftGrants = signal<readonly LayerGrant[]>([]);

  protected readonly newSubjectType = signal<GrantSubjectType>('USER');
  protected readonly newSubjectId = signal('');
  protected readonly newLevel = signal<AccessLevel>('VIEW');

  // --- Rename ------------------------------------------------------------------------------------
  private readonly renamingSignal = signal<string | null>(null);
  protected readonly renaming = this.renamingSignal.asReadonly();
  protected readonly renameText = signal('');

  // --- Delete confirm ------------------------------------------------------------------------------
  private readonly confirmingDeleteSignal = signal<string | null>(null);
  protected readonly confirmingDelete = this.confirmingDeleteSignal.asReadonly();

  /** The options for whichever subject picker is currently selected — the same shape either way. */
  protected readonly subjectOptions = computed<readonly SubjectOption[]>(() => {
    if (this.newSubjectType() === 'GROUP') {
      return this.org.groups().map((group) => ({ id: group.id, name: group.name }));
    }
    return this.org.users().map((user) => ({ id: user.userId, name: user.displayName }));
  });

  /** `true` when the org listing settled with nothing this account may read — the editor says so instead of showing an empty picker. */
  protected readonly subjectsUnavailable = computed(() => this.org.loaded() && this.subjectOptions().length === 0);

  /** The COP layer can never be renamed or deleted — the server refuses both (§3). */
  protected isSystemLayer(layer: MapLayer): boolean {
    return layer.kind === 'COP';
  }

  protected grantSubjectName(grant: LayerGrant): string {
    if (grant.subjectType === 'GROUP') {
      return this.org.groups().find((group) => group.id === grant.subjectId)?.name ?? grant.subjectId;
    }
    return this.org.users().find((user) => user.userId === grant.subjectId)?.displayName ?? grant.subjectId;
  }

  // --- Create ------------------------------------------------------------------------------------

  protected startCreate(): void {
    this.creating.set(true);
    this.newName.set('');
    this.newKind.set('PERSONAL');
    this.newGroupId.set('');
    this.ensureOrgLoaded();
  }

  protected cancelCreate(): void {
    this.creating.set(false);
  }

  protected setNewKind(kind: Exclude<LayerKind, 'COP'>): void {
    this.newKind.set(kind);
    if (kind === 'TEAM' && this.newGroupId() === '') {
      this.newGroupId.set(this.teamGroups()[0]?.id ?? '');
    }
  }

  protected async submitCreate(): Promise<void> {
    if (!this.canSubmitCreate()) {
      return;
    }
    const kind = this.newKind();
    await this.run(async () => {
      const created = await this.layers.create({
        name: this.newName().trim(),
        kind,
        groupId: kind === 'TEAM' ? this.newGroupId() : undefined,
      });
      if (created) {
        this.creating.set(false);
      }
    });
  }

  // --- Rename ------------------------------------------------------------------------------------

  protected startRename(layer: MapLayer): void {
    this.renamingSignal.set(layer.layerId);
    this.renameText.set(layer.name);
  }

  protected cancelRename(): void {
    this.renamingSignal.set(null);
  }

  protected async submitRename(layer: MapLayer): Promise<void> {
    const name = this.renameText().trim();
    if (name.length === 0 || name === layer.name) {
      this.renamingSignal.set(null);
      return;
    }
    await this.run(async () => {
      if (await this.layers.rename(layer.layerId, name)) {
        this.renamingSignal.set(null);
      }
    });
  }

  // --- Delete ------------------------------------------------------------------------------------

  /**
   * Two steps, not an undo toast: deleting a layer cascades its marks and drawings server-side, and
   * re-creating the layer would not bring those back — so this asks first rather than promising a
   * restore it cannot deliver (`LayersStore.remove`'s own doc comment).
   */
  protected requestDelete(layer: MapLayer): void {
    this.confirmingDeleteSignal.set(layer.layerId);
  }

  protected cancelDelete(): void {
    this.confirmingDeleteSignal.set(null);
  }

  protected async confirmDelete(layer: MapLayer): Promise<void> {
    await this.run(async () => {
      await this.layers.remove(layer.layerId);
      this.confirmingDeleteSignal.set(null);
    });
  }

  // --- Grants ------------------------------------------------------------------------------------

  protected toggleGrants(layer: MapLayer): void {
    if (this.editingGrantsForSignal() === layer.layerId) {
      this.editingGrantsForSignal.set(null);
      return;
    }
    this.editingGrantsForSignal.set(layer.layerId);
    // `grants` is only ever populated on a layer this viewer manages (§4.2) and never travels over
    // SSE — an absent list means "not loaded", which for a MANAGE row is simply "none yet".
    this.draftGrants.set(layer.grants ?? []);
    this.newSubjectType.set('USER');
    this.newSubjectId.set('');
    this.newLevel.set('VIEW');
    this.ensureOrgLoaded();
  }

  protected setSubjectType(type: GrantSubjectType): void {
    this.newSubjectType.set(type);
    this.newSubjectId.set('');
  }

  protected addGrant(): void {
    const subjectId = this.newSubjectId();
    if (subjectId.length === 0) {
      return;
    }
    this.draftGrants.update((grants) =>
      upsertGrant(grants, { subjectType: this.newSubjectType(), subjectId, level: this.newLevel() }),
    );
    this.newSubjectId.set('');
  }

  protected setGrantLevel(grant: LayerGrant, level: AccessLevel): void {
    this.draftGrants.update((grants) => upsertGrant(grants, { ...grant, level }));
  }

  protected dropGrant(grant: LayerGrant): void {
    this.draftGrants.update((grants) => removeGrant(grants, grant));
  }

  protected async saveGrants(layer: MapLayer): Promise<void> {
    await this.run(async () => {
      if (await this.layers.setGrants(layer.layerId, this.draftGrants())) {
        this.editingGrantsForSignal.set(null);
      }
    });
  }

  /**
   * Loads users/groups once, quietly. Quiet matters: a MANAGER who may legitimately edit their own
   * layer's grants can still be 403'd by the admin-only listing endpoints, and that is a degraded
   * picker (handled in the template), not an error worth interrupting them with a toast.
   */
  private ensureOrgLoaded(): void {
    if (!this.org.loaded() && !this.org.loading()) {
      void this.org.refresh({ quiet: true });
    }
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    try {
      await action();
    } finally {
      this.busy.set(false);
    }
  }
}
