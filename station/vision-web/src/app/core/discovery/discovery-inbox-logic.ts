import { humanAge } from '../telemetry/telemetry-logic';
import type {
  DiscoveryCandidate,
  DiscoveryCandidateStatus,
  DiscoverySource,
  RegisterDeviceRequest,
  RegisterDiscoveryCandidateRequest,
} from '../api/models';

/**
 * Pure derivations for the discovery inbox's "Found devices" cards
 * (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §3 P2, §11 Z2d) — status filtering, the
 * "last heard <age> ago" render (stale-not-live: a card is never shown as "online", only an age,
 * per CLAUDE.md's degrade-honestly rule), and the request shapes the Add/Attach dialogs post.
 * `DiscoveryInboxStore`/`FoundDevices` own every Angular-side effect; this file owns none.
 */

/** One method → one short, title-cased chip label. Falls back to the raw string (capitalized) for
 *  any method this file doesn't know yet, rather than throwing on a backend value this build
 *  predates — the same "never a blocked page" honesty rule applied to a label instead of a page. */
const METHOD_LABELS: Readonly<Record<string, string>> = {
  mavlink: 'MAVLink',
  onvif: 'ONVIF',
  mdns: 'mDNS',
  v4l2: 'V4L2',
  mediamtx: 'Mediamtx push',
};

export function discoveryMethodLabel(method: string): string {
  const known = METHOD_LABELS[method.toLowerCase()];
  if (known) {
    return known;
  }
  return method.length === 0 ? method : method[0].toUpperCase() + method.slice(1);
}

/**
 * "Mediamtx push unreachable — found devices may be incomplete." per source reporting
 * `UNREACHABLE` (A3, docs/plans/active/ASSET-FLOWS-PLAN.md §2) — an operator staring at an empty or
 * partial inbox has no way to tell "nothing found yet" from "this scanner can't even run right now"
 * without this. Reuses {@link discoveryMethodLabel} so a source's name reads identically here and on
 * its own candidates' cards. `OK` sources produce no message at all — the plan's own "empty+all-OK
 * keeps the current empty state" rule; `FoundDevices` renders nothing when this returns `[]`.
 */
export function sourceUnreachableWarnings(sources: readonly DiscoverySource[]): readonly string[] {
  return sources
    .filter((source) => source.status === 'UNREACHABLE')
    .map((source) => `${discoveryMethodLabel(source.id)} unreachable — found devices may be incomplete.`);
}

/**
 * "last heard 12s ago" — the one age render for a candidate, sharing `humanAge`
 * (`core/telemetry/telemetry-logic.ts`) with the OSD/drawer/picker so a stale reading reads the
 * same everywhere in the app. `nowMs` is threaded in by the caller (never `Date.now()` here) so
 * this stays a pure, clock-free function — mirrors `attentionAgeLabel`'s own shape.
 */
export function candidateAgeLabel(lastSeenIso: string, nowMs: number): string {
  const lastSeenMs = Date.parse(lastSeenIso);
  const ageSeconds = Number.isFinite(lastSeenMs) ? Math.max(0, (nowMs - lastSeenMs) / 1000) : 0;
  return `last heard ${humanAge(ageSeconds)} ago`;
}

/** Which of the inbox's toggles are on — both default off, so the section opens showing only what
 *  still needs a decision (NEW). */
export interface DiscoveryInboxVisibility {
  readonly showRegistered: boolean;
  readonly showDismissed: boolean;
}

export const DEFAULT_DISCOVERY_INBOX_VISIBILITY: DiscoveryInboxVisibility = {
  showRegistered: false,
  showDismissed: false,
};

const STATUS_RANK: Readonly<Record<DiscoveryCandidateStatus, number>> = {
  NEW: 0,
  REGISTERED: 1,
  DISMISSED: 2,
};

/**
 * The cards actually rendered: NEW always shown, REGISTERED/DISMISSED behind their own toggle
 * (§11 Z2d — "REGISTERED cards … can be collapsed/filtered"; "DISMISSED hidden behind a 'show
 * dismissed' toggle"). Sorted NEW-first, then by `lastSeen` descending within a status group, so
 * the freshest, most-actionable card is always the first the operator sees.
 */
export function visibleCandidates(
  candidates: readonly DiscoveryCandidate[],
  visibility: DiscoveryInboxVisibility,
): readonly DiscoveryCandidate[] {
  return candidates
    .filter((candidate) => {
      if (candidate.status === 'REGISTERED') {
        return visibility.showRegistered;
      }
      if (candidate.status === 'DISMISSED') {
        return visibility.showDismissed;
      }
      return true; // NEW
    })
    .slice()
    .sort((a, b) => {
      const rank = STATUS_RANK[a.status] - STATUS_RANK[b.status];
      return rank !== 0 ? rank : Date.parse(b.lastSeen) - Date.parse(a.lastSeen);
    });
}

/** Feeds the section's unobtrusive count badge (mirrors the app's other "how many need me"
 *  counts — Command's attention queue, the bell's unread count): only NEW candidates count,
 *  never REGISTERED/DISMISSED — those no longer need a decision. */
export function newCandidateCount(candidates: readonly DiscoveryCandidate[]): number {
  return candidates.filter((candidate) => candidate.status === 'NEW').length;
}

export function registeredCandidateCount(candidates: readonly DiscoveryCandidate[]): number {
  return candidates.filter((candidate) => candidate.status === 'REGISTERED').length;
}

export function dismissedCandidateCount(candidates: readonly DiscoveryCandidate[]): number {
  return candidates.filter((candidate) => candidate.status === 'DISMISSED').length;
}

/** Which actions a card offers — only a `NEW` candidate is actionable at all; `canAttach`
 *  additionally requires a suggested stream (protocol + uri), since "attach to existing asset"
 *  has no device spec to register without one — a telemetry-only candidate with no stream (or,
 *  today, none the scanner could resolve) can still be **added** as a bare asset, just not
 *  attached as a device onto an existing one. */
export interface CandidateActions {
  readonly canAdd: boolean;
  readonly canAttach: boolean;
  readonly canDismiss: boolean;
}

export function candidateActions(candidate: DiscoveryCandidate): CandidateActions {
  const isNew = candidate.status === 'NEW';
  return {
    canAdd: isNew,
    canAttach: isNew && Boolean(candidate.suggestedStreamProtocol) && Boolean(candidate.suggestedStreamUri),
    canDismiss: isNew,
  };
}

/** The Add dialog's initial form state — prefilled entirely from the candidate, per §11 Z2d
 *  ("deliberately NOT the full wizard — the whole point is one click + confirm"). */
export interface RegisterDraft {
  readonly displayName: string;
  readonly category: string;
}

export function defaultRegisterDraft(candidate: DiscoveryCandidate): RegisterDraft {
  return { displayName: candidate.name, category: candidate.suggestedCategory ?? '' };
}

export function canSubmitRegisterDraft(draft: RegisterDraft): boolean {
  return draft.displayName.trim().length > 0 && draft.category.trim().length > 0;
}

/** `RegisterDraft` → the wire request `POST /api/discovery/inbox/{id}/register` takes.
 *  `attributes`/`identity` stay unset — the minimal one-click Add dialog never collects them
 *  (the full onboarding wizard, unchanged, is still there for identity facts). */
export function buildRegisterCommand(draft: RegisterDraft): RegisterDiscoveryCandidateRequest {
  return { displayName: draft.displayName.trim(), category: draft.category.trim() };
}

/**
 * The "attach to existing asset" flow's device spec, built from the candidate's own
 * `suggestedStream` — never typed by the operator (§11 Z2d). `capabilities` is omitted so the
 * server's protocol-aware default applies (`RegisterDeviceRequest`'s own doc comment: `mavlink` →
 * `[TELEMETRY]`, everything else → `[VIDEO]`), and `options` survives verbatim (the whole reason
 * `DiscoveryCandidateResponse` carries the full map rather than the lossy `suggestedOptions`
 * `sysid`-only workaround `DiscoveredDeviceResponse` still has).
 *
 * Returns `undefined` when the candidate has no suggested stream — callers gate this behind
 * `candidateActions(candidate).canAttach` first, so this should never actually happen, but a pure
 * function degrades honestly instead of throwing on a candidate a caller forgot to gate.
 *
 * **The candidate's status is not patched here or by any caller of this function.** The backend
 * flips a candidate to `REGISTERED` on its own next sweep once the newly-registered device
 * duplicate-matches this candidate's identity (`DiscoveryInboxService#report`'s own dedupe rule) —
 * this client never fakes that flip; the next 30s poll shows it for real.
 */
export function buildDeviceSpecFromCandidate(candidate: DiscoveryCandidate): RegisterDeviceRequest | undefined {
  if (!candidate.suggestedStreamProtocol || !candidate.suggestedStreamUri) {
    return undefined;
  }
  return {
    name: candidate.name,
    protocol: candidate.suggestedStreamProtocol,
    uri: candidate.suggestedStreamUri,
    options: candidate.suggestedStreamOptions ?? {},
  };
}
