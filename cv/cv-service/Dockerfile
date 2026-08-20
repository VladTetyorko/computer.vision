# Build from the repository root so the shared proto/ directory (single
# source of truth for both vision-proto and cv_service/gen) is available in
# the build context:
#
#   docker build -f cv/cv-service/Dockerfile -t cv-service .
#
# (docker-compose: use `context: .` + `dockerfile: cv/cv-service/Dockerfile`
# for the cv-service service.)
FROM python:3.12-slim

WORKDIR /app

# Shared gRPC/protobuf contract.
COPY proto/ ./proto/
COPY cv/cv-service/ ./cv-service/

WORKDIR /app/cv-service

# opencv-python (pulled in transitively by ultralytics -- see pyproject.toml
# NOTE in the `cv` extra) needs a handful of shared libraries at import time
# that python:3.12-slim doesn't ship (libGL, glib); without these, `import
# cv2` fails with "libGL.so.1: cannot open shared object file".
RUN apt-get update \
    && apt-get install --no-install-recommends -y libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# Install the base package plus the `cv` extra (Ultralytics YOLO, plus the
# now-explicit opencv-python and lap pins -- see pyproject.toml) plus
# `openvino` (the export/runtime backend used ONLY in this image --
# deliberately NOT added to the `cv` extra in pyproject.toml, so local/dev
# installs stay on plain PyTorch `.pt` weights AND so invariant P1 stays
# true; see cv/cv-service/MODULE.md Gotchas). `ultralytics` also pulls `torch`
# transitively; --extra-index-url is additive (pip still resolves everything
# else from PyPI) and steers pip to the CPU-only PyTorch wheels so the image
# doesn't balloon with CUDA libraries this CPU-first service never uses.
#
# TRACKING ENGINES NEED NO NEW INSTALL STEP HERE -- verified, not assumed.
# The three shipped engines (docs/plans/done/TRACKING-PLAN.md §5.B) are `bytetrack`
# (ultralytics' own BYTETracker + `lap`, both in the `cv` extra) and `lk` /
# `ncc`, which use only core-OpenCV `video`/`imgproc` entry points
# (`calcOpticalFlowPyrLK`, `goodFeaturesToTrack`, `matchTemplate`,
# `minMaxLoc`, `cvtColor`) that plain opencv-python already ships -- checked
# against the installed wheel, and `cv2.getBuildInformation()` confirms the
# `video` module is built in. No contrib package, no ONNX asset, no extra
# apt library beyond the libgl1/libglib2.0-0 already installed above.
#
# --retries/--timeout: the wheel set is ~400MB (torch+opencv+openvino); on a
# slow or flaky uplink pip's defaults (5 retries, 15s read timeout) abort the
# whole layer for one stalled chunk, restarting the download from zero.
#
# `.[cv,geo]` (VISUAL-GEO-V2-PLAN.md H4): the `geo` extra is self-referential on
# `cv` (see pyproject.toml) so this single `-e ".[cv,geo]"` installs both in one
# resolve -- torch/torchvision/kornia ride the same CPU wheel index as
# ultralytics' own torch pull, so there is no second `--extra-index-url` needed.
# Unlike the YOLO weights baked in below, the geo encoder/matcher backbones
# (`eigenplaces`/`cosplace` via `torch.hub`, `xfeat` via `torch.hub`) are NOT
# fetched at build time here -- they are fetched lazily, once, on first
# `GeolocationServicer` construction (server.py's `serve()`), and cached under
# CV_GEO_MODEL_CACHE. Baking them in would mean every image rebuild re-fetches
# ~100s of MB from GitHub for a feature not every deployment target uses (the
# echo/no-geo-extra install path stays valid, see cv_service/geo/encoder.py's
# ImportError message); the tradeoff is that the FIRST `BuildReferenceIndex` or
# `LocalizeStream` call after a fresh container start needs network egress and
# pays the download latency once. If that first-call network dependency proves
# unacceptable for the offline GB4005 box, mount CV_GEO_MODEL_CACHE as a
# persistent volume pre-seeded from a machine that does have network, exactly
# like the YOLO `.pt`/OpenVINO IR baked into THIS image today.
RUN pip install --no-cache-dir --retries 15 --timeout 120 \
      --extra-index-url https://download.pytorch.org/whl/cpu \
      -e ".[cv,geo]" openvino \
    && ./scripts/gen_proto.sh

# CV_GEO_MODEL_CACHE: where torch.hub lands the encoder/matcher weights on
# first use (see comment above) -- set explicitly rather than relying on
# config.py's default (`<repo>/.model-cache`) so the cache directory's location
# is visible from the image definition and independent of Settings.geo_model_cache's
# fallback logic changing later. Not volume-mounted here (no VOLUME directive) --
# the image's own writable layer is fine for a demo/single-instance deployment;
# an operator who wants the cache to survive a container replace mounts this
# path externally at `docker run`/compose time.
ENV CV_GEO_MODEL_CACHE=/app/cv-service/.model-cache

# DEFERRED (docs/plans/done/TRACKING-PLAN.md §5.B, decision D14): the ONNX-backed
# single-object trackers `cv2.TrackerNano` and `cv2.TrackerVit`. They exist
# as symbols in opencv-python 5.x but raise on construction because the
# package ships no weights for them, which is exactly why `TrackerRegistry`
# probes constructibility at startup and drops what does not build. Adding
# them later is a file drop plus a registry entry, not a redesign -- uncomment
# the fetch below and register the engine ids. Left commented deliberately:
# shipping ~15MB of model assets nobody routes to yet would grow the image
# for a capability `lk` at ~0.5 ms/frame already covers with ~20x headroom.
#
# RUN curl -fsSL -o nanotrack_backbone_sim.onnx \
#       https://github.com/opencv/opencv_zoo/raw/main/models/object_tracking_nanotrack/object_tracking_nanotrack_backbone_sim_2023sep.onnx \
#  && curl -fsSL -o nanotrack_head_sim.onnx \
#       https://github.com/opencv/opencv_zoo/raw/main/models/object_tracking_nanotrack/object_tracking_nanotrack_head_sim_2023sep.onnx \
#  && curl -fsSL -o vittrack.onnx \
#       https://github.com/opencv/opencv_zoo/raw/main/models/object_tracking_vittrack/object_tracking_vittrack_2023sep.onnx
#
# NOTE: this image stays SINGLE-ARCH (linux/amd64) on purpose -- no buildx
# multi-arch target (docs/plans/done/TRACKING-PLAN.md §3.3.3, decision D9). Its whole
# reason to exist is the OpenVINO IR export below, an Intel-CPU
# optimization; maintaining a second architecture of a ~1.5GB image whose
# defining feature does not apply to that architecture is cost with no
# payoff. ARM deploys by `pip install -e '.[cv]'` + rsync instead -- the
# same pattern the GB4005 box already uses. See cv/cv-service/MODULE.md,
# "Running on ARM64 / a companion computer".

# Default model: download the PyTorch weights, then export to OpenVINO IR
# (~2-3x faster CPU inference than plain PyTorch -- see
# cv_service/inference/detector.py CP-a). `yolo export` writes the IR to
# `<stem>_openvino_model/` in the current directory (same download-to-cwd
# behavior as the plain YOLO() load below); CV_MODEL then points at that
# directory, which `ultralytics.YOLO(...)` loads transparently, same call
# shape as a `.pt` path.
#
# COUPLING (must not drift): an OpenVINO-exported model has a FIXED input
# size baked in at export time. The `imgsz` passed to `yolo export` below
# MUST equal the runtime CV_IMGSZ (cv_service/config.py's DEFAULT_IMGSZ,
# currently 416) -- if you change one, change both, or inference silently
# runs against the wrong size. Local/dev (non-Docker) keeps the plain `.pt`
# weights, which have no such fixed-size constraint.
#
# Baking both the `.pt` source and the exported IR into the image means the
# container needs no network access at runtime. If this export step ever
# proves unbuildable (network/size), drop the `yolo export` line and leave
# CV_MODEL=yolo11n.pt to fall back to plain PyTorch inference -- the server
# will still start and detect correctly, just slower.
ENV CV_IMGSZ=416
RUN python -c "import os; from ultralytics import YOLO; YOLO('yolo11n.pt')" \
    && yolo export model=yolo11n.pt format=openvino imgsz=${CV_IMGSZ} \
    && test -d yolo11n_openvino_model
ENV CV_MODEL=yolo11n_openvino_model
# CV_DEVICE selects the explicit inference device (see cv_service/config.py's
# Settings.device / cv_service/inference/detector.py's YoloDetector) -- left
# unset here on purpose: this image runs OpenVINO CPU inference, and
# OpenVINO's own device strings ("CPU"/"GPU"/"AUTO") aren't the same
# namespace as PyTorch/Ultralytics CUDA device strings ("cuda:0"), so setting
# one here would be misleading, not just redundant. For a CUDA-capable
# deployment (plain PyTorch `.pt` weights, not this image -- see
# cv/cv-service/DEPLOY-GPU.md), uncomment and set e.g.:
# ENV CV_DEVICE=cuda:0

EXPOSE 50051

CMD ["python", "-m", "cv_service.grpc.server"]
