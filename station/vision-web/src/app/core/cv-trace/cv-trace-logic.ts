import type { FrameLedger } from '../api/models';

/**
 * Pure logic behind `cv-trace-store.ts` (docs/plans/active/CV-ORCHESTRATION-PLAN.md §4.4/§4.8,
 * wave W5.2) — split out so the client-side ring's merge/cap behavior is unit-testable with no
 * `TestBed`, mirroring this app's standing `*-logic.ts`/store split (`geo-logic.ts`, `live-fallback-logic.ts`).
 */

/**
 * Matches the server's own `DEFAULT_TRACE_LAST` (`StreamController`'s `GET .../cv/trace?last=N`,
 * `station/vision-api/MODULE.md`'s own endpoint row) — keeping the two in step means this store's
 * ring never diverges in size from what the next poll's authoritative resync would replace it
 * with anyway.
 */
export const DEFAULT_CV_TRACE_LAST = 50;

/**
 * Merges one live `cv-trace:<assetId>` arrival into the accumulated ring, oldest-first, capped at
 * `cap` — the client-side mirror of the server's own `FrameLedgerRing` capacity. Keyed by
 * `sequence`: a re-delivered frame (the same arrival observed twice, or one the periodic poll's
 * authoritative resync already carried) replaces its existing entry in place rather than
 * duplicating it; a genuinely new frame is appended and, if that pushes the ring past `cap`, the
 * oldest entries are dropped from the front — never the newest, since a live inspector always
 * wants to see what *just* happened over what happened `cap` frames ago.
 */
export function appendFrameLedger(
  existing: readonly FrameLedger[],
  incoming: FrameLedger,
  cap: number,
): readonly FrameLedger[] {
  const withoutDuplicate = existing.filter((frame) => frame.sequence !== incoming.sequence);
  const merged = [...withoutDuplicate, incoming].sort((a, b) => a.sequence - b.sequence);
  return merged.length > cap ? merged.slice(merged.length - cap) : merged;
}
