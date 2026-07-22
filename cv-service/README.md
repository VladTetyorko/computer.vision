# cv-service

Python gRPC service for computer-vision inference and model training. Phase
0 skeleton only: `Inference.DetectStream` is a working echo stub (empty
detections), `Training` rpcs return `UNIMPLEMENTED`. Real inference
(Ultralytics YOLO) and training land in later phases - see
[`../ARCHITECTURE.md`](../ARCHITECTURE.md) and
[`../docs/PHASE0-PLAN.md`](../docs/PHASE0-PLAN.md).

The gRPC contract lives in one place, shared with the Java side
(`vision-proto`): [`../proto/vision/v1/cv.proto`](../proto/vision/v1/cv.proto).

## Setup

```bash
cd cv-service
python3 -m venv .venv
source .venv/bin/activate
pip install -e .        # add ".[cv]" once Phase 2 inference deps are needed
```

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
python -m cv_service.server
```

Starts a gRPC server on `:50051`. Shuts down gracefully on `SIGTERM`/`SIGINT`.
If you see a `ModuleNotFoundError` mentioning `cv_service.gen`, run
`scripts/gen_proto.sh` first.

## Docker

```bash
docker build -t cv-service .
docker run --rm -p 50051:50051 cv-service
```

The image runs `gen_proto.sh` at build time, then starts the server - the
proto directory (`../proto`) is copied into the build context, so no
generated code is baked into source control on either side.
