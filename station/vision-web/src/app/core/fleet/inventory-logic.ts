import type { AuthCapability, InventoryState, LifecycleState } from '../api/models';
import { hasCapability } from '../auth/auth-logic';

/**
 * Pure, Angular-free logic shared by the Inventory page (`/assets`, docs/plans/active/WAREHOUSE-UX-PLAN.md
 * §3.1 rule 3 / §3.3, wave W4) and any other surface that needs to answer the same three questions
 * without re-deriving them: which of the page's four tabs exist for a given role, what one chip a
 * vehicle/equipment row should show for its combined lifecycle + inventory state, and what filename
 * an inventory CSV export should carry. Lives in `core/fleet/` (not `features/inventory/**`) per
 * this codebase's "a second consumer moves shared logic to `core/`" precedent
 * (`core/fleet/device-logic.ts`'s own doc comment) — `features/inventory/**`'s own facade is today's
 * only consumer, but this module is deliberately placed alongside `core/fleet/warehouse-logic.ts`/
 * `core/fleet/triage-logic.ts` (the other cross-page fleet-lifecycle logic) rather than folded into
 * a page-local file, since the plan's own wave table names this exact path.
 *
 * **Wave W3 of docs/plans/active/INVENTORY-REWORK-PLAN.md** (§5.2) adds the fourth and largest
 * question to that list: *which verbs may this session offer on this asset* —
 * {@link vehicleRowActions}, one authority-aware matrix the row kebab, the detail pane and (from
 * W5) the pilot cards all read, replacing the capability-blind boolean set that used to live in
 * `features/inventory/vehicles-logic.ts` and rendered Issue/Ground/Retire to pilots and viewers
 * whose every click the server then refused (that plan's context §3 defect A).
 */

// --- Tabs (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule 3: "Inventory is one page with tabs") ---

export type InventoryTab = 'vehicles' | 'equipment' | 'links' | 'categories';

/** The page's default tab — a bare `/assets` (no `?tab=`) has always meant "the vehicle list" (OQ4: the URL itself is unchanged). */
const DEFAULT_TAB: InventoryTab = 'vehicles';

const ALL_TABS: readonly InventoryTab[] = ['vehicles', 'equipment', 'links', 'categories'];

/**
 * Parses `?tab=`, defaulting to {@link DEFAULT_TAB} for anything absent/unrecognised — never a
 * blank page for a stale or hand-edited query string.
 */
export function parseInventoryTab(raw: string | null | undefined): InventoryTab {
  return (ALL_TABS as readonly string[]).includes(raw ?? '') ? (raw as InventoryTab) : DEFAULT_TAB;
}

/**
 * The tabs a caller with `canManageOrg` may see — Links (today's Devices content) and Categories
 * carry the same `orgGuard`-equivalent gate `nav-entries.ts`'s "Devices"/"Asset categories" entries
 * used to (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.1 rule 6): a pilot's fleet is scoped to their
 * own assignments, and neither raw device wiring nor the org-wide category taxonomy is a pilot
 * concern. Vehicles/Equipment stay open to everyone, same openness `/assets` always had.
 */
export function visibleInventoryTabs(canManageOrg: boolean): readonly InventoryTab[] {
  return canManageOrg ? ALL_TABS : ['vehicles', 'equipment'];
}

/** Whether `tab` is one `canManageOrg` may actually land on — the in-page equivalent of `orgGuard`
 *  for a route that is all one path (`/assets`), never a second `canActivate`. A caller whose
 *  `?tab=` resolves to a tab this returns `false` for must fall back to {@link DEFAULT_TAB} rather
 *  than rendering gated content or crashing. */
export function isInventoryTabVisible(tab: InventoryTab, canManageOrg: boolean): boolean {
  return visibleInventoryTabs(canManageOrg).includes(tab);
}

// --- Effective inventory-state chip (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3: "Inventory state
// chip (merge with existing state chip logic: archived/deactivated win, else inventoryState)") ---

/**
 * One row's combined lifecycle + inventory-state chip — frontend-style §5's "one chip per row max"
 * rule applied to a row that now carries *two* independent state axes (`AssetSummary#lifecycle`,
 * unrelated to this wave, and the new `inventoryState`, D1/D2/D6). `archived`/`deactivated` win
 * outright (an archived or deactivated asset's inventory state is moot — it isn't in service at
 * all, regardless of who last held it), exactly mirroring `features/assets/assets-logic.ts
 * #describeAssetState`'s own priority order; only once neither applies does the row fall through to
 * its `inventoryState`. `'unknown'` is the honest fallback for a row whose `inventoryState` was
 * never fetched (see `AssetSummary#inventoryState`'s own doc comment on why it's optional in TS
 * despite always being present on the wire) — never a fabricated "In stock".
 */
export type InventoryStateChipKind =
  | 'archived'
  | 'deactivated'
  | 'in-stock'
  | 'issued'
  | 'in-field'
  | 'maintenance'
  | 'retired'
  | 'unknown';

/** Chip visual tone — `frontend-style §3`'s status-only-color rule: `muted` for the archived/steady
 *  states, `ok` for actively-in-use states, `warn` for the one state that needs attention. */
export type InventoryStateChipTone = 'muted' | 'ok' | 'warn';

export interface InventoryStateChip {
  readonly kind: InventoryStateChipKind;
  readonly label: string;
  readonly tone: InventoryStateChipTone;
  /** True only for `in-field` — mirrors the old "Live" chip's pulsing dot: an asset with an open
   *  usage really is live in the field right now, not just administratively checked out. */
  readonly live: boolean;
}

const UNKNOWN_CHIP: InventoryStateChip = { kind: 'unknown', label: '—', tone: 'muted', live: false };

export function effectiveInventoryStateChip(
  row: { readonly lifecycle: LifecycleState; readonly archived: boolean; readonly inventoryState?: InventoryState },
): InventoryStateChip {
  if (row.archived) {
    return { kind: 'archived', label: 'Archived', tone: 'muted', live: false };
  }
  if (row.lifecycle === 'DEACTIVATED') {
    return { kind: 'deactivated', label: 'Deactivated', tone: 'muted', live: false };
  }
  switch (row.inventoryState) {
    case 'IN_STOCK':
      return { kind: 'in-stock', label: 'In stock', tone: 'muted', live: false };
    case 'ISSUED':
      return { kind: 'issued', label: 'Issued', tone: 'ok', live: false };
    case 'IN_FIELD':
      return { kind: 'in-field', label: 'In field', tone: 'ok', live: true };
    case 'MAINTENANCE':
      return { kind: 'maintenance', label: 'Maintenance', tone: 'warn', live: false };
    case 'RETIRED':
      return { kind: 'retired', label: 'Retired', tone: 'muted', live: false };
    default:
      return UNKNOWN_CHIP;
  }
}

// --- Authority-aware row verbs (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.2, wave W3) --------

/**
 * Who is looking, as far as the verb matrix is concerned — the client-side mirror of the server's
 * own `Authority` (`capabilities` × `scope`), read once off `AuthStore` and threaded through
 * {@link vehicleRowActions} rather than re-derived per verb. Built by {@link inventoryActor}.
 *
 * **Scope is deliberately absent.** The server already scopes every inventory read
 * (INVENTORY-REWORK-CONTEXT.md §3: "Nothing leaks"), so a row this session can see is by definition
 * a row in its scope — the matrix's "in scope" column needs no client-side test, only the
 * capability columns do.
 *
 * **W2 (decisions D4/D5) extends this record, not its call sites**: a pilot's *Report issue* and a
 * custodian's *Return* need `userId` (already here) plus an `assignedAssetIds` set; adding that as
 * one more optional field leaves every `vehicleRowActions(row, actor)` call unchanged.
 */
export interface InventoryActor {
  /** `MANAGE_FLEET` — custody, grounding, retirement, archive/restore (`AssetController#requireManageable`). */
  readonly canManageFleet: boolean;
  /** `MANAGE_ORG` — creating assets (`POST /api/assets`), the discovery inbox, the Links/Categories tabs. Not a row verb; carried here so one actor answers every gate on the page. */
  readonly canManageOrg: boolean;
  /** `COMMAND_FLIGHT` — may actually fly, so may be offered the cockpit. */
  readonly canCommandFlight: boolean;
  /** The acting user's own id (`MeResponse#userId`), `undefined` until `GET /api/auth/me` resolves. Unused by this wave's two columns; it is what §5.2's "custodian == me" column will test (D5, W2). */
  readonly userId?: string;
}

/** `AuthStore`'s two reads (`capabilities()`, `user()?.userId`) folded into one {@link InventoryActor} — the only mapping from session to matrix input, so a page/facade never spells out three `can(...)` calls of its own. */
export function inventoryActor(
  capabilities: readonly AuthCapability[] | null | undefined,
  userId: string | undefined,
): InventoryActor {
  return {
    canManageFleet: hasCapability(capabilities, 'MANAGE_FLEET'),
    canManageOrg: hasCapability(capabilities, 'MANAGE_ORG'),
    canCommandFlight: hasCapability(capabilities, 'COMMAND_FLIGHT'),
    userId,
  };
}

/**
 * The facts about one asset the matrix reads — a structural subset every Inventory row model
 * (`features/inventory/vehicles-logic.ts#VehicleRow`, and W5's own card model) already satisfies,
 * so neither has to be imported into `core/`.
 */
export interface InventoryActionRow {
  readonly lifecycle: LifecycleState;
  readonly archived: boolean;
  readonly inventoryState?: InventoryState;
  /** Is video live for this asset right now (`AssetSummary#status === 'STREAMING'`)? Gates *Watch live* only. */
  readonly streaming: boolean;
  /** Who physically holds it (`AssetCustody#custodianId`), absent while it is in stock. */
  readonly custodianId?: string;
}

/** Every verb the Inventory surfaces can offer for one asset — `open` is the only one available unconditionally to anyone who can see the row at all. */
export type VehicleVerb =
  | 'issue'
  | 'return'
  | 'ground'
  | 'release'
  | 'retire'
  | 'archive'
  | 'restore'
  | 'fly'
  | 'watchLive'
  | 'open';

/**
 * One verb's availability. The two states are deliberately distinct (INVENTORY-REWORK-PLAN.md §5.2's
 * closing rule): a verb **the server would refuse** — this session lacks the capability — is not
 * rendered at all (`shown: false`), because offering it is a lie that costs a 403 to discover
 * (INVENTORY-REWORK-CONTEXT.md §3 defect A); a verb that exists for this session but is
 * *momentarily impossible* is rendered `disabled` with a {@link reason} the caller shows as a
 * `title`/`.kebab-reason`, because hiding it would leave the operator wondering where it went.
 */
export interface VerbAvailability {
  readonly shown: boolean;
  readonly disabled: boolean;
  /** Why it is disabled, as a sentence fragment the UI shows verbatim. Present only when `disabled`. */
  readonly reason?: string;
}

export type VehicleRowActions = Readonly<Record<VehicleVerb, VerbAvailability>>;

const VERB_HIDDEN: VerbAvailability = { shown: false, disabled: false };
const VERB_AVAILABLE: VerbAvailability = { shown: true, disabled: false };

function verbBlocked(reason: string): VerbAvailability {
  return { shown: true, disabled: true, reason };
}

/** Retire is refused by `DefaultAssetInventoryService` while somebody still holds the asset — the fix is one click away, so the verb stays visible and says so. */
export const RETIRE_WHILE_HELD_REASON = 'Return it to stock first';

/** An open flight-blocking `MaintenanceRecord` makes `engage` refuse (`DefaultReadinessService#maintenanceBlockers`); the cockpit would open onto a vehicle that cannot arm. */
export const FLY_WHILE_GROUNDED_REASON = 'Grounded for maintenance — release it first';

/**
 * **The one verb matrix** (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.2) — effective state ×
 * authority → what a surface may render. Both the row kebab and the detail pane read this, and
 * W5's "My vehicles" cards will read the same function, so the three can never drift.
 *
 * Effective state comes from {@link effectiveInventoryStateChip}'s own `kind` rather than a second
 * derivation of the archived/deactivated-wins precedence — the chip a row shows and the verbs it
 * offers are then guaranteed to describe the same state.
 *
 * Implemented here: §5.2's column 1 (**mayManageFleet**) and column 4 (**anyone in scope**), plus
 * `fly`, which column 2 grants to any `COMMAND_FLIGHT` holder in scope (a MANAGER/ADMIN holds it
 * too — this is not one of the D4/D5 widenings). Columns 2 and 3's *new* verbs — a pilot's
 * **Report issue** (D4) and a custodian's own **Return** (D5) — are deliberately absent until that
 * plan's wave W2 widens the server gates; rendering them earlier would be exactly the defect this
 * function exists to fix.
 *
 * Two deliberate divergences from the printed table, both noted rather than silently taken:
 * `DEACTIVATED` has no row in §5.2 at all (it is a lifecycle state, not an inventory one) and is
 * treated exactly like `ARCHIVED` here — `Restore` and nothing else; and an `inventoryState` that
 * was never fetched (`'unknown'`) offers no mutating verb whatsoever rather than guessing which
 * would be safe.
 */
export function vehicleRowActions(row: InventoryActionRow, actor: InventoryActor): VehicleRowActions {
  const state = effectiveInventoryStateChip(row).kind;
  const verbs: Record<VehicleVerb, VerbAvailability> = {
    issue: VERB_HIDDEN,
    return: VERB_HIDDEN,
    ground: VERB_HIDDEN,
    release: VERB_HIDDEN,
    retire: VERB_HIDDEN,
    archive: VERB_HIDDEN,
    restore: VERB_HIDDEN,
    fly: VERB_HIDDEN,
    watchLive: VERB_HIDDEN,
    // Column 4: anybody who can see the row can open it — the detail page is a scoped read.
    open: VERB_AVAILABLE,
  };

  const inService = state === 'in-stock' || state === 'issued' || state === 'in-field';
  if (inService && row.streaming) {
    verbs.watchLive = VERB_AVAILABLE;
  }

  if (actor.canCommandFlight) {
    if (inService) {
      verbs.fly = VERB_AVAILABLE;
    } else if (state === 'maintenance') {
      verbs.fly = verbBlocked(FLY_WHILE_GROUNDED_REASON);
    }
  }

  if (!actor.canManageFleet) {
    return verbs;
  }

  switch (state) {
    case 'in-stock':
      verbs.issue = VERB_AVAILABLE;
      verbs.ground = VERB_AVAILABLE;
      verbs.retire = VERB_AVAILABLE;
      verbs.archive = VERB_AVAILABLE;
      break;
    case 'issued':
      verbs.return = VERB_AVAILABLE;
      verbs.ground = VERB_AVAILABLE;
      verbs.retire = verbBlocked(RETIRE_WHILE_HELD_REASON);
      break;
    case 'in-field':
      // No Return and no Retire while a usage is open: the asset is out flying, and both writes
      // would be a paperwork claim about a vehicle that is demonstrably still in the air.
      verbs.ground = VERB_AVAILABLE;
      break;
    case 'maintenance':
      verbs.release = VERB_AVAILABLE;
      verbs.retire = VERB_AVAILABLE;
      verbs.archive = VERB_AVAILABLE;
      break;
    case 'retired':
      verbs.archive = VERB_AVAILABLE;
      break;
    case 'archived':
    case 'deactivated':
      verbs.restore = VERB_AVAILABLE;
      break;
    case 'unknown':
      break;
  }
  return verbs;
}

/**
 * Which single verb a surface may render as its **primary** control — frontend-style §6's "max one
 * primary `.btn`" applied to the matrix above, so the detail drawer
 * (docs/plans/active/INVENTORY-REWORK-PLAN.md §5.3, wave W4) and W5's "My vehicles" card can never
 * disagree about which button is the loud one for a given state.
 *
 * The order below *is* §5.2's own bolding, read top to bottom: the custody verb the state is waiting
 * for first (Issue to… → Return to stock → Release), then flying it, then watching it. A verb that
 * is `shown` but `disabled` is deliberately skipped — a greyed-out control is not this row's primary
 * action, and promoting it would push the verb that *is* usable down into the ghost row. `undefined`
 * means this session has no primary verb for this row at all (a viewer, an archived asset); the
 * caller then renders only ghost buttons and "Open full ›", never a lone loud button it invented.
 */
export function primaryVehicleVerb(actions: VehicleRowActions): VehicleVerb | undefined {
  const order: readonly VehicleVerb[] = ['issue', 'return', 'release', 'fly', 'watchLive', 'restore'];
  return order.find((verb) => actions[verb].shown && !actions[verb].disabled);
}

/**
 * A raw id rendered short enough for a table cell — the first segment of a UUID with an ellipsis
 * (`3f2a91c4…`). The **last** fallback for a name (`custodianName` → user-list join → this), used
 * only when neither the wire nor the org listing can name the account; the caller always pairs it
 * with a `title` carrying the full id, so the value stays copyable. A short/unusual id is returned
 * verbatim rather than padded — never a fabricated shape.
 */
export function shortIdLabel(id: string): string {
  const head = id.split('-')[0];
  return head.length > 0 && head.length < id.length ? `${head}…` : id;
}

// --- CSV export filename (docs/plans/active/WAREHOUSE-UX-PLAN.md §3.3's Export action, wave W4) ---

/**
 * The `download` attribute's own suggested filename for the Export CSV action's `<a>` —
 * `inventory-YYYY-MM-DD.csv`, one dash-joined ISO date so the file sorts naturally beside other
 * downloads. Cosmetic only: `InventoryExportController#export` already sends
 * `Content-Disposition: attachment; filename="inventory.csv"`, which every browser prefers over an
 * anchor's own `download` value when both are present — this is what a client falls back to on the
 * rare browser/proxy combination that drops the header, not the name a normal download will show.
 */
export function inventoryExportFilename(nowMs: number): string {
  const iso = new Date(nowMs).toISOString().slice(0, 10);
  return `inventory-${iso}.csv`;
}
