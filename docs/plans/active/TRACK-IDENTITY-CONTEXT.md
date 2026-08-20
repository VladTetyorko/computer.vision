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
