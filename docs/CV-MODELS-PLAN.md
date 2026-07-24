# CV-MODELS-PLAN — two local models, together

Status: **in execution** (2026-07-24). User direction: two model checkpoints exist locally in
`cv-service/` — `yolo11n.pt` (general, 5.6 MB) and `orion12l.pt` (military-vehicle YOLO12-L,
53 MB, user-provided) — "maybe we can use both of them same time?" → yes: a model registry plus
a composite mode that runs both on the same frame and merges detections.

Provenance note: an earlier draft (quarantined in the session scratchpad) had a Dockerfile
*download* `orion12n.pt` from a third-party repo at build time — rejected: `.pt` loading is pickle
deserialization, and remote fetch at build time is an untrusted-code vector. This plan uses ONLY
the two local, user-provided files; images/deployments COPY them from the repo checkout. No
network fetch of weights anywhere.

## Design

1. **cv-service registry (Python)** — replace the single hard-loaded `YoloDetector` with a
   lazy registry `{model_id → YoloDetector}`:
   - Allowed ids = local `*.pt`/exported-dir names present in the service directory (plus the
     existing OpenVINO default). Unknown id → current behavior (log once, serve default) so old
     clients never break.
   - `DetectionRequest.model_id` (already on the wire, currently log-and-ignore) routes to the
     registry. Lazy load on first use; each model pays its warmup once; memory documented.
2. **Composite mode** — `model_id` accepts a comma-separated list (`"yolo11n.pt,orion12l.pt"`):
   run each model on the frame, concatenate detections (each keeps its own class labels; a
   `model` tag rides in the existing detection metadata if the proto has a free field — check
   `cv.proto`; if not, prefix labels `mil:`/keep as-is and document). Confidence filtering stays
   per-model. Latency = sum of members (documented; the InferenceGate already bounds in-flight).
3. **Wiring (Java)** — verify `PipelineConfig.model` flows into `DetectionRequest.model_id`
   (vision-application → adapter-cv-grpc). If it already does, zero Java changes for routing;
   only the settings surface needs the new choices.
4. **UI** — the detection-settings profile picker (Tier-2 knobs) gains a Model choice:
   `General (yolo11n)` / `Military (orion12l)` / `Both` → writes the (composite) model string
   into the profile. Overlay: distinct box hue per source model when composite (only if the tag
   from item 2 exists; otherwise same hue, deferred).
5. **Out of scope now**: training/promotion (Phase 3 studio), hot per-frame model swapping
   beyond profile changes (profiles already re-apply on stream restart), remote model registry.

## Verification

cv-service pytest: registry routing, composite merge, unknown-id fallback. Live: start a stream
with each mode; `Both` shows both classsets; latency badge/lag summaries stay sane. Document
per-model warmup + RAM in cv-service/MODULE.md.
