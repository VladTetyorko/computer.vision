import type { AssetSummary, AuditEntry, UserSummary } from '../api/models';
import { formatActivity } from '../org/org-logic';
import { activityAccentTone, type ActivityAccentTone } from '../activity/activity-logic';

/**
 * Pure, Angular-free logic behind `/monitor/audit` (docs/plans/active/OPS-UX-PLAN.md §3 B1) — the manager's
 * fleet-wide accountability surface over `GET /api/audit`, a built-and-audited endpoint no page in
 * this app called before this task (docs/conclusions/OPS-UX-REVIEW.md §U3). Deliberately reuses
 * `core/org/org-logic.ts#formatActivity` and `core/activity/activity-logic.ts#activityAccentTone`
 * rather than re-deriving action/target labels or the verb→color mapping a second time — both
 * already turn one `AuditEntry` into the display fields `/activity` (the acting user's *own* recent
 * actions) needs; this file only adds what a fleet-wide view needs on top: **who** (an actor
 * resolved to a display name, `/activity` has no need to show since it is always "you"), and
 * **result** (denied vs succeeded — `/activity` never shows a denied entry for its own actor by
 * construction, since a denied write is *also* the actor's own action and would appear there too if
 * `ActivityService` scoped that way, but no page before this one needed to distinguish the two).
 */

/** One row of the audit table — every field the template needs, already resolved to display text. */
export interface AuditRow {
  readonly id: string;
  readonly occurredAtIso: string;
  /** Locale-formatted absolute date+time — an audit log is read by "when exactly", not "how long ago" (mirrors `features/activity/activity-facade.ts#toRow`'s identical absolute-time choice). */
  readonly absoluteTime: string;
  readonly actorId: string;
  /** The actor's resolved display name, or a short id fragment when unresolvable — same fallback rule as `features/roster/roster-logic.ts#buildRosterRows`, never fabricated. */
  readonly actorLabel: string;
  readonly action: string;
  readonly actionLabel: string;
  readonly accentTone: ActivityAccentTone;
  readonly targetType: string;
  /** `formatActivity`'s own capitalized kind label ("Asset"/"Device"/…), not a resolved name — see `targetName` for that. */
  readonly targetKindLabel: string;
  readonly targetId: string;
  /** The target's resolved display name (currently only ever resolved for `ASSET`, the only target kind this page has a name source for) or a short id fragment. */
  readonly targetName: string;
  readonly summary: string;
  readonly details: readonly { readonly key: string; readonly value: string }[];
  /** Whether `details.result` (the `DENIED:<reason>` convention several application services already write — see {@link auditResult}) marks this entry a refused attempt rather than a completed change. */
  readonly denied: boolean;
  /** `'Denied — <reason>'` when `denied`, otherwise `'Success'` — never blank, so the column always reads as a real fact rather than an absence. */
  readonly resultLabel: string;
}

const DENIED_PREFIX = 'DENIED';

/**
 * Reads the `result` free-form detail several application services already write on every audit
 * entry they record — success and denial alike (`DefaultDatasetService`/`DefaultLabelingService`/
 * `DefaultTrainingJobService`/`DefaultModelRegistryService`/`DefaultFlightCommandService`/
 * `DefaultManualControlService` all follow the identical `"DENIED:<reason>"` convention on refusal).
 * **Not every service writes this key** — `DefaultAssetService`/`DefaultDeviceService` (the majority
 * of real fleet activity: create/edit/deactivate/delete an asset or device) never do, because
 * `AssetController`'s own authority gate (docs/plans/active/OPS-UX-PLAN.md §1) throws before the service
 * layer is ever reached on a denial, so no entry is recorded for those refusals at all — an absent
 * `result` key therefore always means "this entry is a completed change", never "denied, but we
 * forgot to say so"; a fabricated distinction is never invented for the entries that carry no signal
 * either way.
 */
export function auditResult(entry: AuditEntry): { readonly denied: boolean; readonly label: string } {
  const raw = entry.details?.['result'];
  if (!raw || !raw.toUpperCase().startsWith(DENIED_PREFIX)) {
    return { denied: false, label: 'Success' };
  }
  const colon = raw.indexOf(':');
  const reason = colon >= 0 ? raw.slice(colon + 1).trim() : '';
  return { denied: true, label: reason ? `Denied — ${reason}` : 'Denied' };
}

function actorLabelFor(actorId: string, users: readonly UserSummary[]): string {
  return users.find((user) => user.userId === actorId)?.displayName ?? actorId.slice(0, 8);
}

/**
 * Resolves a target to a display name when this page has a name source for its kind — today only
 * `ASSET`, against the already-loaded fleet (which, like `actorLabelFor`, may legitimately not
 * contain every target an admitted MANAGER's fleet-wide audit trail can mention — see
 * `AuditController`'s own class javadoc, "an admitted MANAGER sees the whole fleet's audit trail",
 * wider than their own `GET /api/assets` scope). An unresolved target of any kind — a genuinely
 * unknown asset, or any `DEVICE`/`DATASET`/`MODEL` target (no cheap name source loaded for those on
 * this page) — falls back to a short id fragment rather than a guess.
 */
function targetNameFor(entry: AuditEntry, assets: readonly AssetSummary[]): string {
  if (entry.targetType === 'ASSET') {
    const asset = assets.find((a) => a.assetId === entry.targetId);
    if (asset) {
      return asset.displayName;
    }
  }
  return entry.targetId.slice(0, 8);
}

/** Builds every row for the table, in whatever order `entries` already arrives in (the backend's own newest-first contract — never re-sorted here). */
export function buildAuditRows(
  entries: readonly AuditEntry[],
  users: readonly UserSummary[],
  assets: readonly AssetSummary[],
  nowMs: number,
): readonly AuditRow[] {
  return entries.map((entry) => {
    const activity = formatActivity(entry, nowMs);
    const result = auditResult(entry);
    return {
      id: entry.id,
      occurredAtIso: entry.occurredAt,
      absoluteTime: new Date(entry.occurredAt).toLocaleString(undefined, {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      }),
      actorId: entry.actor,
      actorLabel: actorLabelFor(entry.actor, users),
      action: entry.action,
      actionLabel: activity.actionLabel,
      accentTone: activityAccentTone(entry.action),
      targetType: entry.targetType,
      targetKindLabel: activity.targetLabel,
      targetId: entry.targetId,
      targetName: targetNameFor(entry, assets),
      summary: activity.summary,
      details: activity.details,
      denied: result.denied,
      resultLabel: result.label,
    };
  });
}

/** The page's own two filters (docs/plans/active/OPS-UX-PLAN.md §3 B1 — "filterable by actor and action"); the backend's `targetType`/`targetId` query params are never used here — this page filters client-side over one already-fetched page of recent entries, not a second round-trip per filter change. */
export interface AuditFilter {
  readonly actorId?: string;
  readonly action?: string;
}

export function filterAuditRows(rows: readonly AuditRow[], filter: AuditFilter): readonly AuditRow[] {
  return rows.filter(
    (row) =>
      (!filter.actorId || row.actorId === filter.actorId) && (!filter.action || row.action === filter.action),
  );
}

/** One `<option>` for the actor filter — id to filter on, label to display. */
export interface AuditOption {
  readonly value: string;
  readonly label: string;
}

/** Every distinct actor across `rows`, alphabetical by label — the actor filter's own option list, so it only ever offers choices that actually appear in the loaded page. */
export function distinctActors(rows: readonly AuditRow[]): readonly AuditOption[] {
  const byId = new Map<string, string>();
  for (const row of rows) {
    if (!byId.has(row.actorId)) {
      byId.set(row.actorId, row.actorLabel);
    }
  }
  return [...byId.entries()]
    .map(([value, label]) => ({ value, label }))
    .sort((a, b) => a.label.localeCompare(b.label, undefined, { sensitivity: 'base' }));
}

/** Every distinct action across `rows`, alphabetical by label — the action filter's own option list. */
export function distinctActions(rows: readonly AuditRow[]): readonly AuditOption[] {
  const byValue = new Map<string, string>();
  for (const row of rows) {
    if (!byValue.has(row.action)) {
      byValue.set(row.action, row.actionLabel);
    }
  }
  return [...byValue.entries()]
    .map(([value, label]) => ({ value, label }))
    .sort((a, b) => a.label.localeCompare(b.label, undefined, { sensitivity: 'base' }));
}
