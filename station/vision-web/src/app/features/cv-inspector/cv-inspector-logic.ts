import type { FrameLedger, SubsystemStatus, SystemStatus, WorldObject } from '../../core/api/models';

/**
 * Pure, Angular-free logic behind `cv-inspector-facade.ts` (docs/plans/active/CV-ORCHESTRATION-PLAN.md
 * §4.4/§4.8, wave W5.3) — joining `CvTraceStore`'s three independent ledgers (`gate`/`frame`/`world`)
 * into the shapes the inspector's per-frame and per-object panels actually render, the same
 * `*-logic.ts`/store split every other feature in this app follows (`geo-logic.ts`, `cv-trace-logic.ts`).
 */

/**
 * The most recently captured frame in the ring, or `undefined` while it is empty. `CvTraceStore.frame`
 * is documented oldest-first (its own class doc, and `cv-trace-logic.ts#appendFrameLedger`'s ascending
 * sort) — the last element is always the newest, whether it arrived via the poll's authoritative
 * resync or a live `cv-trace:<assetId>` merge.
 */
export function latestFrame(frames: readonly FrameLedger[]): FrameLedger | undefined {
  return frames.length === 0 ? undefined : frames[frames.length - 1];
}

/**
 * Every track id mentioned anywhere in the ring, union'd with every id `world` currently carries —
 * an object that fell out of the frame ring (no contributor touched it recently) but is still alive
 * in the world fold must stay selectable, and vice versa for one still accumulating evidence before
 * its first `WorldObject` exists. Sorted ascending, de-duplicated — a stable, deterministic dropdown
 * order rather than ledger arrival order.
 */
export function allTrackIds(frames: readonly FrameLedger[], world: readonly WorldObject[]): readonly number[] {
  const ids = new Set<number>();
  for (const frame of frames) {
    for (const key of Object.keys(frame.objects)) {
      ids.add(Number(key));
    }
  }
  for (const object of world) {
    ids.add(object.state.id);
  }
  return [...ids].sort((a, b) => a - b);
}

/** One flattened evidence row — a single contributor's claim about `trackId` on a single frame,
 *  the shape `evidenceRowsFor`'s table renders one row per. */
export interface EvidenceRow {
  readonly frameSequence: number;
  readonly capturedAtMillis: number;
  readonly contributorId: string;
  readonly claim: Readonly<Record<string, string>>;
}

/**
 * Flattens every `ObjectEvidence` recorded for `trackId` across the whole ring into one
 * chronological (oldest-first, matching `frames`' own order) list of rows — the answer to §1.5's
 * "what did the platform believe about this object, and who said so?" question, joined across every
 * frame still in the ring rather than just the latest one.
 */
export function evidenceRowsFor(frames: readonly FrameLedger[], trackId: number): readonly EvidenceRow[] {
  const rows: EvidenceRow[] = [];
  for (const frame of frames) {
    const evidences = frame.objects[String(trackId)];
    if (evidences === undefined) {
      continue;
    }
    for (const evidence of evidences) {
      rows.push({
        frameSequence: frame.sequence,
        capturedAtMillis: frame.capturedAtMillis,
        contributorId: evidence.contributorId,
        claim: evidence.claim,
      });
    }
  }
  return rows;
}

/** The current `WorldObject` fold for `trackId`, or `undefined` if the world model has never folded
 *  this id (a track only ever seen in the frame ledger, not yet — or no longer — in `world`). */
export function worldObjectFor(world: readonly WorldObject[], trackId: number): WorldObject | undefined {
  return world.find((object) => object.state.id === trackId);
}

/**
 * `claim`/`summary` (both `Readonly<Record<string, string>>`, genuinely free-form per contributor —
 * see `ObjectEvidence`/`LedgerEntry`'s own doc comments in `core/api/models.ts`) rendered as one
 * `key=value, key=value` line rather than a nested sub-table — the simplest presentation that still
 * shows every key a contributor chose to record, with no assumption about which keys exist.
 */
export function formatRecord(record: Readonly<Record<string, string>>): string {
  const entries = Object.entries(record);
  return entries.length === 0 ? '—' : entries.map(([key, value]) => `${key}=${value}`).join(', ');
}

/**
 * `atMillis`/`capturedAtMillis` as a local wall-clock time (`HH:MM:SS`) — a precomputed value the
 * template calls directly, rather than an Angular `date` pipe, so this stays testable with no
 * `TestBed`/`CommonModule` import, matching `core/audit/audit-logic.ts#absoluteTime`'s identical
 * "compute it here, the template just prints a string" convention.
 */
export function clockTime(epochMillis: number): string {
  return new Date(epochMillis).toLocaleTimeString();
}

/**
 * The `cv-service` row of `/api/system/status` — Ops' own process-facts surface (§4.8's audience
 * table) reused verbatim rather than re-derived: `CvStatusProvider` (cv/grpc) folds capacity/
 * occupancy/queue facts into this row's free-text `detail` deliberately, not as structured fields
 * (see that provider's own javadoc) — this inspector shows the same one sentence, not a second,
 * competing rendering of the same facts.
 */
export function cvSubsystemRow(status: SystemStatus | undefined): SubsystemStatus | undefined {
  return status?.subsystems.find((row) => row.id === 'cv-service');
}
