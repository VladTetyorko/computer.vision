# cv-service

Python gRPC CV inference & training service — Phase 0 skeleton (echo-stub inference, no model yet).

**Depends on:** grpcio, grpcio-tools, protobuf (Python); shared contract `proto/vision/v1/cv.proto` · **Used by:** adapter-cv-grpc (Phase 2, not wired yet)
**Build/test:** `python -m cv_service.server` (run); no automated test suite yet. Codegen: `scripts/gen_proto.sh` (regenerates `cv_service/gen/`, not committed).

## API surface
### `cv_service/server.py`
- `class InferenceServicer(cv_pb2_grpc.InferenceServicer)` — `DetectStream(request_iterator: Iterable[FrameRequest], context) -> Iterator[DetectionResponse]`: **working echo stub** — for every incoming `FrameRequest` yields a `DetectionResponse` copying `stream_id`/`sequence`/`timestamp_millis`/`model_id`/`model_version`, with `detections=[]` and `inference_millis=0`. No model runs yet (Phase 2).
- `class TrainingServicer(cv_pb2_grpc.TrainingServicer)` — `StartTraining`, `ListModels`, `PromoteModel` each call `context.abort(grpc.StatusCode.UNIMPLEMENTED, ...)` (Phase 3).
- `def serve(port: int = DEFAULT_PORT) -> grpc.Server` — builds a `ThreadPoolExecutor(max_workers=10)`-backed server, registers both servicers, binds insecure `[::]:{port}` (`DEFAULT_PORT`=50051), starts it, returns it.
- `def main() -> None` — configures logging, calls `serve()`, installs `SIGTERM`/`SIGINT` handlers that call `server.stop(grace=5)`, blocks on `wait_for_termination()`.

## Conventions
- Generated stubs live under `cv_service/gen/` and are **never committed** (`.gitignore`) — run `scripts/gen_proto.sh` after cloning and whenever `proto/vision/v1/cv.proto` changes.
- `server.py` inserts `cv_service/gen` onto `sys.path` at import time (before importing `vision.v1.cv_pb2`/`cv_pb2_grpc`) and raises a clear `ModuleNotFoundError` pointing at `gen_proto.sh` if the generated tree is missing.

## Gotchas
- **`sys.path` gotcha**: `protoc`'s Python codegen emits imports rooted at the proto package path (`from vision.v1 import cv_pb2`), not at `cv_service.gen.vision.v1...` — so the generated tree's *root* (`cv_service/gen`, which contains the `vision/` package) must be on `sys.path`, not `cv_service/gen/vision/v1`. `gen_proto.sh` also `touch`es an `__init__.py` in every generated directory so the whole tree imports as regular (non-namespace) packages.
- **protobuf version-scheme note**: `pyproject.toml` pins `protobuf>=5.26.1,<6` while the Java side (`vision-proto`) pins `protobuf-java` 3.25.5 — looks like a major-version mismatch but isn't: protobuf unified its cross-language version numbering starting with this release generation, and Java/C++ stayed on the legacy `3.25.x` line for it while Python jumped to `5.26.x`. Both generate/consume the same wire format from the same `.proto`; don't "fix" this to match.
- **Dockerfile build context**: must be built from the **repository root**, not from `cv-service/`, because it does `COPY proto/ ./proto/` before `COPY cv-service/ ./cv-service/` — `docker build -f cv-service/Dockerfile -t cv-service .` (repo root as context, or `context: .` / `dockerfile: cv-service/Dockerfile` in compose). Running `docker build` from inside `cv-service/` fails to find `../proto`.

## Status
Phase 0 skeleton: `Inference.DetectStream` works end-to-end as an echo (proves the Java↔Python gRPC wiring), `Training` is stubbed `UNIMPLEMENTED`. Real inference (Ultralytics YOLO, behind the `cv` optional dependency group in `pyproject.toml`) lands in Phase 2; `Training` lands in Phase 3. No automated tests yet.
