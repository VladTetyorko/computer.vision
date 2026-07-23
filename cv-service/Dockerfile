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
# transitively pulls in opencv-python). `ultralytics` also pulls `torch`
# transitively; --extra-index-url is additive (pip still resolves everything
# else from PyPI) and steers pip to the CPU-only PyTorch wheels so the image
# doesn't balloon with CUDA libraries this CPU-first service never uses.
RUN pip install --no-cache-dir \
      --extra-index-url https://download.pytorch.org/whl/cpu \
      -e ".[cv]" \
    && ./scripts/gen_proto.sh

# Default model (overridable at runtime via CV_MODEL, see
# cv_service/inference.py). Pre-downloaded here so the container needs no
# network access at runtime -- if this ever bloats/flakes the build, drop
# this RUN line and the server will download the weights lazily on first
# use instead (same CV_MODEL env var), at the cost of first-request latency
# and requiring network egress from the running container.
ENV CV_MODEL=yolo11n.pt
RUN python -c "import os; from ultralytics import YOLO; YOLO(os.environ['CV_MODEL'])"

EXPOSE 50051

CMD ["python", "-m", "cv_service.server"]
