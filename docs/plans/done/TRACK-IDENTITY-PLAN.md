# TRACK-IDENTITY-PLAN — the name stops flapping

Status: **MERGED to master 2026-08-22** (`2484d6ab`; waves individually L3 `34f528ed`, L1 `899fb838`, L2 `e25c9b03`, L4 `6176a44e` — verified `git merge-base --is-ancestor`, 2026-09-04). Originally authored 2026-08-20. Executes the owner-approved
recommendation of `TRACK-IDENTITY-RESEARCH.md` (§3): L1 label election, L2 association
hardening, L3 SPA stability, L4 FOLLOW memory path. L5 stays deferred.

**Branch:** `feat/track-identity`, based on `feat/cv-clean-feed` (L3 builds on the W4/W7 tier
renderer; merge order: clean-feed first or this branch carries it). One commit per wave,
committed by the orchestrator.

**Wire contract: frozen.** No proto edit, no Java edit, no REST change in any wave. L1 changes
*which string* cv-service puts in the existing `Detection.label` field — that is the entire
externally-visible surface. (V2's D5 rule stands: additive scalars would be allowed, but no wave
here needs one.)

**Governing doctrine:** TRACKING-PLAN R9 ("a flipping label is cosmetic — do not special-case")
is **overturned for open-vocabulary models** by this plan; the two code comments citing it
(`assign.py`, `bytetrack.py`) get a pointer to this plan. Election is the special-casing R9
declined, now owner-ordered.

## L1 — track-level label election (cv-service)

Scope: `cv/cv-service/cv_service/tracking/track.py`, `session.py`, `config.py`/`params.py`
(new knobs), tests, `cv/cv-service/MODULE.md`.

1. Each confirmed track keeps a bounded per-label tally over its recent observations —
   confidence-weighted votes with exponential decay, so an old identity fades rather than
   anchors forever. Knobs (config.py + params.py idiom, **no magic numbers**):
   `CV_TRACK_LABEL_VOTE_WINDOW` (observations retained, default 10),
   `CV_TRACK_LABEL_SWITCH_MARGIN` (challenger must exceed incumbent × margin, default 1.5),
   `CV_TRACK_LABEL_SWITCH_STREAK` (consecutive passes the challenger must lead, default 3).
2. `track.elected_label`: starts as the first confirmed observation's label; switches only when
   the margin AND streak conditions hold. `track.label` (raw) keeps existing semantics for the
   matcher; the *elected* label is what leaves the service.
3. Emission: `session.py`'s `_box_for` uses the elected label for tracked detections (raw label
   for untracked); `_from_track` (coast) already echoes the track — it echoes the elected label
   now. `ObjectMemory.remember/match` and `assign.py` candidates offer the elected label
   (stability compounds: recovery gates and the L2 label penalty both get a stable operand).
4. Reset rules: election state clears with the track book epoch (model re-arm), never survives
   an id retirement except through `ObjectMemory` adoption (the recovered track resumes its
   elected label and tally).
5. Tests: election/hysteresis unit tests (incumbent holds under alternating noise; legitimate
   change switches after streak; decay forgets; coast echoes elected; adoption resumes);
   `tools/trackeval` replayed before/after — id-switches and fragmentation must not regress,
   and a new label-flip counter (flips per track per minute) must drop by an order of magnitude
   on the synthetic noisy-label sequence (add one if the harness lacks it).

## L2 — association hardening, measured (cv-service) — after L1

Scope: `cv_service/config.py` defaults, `docker-compose.yml` comment block if envs are
documented there, `tools/trackeval` runs, `BASELINE.md`, MODULE.md.

- `DEFAULT_TRACK_COST_WEIGHT_LABEL` 0.0 → **0.3** (soft penalty — never a hard gate; a gate
  would split tracks on residual flips, the exact failure R9 feared).
- `DEFAULT_TRACK_COST_GATE_MIN_IOU` 0.0 → **0.05** (a track may no longer absorb a detection it
  doesn't even touch; ego-motion warping runs before the gate, so fast pans stay matchable).
- `DEFAULT_TRACK_COST_GATE_MAX_COST` inf → **1.5** starting point (full cost range is
  1.0·(1−iou) + 0.5·appearance + 0.3·label ≤ 1.8; the ceiling forbids worst-of-everything
  pairings). Tune against trackeval; the acceptance bar is BASELINE.md non-regression on
  id-switches/fragmentation/track-life plus the L1 flip counter still improved.
- `_labels_compatible` keeps its composite-prefix tolerance unchanged.
- Record final numbers + reasoning in BASELINE.md; stale-doc fixes ride along
  (`config.py:114-120` engine-default comment; note `TrackState.java` javadoc defect for a
  Java-side doc fix in a later task — no Java edits here).

## L3 — SPA label stability (web) — parallel with L1

Scope: `station/vision-web/**` only: `shared/player/detection-overlay-logic.ts` (+spec),
`shared/player/detections-strip-logic.ts` (+spec), consumers as needed, MODULE.md.

1. **Client-side sticky labels per track** (stopgap + defense for not-yet-updated cv-service
   deployments): a pure function elects a display label per track id over the batches
   `DetectionsStore` already holds (confidence-weighted majority, switch-margin hysteresis
   mirroring L1's semantics; constants doc-cited to the L1 knobs so the two ends state the same
   contract). Painted text, `classBucket` hue, and tooltips all consume the sticky label —
   the box colour stops churning because the label feeding it stopped churning. (Deliberate
   deviation from research L3's "restore per-track hash hue": W4's class-bucket hue semantics
   are kept; stability comes from stabilizing the input, not from abandoning the encoding.)
2. **Strip chips over a sliding window**: aggregate the last `STRIP_WINDOW_SECONDS` (default 5)
   of batches instead of the newest batch only — counts become max-concurrent-per-class in the
   window; chips stop blinking in and out at batch cadence. Hover/click semantics unchanged.
3. Untracked detections keep raw labels (no identity to elect over).
4. Tests: election mirror cases from L1 plus window aggregation; `npm run test:ci` + tsc +
   prod build green, foreground.

## L4 — FOLLOW memory path (cv-service) — after L1

Scope: `cv_service/tracking/session.py`, `lock.py`, `memory.py` (if a hook is missing),
`config.py` if a knob is needed, tests, MODULE.md.

1. When the followed track settles LOST (`_settle_followed`), remember it to `ObjectMemory`
   (descriptor, last box, elected label, timestamp) before unbinding — today FOLLOW's loss is
   invisible to the gallery.
2. In the re-acquire loop, when `_box_of_track` finds the operator's target id expired, consult
   `ObjectMemory.match` with the current detections (same four gates as ASSOCIATE recovery:
   label-compatible, appearance ≤ threshold, motion-plausible, TTL). A match rebinds the lock
   and adopts the original id — the operator's lock survives occlusion without a re-click.
   No match → keep coasting toward the honest `-1`, exactly as today.
3. `DEFAULT_FOLLOW_TOP_K` 1 → **2**, gated on the follow bench (FOLLOW must stay within its
   verify-pass time budget on the GB4005 profile; if it doesn't, keep 1 and record why).
4. Tests: lost-then-reappear FOLLOW scenario recovers the lock without a new lock_seq;
   TTL expiry does not; label-incompatible impostor does not steal the lock.

## Acceptance (whole plan)

cv-service pytest green per wave; trackeval BASELINE.md updated with before/after; web 
test:ci/tsc/prod green; MODULE.md per touched module; no Java diff in `git diff --stat` against
branch base outside docs/.
