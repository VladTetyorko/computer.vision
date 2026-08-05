import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LayersStore } from '../../../core/map-data/layers-store';
import { AuthStore } from '../../../core/auth/auth-store';
import { OrgStore } from '../../../core/org/org-store';
import {
  ACCESS_LEVELS,
  accessLevelLabel,
  canManage,
  layerKindLabel,
  removeGrant,
  upsertGrant,
} from '../../../core/map-data/layers-logic';
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
 * users and groups (docs/MAP-REWORK-PLAN.md §5.2's "layer manager"). Shared by the Fly cockpit's Map
 * drawer and Command's Layers panel.
 *
 * **Deviation from §5.2, flagged.** The plan places this "from the data-layer panel": the map's own
 * built-in panel would grow a grants editor on MANAGE rows. That panel lives inside
 * `<vision-tactical-map>`, whose internals Wave E is explicitly scoped out of restructuring — and a
 * grants editor with two subject pickers, a level select and a create-layer form does not fit a
 * 14rem overlay pinned to a map corner anyway (frontend-style §7: overlay controls are *small* light
 * cards). So the map panel keeps exactly what it had — one eye toggle per layer, client-side
 * decluttering — and the *management* surface is this sibling panel, reachable from both hosts. The
 * two are complementary, and neither duplicates the other.
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
 * `OrgStore` is therefore loaded lazily and quietly (no toast) the first time an Access editor
 * opens, and when it comes back empty the editor says so and still lets existing grants be
 * re-levelled or removed, rather than pretending there is nobody to add or blocking the whole panel.
 *
 * **Dev parity** (`vision.auth.enabled=false`): the dev admin reports `topRole: 'ADMIN'`, so the
 * Team-layer path resolves its group list from `OrgStore.groups()` (unbounded for that account) and
 * every management control is available exactly as it is for a real admin — the app behaves as
 * before with zero auth.
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
  private readonly auth = inject(AuthStore);
  protected readonly org = inject(OrgStore);

  protected readonly levels = ACCESS_LEVELS;
  protected readonly accessLevelLabel = accessLevelLabel;
  protected readonly layerKindLabel = layerKindLabel;
  protected readonly canManage = canManage;

  protected readonly busy = signal(false);

  // --- Create ------------------------------------------------------------------------------------
  protected readonly creating = signal(false);
  protected readonly newName = signal('');
  protected readonly newKind = signal<Exclude<LayerKind, 'COP'>>('PERSONAL');
  protected readonly newGroupId = signal('');

  /**
   * Groups this account may own a TEAM layer in. A MANAGER's own memberships are the honest answer
   * (`MeResponse.memberships` always ships with the session, no extra request); an ADMIN is
   * unbounded, so their list comes from `OrgStore` once it has loaded, falling back to memberships
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
    if (me.topRole !== 'ADMIN') {
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
