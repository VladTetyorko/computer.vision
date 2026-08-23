# TRACK-IDENTITY-RESEARCH — why one object is a plant, then a helicopter, then an electric chair

Status: **investigation delivered** (2026-08-20), implementation awaits owner pick. Owner ask:
"better tracking and following the object — same object changes plant → helicopter → electric
chair; investigate and doublecheck what we can do."
Code facts verified 2026-08-20 against the tree (post-W7,
`feat/cv-clean-feed`); every claim below carries its citation.

## 1. Diagnosis — the six-layer causal chain, zero smoothing points

The track *identity* is stable (that was TRACKING-V2/V3's whole job). The *name* attached to the
identity is re-rolled on every detector pass, and every layer repeats the newest roll verbatim:

1. **Model** — `yoloe-26s-seg-pf.pt` (the "Everything — wide search" intent card) emits ONE argmax
   class per box per pass from a **~4585-class** synonym/scene vocabulary (measured by cv-service
   Wave A; `station/vision-web/MODULE.md` cites it). No score vector, no top-k, no temporal state
   (`cv_service/inference/detector.py:116-143`; `proto/vision/v1/cv.proto:176-191` carries one
   `string label`). An ambiguous blob rolls a 4585-sided die ~10×/s.
2. **Detector floor** — while tracking is on, the detector runs at `DEFAULT_DETECT_FLOOR = 0.15`
   and the operator's confidence slider becomes a *reporting* threshold (`config.py:163-187`) — so
   the associator sees far more low-confidence, label-unstable boxes than the slider implies.
3. **Associator** — the default `cost` matcher keeps the track alive across the class change
   *by design*: `CV_TRACK_COST_WEIGHT_LABEL = 0.0` (label disagreement costs nothing),
   `COST_GATE_MIN_IOU = 0.0`, `COST_GATE_MAX_COST = inf` (`config.py:138-161`). With the
   appearance extractor absent (below L2) **nothing is ever FORBIDDEN** — the Hungarian solver
   assigns every track to *some* detection whenever counts allow, regardless of overlap or class
   (`assign.py:151-168`, `session.py:862-863`).
4. **Track record** — `track.label = observation.label`, overwritten unconditionally every
   observation (`track.py:717-720`). `ObservationRing` stores boxes for ORU, never labels. And the
   wire doesn't even send `track.label`: tracked boxes are emitted with the **raw per-frame
   `detection.label`** (`session.py:2147-2178`); only coasted frames echo the track's last label.
5. **Java** — `TrackBook` books the newest observation verbatim, no label memory
   (`TrackBook.java:138-149`); `TrackResponse` documents "the latest observation's class label".
6. **SPA** — `formatTierLabel` paints `detection.label` (track contributes only the `#id` prefix),
   and `classBucketHue(detection.label)` **recolours the box with every flip**
   (`detection-overlay-logic.ts:985-999`). The per-track constant hue TRACKING-PLAN §"client"
   promised ("one object keeps one colour as its label flips") was deliberately removed in
   CV-CLEAN-FEED W4. The strip chips aggregate the newest batch only, so they churn in lockstep.

**Root ruling to overturn:** TRACKING-PLAN R9 declared a flipping label "cosmetic — do not
special-case", a judgment made for *composite-mode model prefixes* over a COCO-sized vocabulary.
`assign.py` and `bytetrack.py` both cite it verbatim. It was silently inherited by the 4585-class
prompt-free path, where the flip is semantic noise, not a naming variant. R9 is hereby re-opened.

## 2. Second-order defects the flip manufactures (found while double-checking)

- **D-A: deny-list kills live tracks.** `labelFilter`/`labelDenyFilter` drop *before* `TrackBook`
  booking (`StreamPipeline.java:1315-1361`) and never reach cv-service. A track whose label flips
  *into* the deny set goes silent in Java, is dropped after the 5 s retention, and re-books with a
  new `firstSeen` when the label flips back — label instability directly manufactures track churn
  in `/tracks`, the UI and the map trail.
- **D-B: FOLLOW re-acquire cliff.** The lock coasts and re-acquires **geometrically only**
  (`best_iou_match`, `lock.py:222-236`); once the lost track expires from the book,
  `_box_of_track` returns `None` and the lock is dead until the operator re-clicks
  (`session.py:2042-2044`). `ObjectMemory` (the dormant re-ID gallery, V2) is consulted only from
  ASSOCIATE's unmatched-target path — **FOLLOW has no memory path at all**.
- **D-C: appearance evidence is thin.** The only descriptor is a 16×8 HSV colour histogram (L2+,
  `engines/histogram.py`); OSNet/OpenVINO re-ID is TRACKING-V3 wave **V7 — not implemented**
  (V4, V5, V7, V8 all unbuilt; `assign.py` still has exactly iou/appearance/label).
- **D-D: stale docs.** `config.py:114-120` claims `cost` is not the default engine (it is);
  `TrackState.java:161-167` javadoc describes Java-side LOST retention that `TrackBook` explicitly
  does not do.

## 3. Options, ranked (what we can do)

### L1 — track-level label election in cv-service ⭐ recommended first
The track keeps a small per-label confidence-weighted tally (ring of last N observations);
the **elected** label is emitted on the wire for tracked detections, with hysteresis: the painted
name changes only when a challenger out-scores the incumbent by a margin over consecutive passes.
Coast keeps the elected label. Zero proto change (the `label` field simply becomes trustworthy),
zero Java change, zero SPA change — every downstream layer inherits the fix, **including D-A**
(elected labels don't flicker across the deny boundary). Honesty: an additive scalar
`label_confidence` (share of recent votes) can ride along for the UI later — additive proto
scalars need no Java edit (V2 D5 rule). Scope: `track.py`, `session.py:_box_for`, tests,
trackeval before/after. **Small wave, biggest payoff.**

### L2 — association hardening, measured
Raise defaults: `COST_WEIGHT_LABEL` 0.0 → ~0.3 (soft penalty, not a gate — a hard label gate
would *split* tracks on every flip, the exact reason R9 set 0), `COST_GATE_MIN_IOU` 0.0 → ~0.05,
`COST_GATE_MAX_COST` inf → finite. All are existing `CV_TRACK_*` env knobs — the wave is new
*defaults* in `config.py` + docker-compose, validated against `tools/trackeval` + `BASELINE.md`
so id-switches don't regress. Prevents the "track absorbs an unrelated detection" failure that
no amount of label voting can fix.

### L3 — SPA quick wins (pure frontend, ships regardless)
Restore the **per-track constant hue** (identity = colour, the promised mitigation W4 removed) —
label text may change, the object's colour never does; strip chips aggregate over a sliding
window instead of the newest batch; optional client-side label stickiness per track id as a
stopgap until L1 deploys (client already holds 50 batches).

### L4 — FOLLOW memory path (fixes D-B)
On followed-track loss, snapshot the target's descriptor + last box into `ObjectMemory`; the
re-acquire loop consults the gallery (same four gates as ASSOCIATE recovery: label-compatible,
appearance, motion-plausible, TTL) instead of dying when the track id expires. Raise
`follow_top_k` ≥ 2 so FOLLOW isn't scene-blind between verify passes. cv-service only.

### L5 — the heavy tier (defer until L1–L4 measured)
Top-k class candidates + score vector on the wire (additive proto) for smarter election;
OSNet/OpenVINO re-ID descriptor (V3 wave V7) on the GB4005 box; vocabulary canonicalization
(alias-map 4585 synonyms into stable buckets before they leave cv-service).

### Rides along at near-zero cost
Fix D-D's two stale comments; re-state R9 in TRACKING-PLAN as overturned-for-open-vocab.

## 4. Doublecheck — why not something simpler?

- *"Just raise the confidence slider"* — it's a reporting threshold while tracking runs (§1.2);
  the associator still sees the noise. Also hides real objects.
- *"Just deny-list the junk classes"* — 4585 classes, and D-A means every deny entry is a new
  track-churn trigger today. Deny-listing becomes safe *after* L1.
- *"Use the closed-set model"* — already the default (`yolo26n.pt`); the owner explicitly wants
  the wide-search card usable. L1–L3 make it usable.
- *"ByteTrack engine instead"* — it feeds class 0 for everything by explicit R9-citing design
  (`bytetrack.py:203-209`); same flip, fewer knobs.

## 5. Proposed shape if approved

One branch `feat/track-identity`. L1+L2 are cv-service waves (Python, trackeval-gated, no Java);
L3 is a web-ui wave (disjoint, parallel); L4 follows L1. Each wave: scoped tests green +
MODULE.md updated. Estimate: L1 ≈ one focused wave, L2 ≈ half (mostly measurement), L3 ≈ half,
L4 ≈ one.
