import type { AuditEntry, GroupSummary, Role } from '../api/models';
import { roleLabel } from '../auth/auth-logic';
import { relativeTimeLabel } from '../events/events-logic';

/**
 * Pure decision logic behind the org-settings surface (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2) — the
 * role gate, the group-tree shape, the role picker, and the activity-row view model. Kept
 * framework-free and unit-tested here so `core/org/org-store.ts`, `core/org/org-guard.ts`,
 * `features/org-settings/**`, and `features/activity/**` stay dumb: each only reads a signal and
 * calls one of these, exactly like `core/auth/auth-logic.ts` backs the login/identity surface.
 */

/**
 * May this role reach the org-settings surface (users/groups CRUD, pilot assignment)? ADMIN and
 * MANAGER only — a PILOT flies aircraft, they don't manage the org (docs/plans/done/U-SCOPE-PLAN.md's own
 * "a pilot flies their aircraft, not their org's inventory").
 *
 * Takes `topRole` as it actually arrives — possibly `undefined`/`null` (a user with no membership
 * has no top role; `UserSummary#topRole` is optional, and a not-yet-loaded `MeResponse` reads
 * `undefined`) — and answers `false` for that case rather than throwing. This is the one place
 * "can manage the org" is written down; the route guard and the nav-link gate both defer to it.
 */
export function canManageOrg(topRole: Role | null | undefined): boolean {
  return topRole === 'ADMIN' || topRole === 'MANAGER';
}

/** One selectable role for the invite form's role picker. */
export interface RoleOption {
  readonly value: Role;
  readonly label: string;
}

/**
 * The roles an inviter can pick, least→most privileged (the domain's own `Role` order). Reuses
 * `auth-logic.ts#roleLabel` verbatim rather than a second label map — one human name per role,
 * app-wide. The ≤-own-scope rule (a MANAGER can't mint an ADMIN) is enforced server-side and
 * surfaced as a `403`; this list is deliberately the full set, so the UI never silently pretends a
 * role doesn't exist — it lets the backend be the single authority on what a given inviter may grant.
 */
export function roleOptions(): readonly RoleOption[] {
  return (['PILOT', 'MANAGER', 'ADMIN'] as const).map((value) => ({ value, label: roleLabel(value) }));
}

/** One node of the group hierarchy — `depth` is 0 for a root, +1 per level, so the template indents without recomputing it. */
export interface GroupTreeNode {
  readonly group: GroupSummary;
  readonly depth: number;
  readonly children: readonly GroupTreeNode[];
}

/**
 * Builds the nested group hierarchy from the flat `GET /api/groups` list, following
 * `parentGroupId`. **Cycle-safe and loss-free**: a group whose parent is absent, unknown, or itself
 * is treated as a root; a group caught in a parent cycle (A→B→A) still appears exactly once (as a
 * root of its own reachable sub-branch) rather than vanishing or looping forever. Children and
 * roots are sorted by name for a stable render. Never mutates its input.
 */
export function buildGroupTree(groups: readonly GroupSummary[]): readonly GroupTreeNode[] {
  const byId = new Map(groups.map((group) => [group.id, group]));
  const childrenOf = new Map<string, GroupSummary[]>();
  const roots: GroupSummary[] = [];

  for (const group of groups) {
    const parentId = group.parentGroupId;
    if (parentId !== undefined && parentId !== group.id && byId.has(parentId)) {
      const siblings = childrenOf.get(parentId) ?? [];
      siblings.push(group);
      childrenOf.set(parentId, siblings);
    } else {
      roots.push(group);
    }
  }

  const byName = (a: GroupSummary, b: GroupSummary): number => a.name.localeCompare(b.name);
  const visited = new Set<string>();

  const build = (group: GroupSummary, depth: number): GroupTreeNode => {
    visited.add(group.id);
    const kids = (childrenOf.get(group.id) ?? [])
      .filter((child) => !visited.has(child.id)) // breaks any parent cycle — a node is placed once
      .sort(byName)
      .map((child) => build(child, depth + 1));
    return { group, depth, children: kids };
  };

  const tree = roots.sort(byName).map((root) => build(root, 0));

  // Any group never reached from a root is a pure-cycle member — surface it as its own root branch
  // so nothing is silently dropped (loss-free even for malformed data).
  for (const group of groups) {
    if (!visited.has(group.id)) {
      tree.push(build(group, 0));
    }
  }
  return tree;
}

/**
 * Flattens the tree to a depth-annotated, pre-ordered list — the template `@for`s over this
 * directly (indenting by `depth`) rather than recursing, which Angular's `@for` can't do cleanly.
 * Preserves the tree's own root/child ordering.
 */
export function flattenGroupTree(nodes: readonly GroupTreeNode[]): readonly GroupTreeNode[] {
  const out: GroupTreeNode[] = [];
  const walk = (node: GroupTreeNode): void => {
    out.push(node);
    node.children.forEach(walk);
  };
  nodes.forEach(walk);
  return out;
}

const ACTION_LABELS: Readonly<Record<string, string>> = {
  CREATED: 'Created',
  UPDATED: 'Updated',
  DEACTIVATED: 'Deactivated',
  ACTIVATED: 'Activated',
  DELETED: 'Deleted',
  RESTORED: 'Restored',
};

/** One activity entry rendered for a human — everything `features/activity/**` needs to draw a row, derived purely so the component stays dumb. */
export interface ActivityView {
  readonly id: string;
  readonly actionLabel: string;
  readonly targetLabel: string;
  readonly summary: string;
  readonly relativeTime: string;
  readonly details: readonly { readonly key: string; readonly value: string }[];
}

/**
 * Turns one `AuditEntry` into its display model. `action`/`targetType` are mapped to friendly
 * labels but **fall back to the raw value** for anything unrecognized (a new backend action never
 * renders as blank); `relativeTime` reuses `events-logic.ts#relativeTimeLabel` verbatim (one
 * "3m ago" format app-wide), with `nowMs` passed in for determinism, the same convention every
 * time-formatting helper in this app follows. `details` is flattened to sorted key/value rows so
 * the template needn't iterate an object.
 */
export function formatActivity(entry: AuditEntry, nowMs: number): ActivityView {
  const targetLabel = entry.targetType
    ? entry.targetType.charAt(0).toUpperCase() + entry.targetType.slice(1).toLowerCase()
    : '';
  return {
    id: entry.id,
    actionLabel: ACTION_LABELS[entry.action] ?? entry.action,
    targetLabel,
    summary: entry.summary,
    relativeTime: relativeTimeLabel(entry.occurredAt, nowMs),
    details: Object.entries(entry.details ?? {})
      .map(([key, value]) => ({ key, value }))
      .sort((a, b) => a.key.localeCompare(b.key)),
  };
}
