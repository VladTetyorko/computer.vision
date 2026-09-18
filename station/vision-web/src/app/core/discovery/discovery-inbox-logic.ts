import { humanAge } from '../telemetry/telemetry-logic';
import type {
  DiscoveryCandidate,
  DiscoveryCandidateStatus,
  DiscoveryEventPayload,
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

/**
 * Simulated sources are created and managed exclusively from `/playground` (docs/plans/active/
 * LINK-PAIRING-PLAN.md §4 row L4) — they must never surface in the passive "Found nearby" onboarding
 * feed or the discovery inbox's own count badges, so a Playground-created asset is never mistaken
 * for a real, physically-present device. **Assumed, not verified against a real backend**: nothing
 * in the discovery contract (§3.3/§3.4) says a simulated source is ever reported to the inbox at
 * all (today it plainly is not — `POST /api/simulations` never touches discovery), so this filter is
 * defensive/forward-compatible only, in case a future scanner starts reporting one. Matches by
 * `method` — case-insensitively `'simulation'` or `'sim'` — the two spellings this codebase already
 * uses for the concept (`SimulateMode`/`core/fleet/simulation-logic.ts` vs. this module's own
 * `sim`-prefixed device ids elsewhere).
 */
export function excludeSimulated(candidates: readonly DiscoveryCandidate[]): readonly DiscoveryCandidate[] {
  return candidates.filter((candidate) => {
    const method = candidate.method.toLowerCase();
    return method !== 'simulation' && method !== 'sim';
  });
}

/** Keys `candidate.details` uses, across the scanners this app already has, when a probe attempt
 *  came back needing authentication (an ONVIF camera behind a login, mainly) — matched
 *  case-insensitively against both the key and, for boolean-shaped keys, its value. */
const CREDENTIAL_HINT_KEYS = ['authrequired', 'needsauth', 'requiresauth', 'credentialrequired'];
const CREDENTIAL_HINT_STATUS_VALUES = ['unauthorized', '401', 'auth_required', 'credentials_required'];

/**
 * Whether the Confirm screen's one typed credential field should show at all (docs/plans/active/
 * LINK-PAIRING-PLAN.md §3.7, wave L4 — "the ONE typed field only if the probe was refused for
 * credentials"). **Assumed, not verified against a real backend**: no field on `DiscoveryCandidate`
 * today distinguishes "this probe failed because it needs a password" from any other failure or from
 * "never probed at all" — `sysidPushRequired`'s own doc comment notes the same gap for pairing. This
 * reads `candidate.details` defensively for a small set of plausible key/value spellings a scanner
 * might use to report that; until L2/L3 lands a real signal (or tells the web which key to read) this
 * will almost always resolve `false` and the field stays hidden — which is the honest failure mode
 * (never invents a "credentials needed" prompt the backend never asked for).
 */
export function candidateNeedsCredential(candidate: DiscoveryCandidate): boolean {
  return Object.entries(candidate.details).some(([key, value]) => {
    const loweredKey = key.toLowerCase();
    const loweredValue = value.toLowerCase();
    if (CREDENTIAL_HINT_KEYS.includes(loweredKey)) {
      return loweredValue === 'true' || loweredValue === 'yes' || CREDENTIAL_HINT_STATUS_VALUES.includes(loweredValue);
    }
    return loweredKey === 'probestatus' && CREDENTIAL_HINT_STATUS_VALUES.includes(loweredValue);
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
 *  attached as a device onto an existing one. `canRestore` is the mirror image of `canDismiss` —
 *  only a `DISMISSED` candidate can be undone (W3, docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md
 *  §11), reopening it back to `NEW` via `DiscoveryInboxStore#restore`. */
export interface CandidateActions {
  readonly canAdd: boolean;
  readonly canAttach: boolean;
  readonly canDismiss: boolean;
  readonly canRestore: boolean;
}

export function candidateActions(candidate: DiscoveryCandidate): CandidateActions {
  const isNew = candidate.status === 'NEW';
  return {
    canAdd: isNew,
    canAttach: isNew && Boolean(candidate.suggestedStreamProtocol) && Boolean(candidate.suggestedStreamUri),
    canDismiss: isNew,
    canRestore: candidate.status === 'DISMISSED',
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

/**
 * Folds `discovery` SSE deltas (`DiscoveryEventPayload`, docs/plans/active/SOURCE-ONBOARDING-2-PLAN.md
 * §3.2 C4, wave W1) onto the inbox's own poll-fetched candidate list — upsert by id, in event
 * order, so a later delta for the same candidate always wins over an earlier one regardless of its
 * `action` (mirrors `core/map-data/marks-store.ts`'s own upsert-by-id fold over `mapEvents`). Every
 * action (`REPORTED` a first sight or a re-sight; `REGISTERED`/`DISMISSED`/`RESTORED` a status
 * change) carries the candidate's own full current shape, so this never needs to special-case by
 * `action` — it is always exactly "replace this id, or append it if new". A candidate this poll
 * hasn't fetched yet (a brand-new `REPORTED` arriving between two polls) is appended, keeping it
 * visible immediately rather than waiting up to the poll interval to appear.
 */
export function applyDiscoveryEvents(
  candidates: readonly DiscoveryCandidate[],
  events: readonly DiscoveryEventPayload[],
): readonly DiscoveryCandidate[] {
  if (events.length === 0) {
    return candidates;
  }
  const byId = new Map(candidates.map((candidate) => [candidate.id, candidate] as const));
  for (const event of events) {
    byId.set(event.candidate.id, event.candidate);
  }
  return [...byId.values()];
}
