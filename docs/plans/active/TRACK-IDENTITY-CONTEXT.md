# TRACK-IDENTITY — task context

Task started: 2026-08-20. Owner ask (verbatim intent): "better tracking and following the object.
Now same object can change from plant to helicopter to electric chair. But it should not be like
this, investigate and doublecheck what we can do." Deliverable: investigation + ranked options
(research doc), implementation only after owner picks.

## Two distinct defects hiding in one sentence

- **Label instability**: one physical object's *class label* flips batch-to-batch
  ("plant → helicopter → electric chair" smells like open-vocab YOLOE prompt-free vocabulary).
- **Follow robustness**: "following the object" — FOLLOW lock quality across ID switches/occlusion.

## Investigation plan

1. Docs first (CLAUDE.md rule): TRACKING-PLAN, TRACKING-V2/V3, CV-RATE-CONTROL, CV-MODELS,
   perception + cv-service MODULE.md.
2. Code facts (Explore agent): where association happens, whether a track owns a label or borrows
   the newest detection's, what the UI paints, what smoothing exists, FOLLOW re-acquire behavior.
3. Write TRACK-IDENTITY-RESEARCH.md: diagnosis → options (client / perception / cv-service) →
   ranked waves with costs.

## Outcome (filled at close)

Delivered docs/plans/active/TRACK-IDENTITY-RESEARCH.md (2026-08-20): six-layer causal chain
(argmax over ~4585-class YOLOE vocab -> geometry-only association (w_label=0, min_iou=0,
max_cost=inf) -> unconditional label overwrite -> raw detection.label on wire -> Java books
verbatim -> SPA paints newest). TRACKING-PLAN R9 ("flip is cosmetic") re-opened. Second-order:
deny-list kills live Java tracks (drop precedes booking); FOLLOW re-acquire is geometric-only
with no ObjectMemory path. Options L1-L5 ranked; L1 (track-level label election in cv-service)
recommended first. Implementation awaits owner pick.

## Implementation (owner-approved same day, branch feat/track-identity off feat/cv-clean-feed)

- 74dbf8c2 plan · 34f528ed **L3** web sticky labels + 5s strip window (2414 tests)
- 899fb838 **L1** election on the wire — trackeval worst-case flips 110→7 (~16×), idsw byte-identical (1225 pytest)
- e25c9b03 **L2** w_label 0.3 + max_cost 1.5 shipped; min_iou 0.05 measured harmful at EVERY
  positive value (zero-overlap wide-displacement recovery) → reverted, trials in BASELINE.md §6
- 6176a44e **L4** FOLLOW memory path — lost lock re-acquires same id via ObjectMemory within TTL,
  no new lock_seq; impostor/TTL tests; follow_top_k 1→2 measured free (1238 pytest, trackeval 177)

Open: live /fly smoke of the whole ladder (rides with CV-CLEAN-FEED W6); L5 heavy tier deferred.
Not merged; merge order: feat/cv-clean-feed first (this branch bases on it).
