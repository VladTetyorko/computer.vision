# cv-service

Python gRPC service for computer-vision inference and model training.
`Inference.DetectStream` runs real Ultralytics YOLO inference when the `cv`
optional dependency group is installed and the model loads successfully;
otherwise it falls back to the original echo stub (empty detections) so the
service never crash-loops for lack of a model. `Training` rpcs still return
`UNIMPLEMENTED` (Phase 3) - see [`../ARCHITECTURE.md`](../ARCHITECTURE.md)
and [`../docs/MVP1-PLAN.md`](../docs/MVP1-PLAN.md) §C7.

The gRPC contract lives in one place, shared with the Java side
(`vision-proto`): [`../proto/vision/v1/cv.proto`](../proto/vision/v1/cv.proto).

## Setup

```bash
cd cv-service
python3 -m venv .venv
source .venv/bin/activate
pip install -e .                # base: gRPC echo only
pip install -e '.[cv]' \
  --extra-index-url https://download.pytorch.org/whl/cpu   # + real YOLO inference (CPU torch)
```

The default model is `yolo26n.pt` (overridable via the `CV_MODEL` env var).
Ultralytics downloads it to the current working directory on first use if
not already present there.

## Generate protobuf/gRPC stubs

Generated code is **never committed** (see `.gitignore`); generate it after
cloning and whenever `proto/vision/v1/cv.proto` changes:

```bash
scripts/gen_proto.sh
```

This runs `python -m grpc_tools.protoc` against `../proto`, writing Python
stubs to `cv_service/gen/vision/v1/` (`cv_pb2.py`, `cv_pb2_grpc.py`,
`cv_pb2.pyi`).

## Run the server

```bash
python -m cv_service.grpc.server
```

Starts a gRPC server on `:50051` (overridable via `CV_PORT`). Shuts down
gracefully on `SIGTERM`/`SIGINT`. If you see a `ModuleNotFoundError`
mentioning `cv_service.gen`, run `scripts/gen_proto.sh` first.

## Tests

```bash
pip install -e '.[dev]'      # pytest
scripts/test.sh               # or: python -m pytest
```

`tests/test_inference.py`/`test_server.py` are mock-based (need the `cv`
extra's opencv-python/numpy, but not `ultralytics`/`torch` - they inject fake
model doubles). `tests/test_real_model.py` runs real inference and is
auto-skipped unless `ultralytics` is importable and its default weights load.

## Docker

Build from the **repository root**, not from `cv-service/` - the Dockerfile
does `COPY proto/ ./proto/` before `COPY cv-service/ ./cv-service/`:

```bash
cd ..    # repo root
docker build -f cv-service/Dockerfile -t cv-service .
docker run --rm -p 50051:50051 cv-service
```

The image installs the `cv` extra (CPU-only PyTorch wheels) and pre-downloads
the default YOLO weights at build time, then runs `gen_proto.sh` and starts
the server - the proto directory (`../proto`) is copied into the build
context, so no generated code (or model weights) is baked into source
control on either side.
