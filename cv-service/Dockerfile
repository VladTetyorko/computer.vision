# Build from the repository root so the shared proto/ directory (single
# source of truth for both vision-proto and cv_service/gen) is available in
# the build context:
#
#   docker build -f cv-service/Dockerfile -t cv-service .
#
# (docker-compose: use `context: .` + `dockerfile: cv-service/Dockerfile`
# for the cv-service service.)
FROM python:3.12-slim

WORKDIR /app

# Shared gRPC/protobuf contract.
COPY proto/ ./proto/
COPY cv-service/ ./cv-service/

WORKDIR /app/cv-service

# opencv-python (pulled in transitively by ultralytics -- see pyproject.toml
# NOTE in the `cv` extra) needs a handful of shared libraries at import time
# that python:3.12-slim doesn't ship (libGL, glib); without these, `import
# cv2` fails with "libGL.so.1: cannot open shared object file".
RUN apt-get update \
    && apt-get install --no-install-recommends -y libgl1 libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# Install the base package plus the `cv` extra (Ultralytics YOLO, which
# transitively pulls in opencv-python) plus `openvino` (the export/runtime
# backend used ONLY in this image -- deliberately NOT added to the `cv`
# extra in pyproject.toml, so local/dev installs stay on plain PyTorch `.pt`
# weights; see cv-service/MODULE.md Gotchas). `ultralytics` also pulls
# `torch` transitively; --extra-index-url is additive (pip still resolves
# everything else from PyPI) and steers pip to the CPU-only PyTorch wheels
# so the image doesn't balloon with CUDA libraries this CPU-first service
# never uses.
# --retries/--timeout: the wheel set is ~400MB (torch+opencv+openvino); on a
# slow or flaky uplink pip's defaults (5 retries, 15s read timeout) abort the
# whole layer for one stalled chunk, restarting the download from zero.
RUN pip install --no-cache-dir --retries 15 --timeout 120 \
      --extra-index-url https://download.pytorch.org/whl/cpu \
      -e ".[cv]" openvino \
    && ./scripts/gen_proto.sh

# Default model: download the PyTorch weights, then export to OpenVINO IR
# (~2-3x faster CPU inference than plain PyTorch -- see cv_service/inference.py
# CP-a). `yolo export` writes the IR to `<stem>_openvino_model/` in the
# current directory (same download-to-cwd behavior as the plain YOLO() load
# below); CV_MODEL then points at that directory, which `ultralytics.YOLO(...)`
# loads transparently, same call shape as a `.pt` path.
#
# COUPLING (must not drift): an OpenVINO-exported model has a FIXED input
# size baked in at export time. The `imgsz` passed to `yolo export` below
# MUST equal the runtime CV_IMGSZ (cv_service/inference.py's DEFAULT_IMGSZ,
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

EXPOSE 50051

CMD ["python", "-m", "cv_service.server"]
