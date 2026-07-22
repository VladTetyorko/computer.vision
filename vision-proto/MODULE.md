# vision-proto

Java gRPC codegen (grpc-java) from the shared `proto/vision/v1/cv.proto` contract — the Java↔Python CV service wire format.

**Depends on:** io.grpc:grpc-{netty-shaded,protobuf,stub}, com.google.protobuf:protobuf-java, javax.annotation-api · **Used by:** adapter-cv-grpc
**Build/test:** `./mvnw -B -pl vision-proto test` (no hand-written sources or tests — codegen + compile only)

## API surface
No hand-written Java — everything under `com.drones.vision.proto.v1` is generated into `target/generated-sources/protobuf/{java,grpc-java}` from `proto/vision/v1/cv.proto` (`java_multiple_files = true`, `java_package = "com.drones.vision.proto.v1"`):
- Messages: `FrameRequest`, `BoundingBox`, `Detection`, `DetectionResponse`, `TrainingJobSpec`, `TrainingProgress`, `ModelInfo`, `ModelList`, `ModelRefMsg`, `Ack` (each with a generated `*OrBuilder` and `.Builder`).
- Enums: `ImageEncoding` (`UNSPECIFIED`/`JPEG`/`BGR24`), `JobState` (`UNSPECIFIED`/`RUNNING`/`SUCCEEDED`/`FAILED`).
- gRPC stubs: `InferenceGrpc` and `TrainingGrpc`, each with `*ImplBase`, `*Stub`, `*BlockingStub`, `*FutureStub`, and an `*.AsyncService` interface (grpc-java 1.64's async-service pattern).
- Proto services: `Inference.DetectStream(stream FrameRequest) returns (stream DetectionResponse)`; `Training.StartTraining(TrainingJobSpec) returns (stream TrainingProgress)`, `Training.ListModels(google.protobuf.Empty) returns (ModelList)`, `Training.PromoteModel(ModelRefMsg) returns (Ack)`.

## Conventions
- `protoSourceRoot` points at `../proto` (repo-root `proto/vision/v1/cv.proto`) — **not** a copy under this module; it is the single source of truth shared with `cv-service`'s Python codegen (`scripts/gen_proto.sh`). Never duplicate or fork the `.proto` file.
- Codegen via `org.xolstice.maven.plugins:protobuf-maven-plugin` (`${protobuf-maven-plugin.version}`=0.6.1), goals `compile` + `compile-custom` (the latter drives the `grpc-java` plugin, `pluginId=grpc-java`). `os-maven-plugin` (`${os-maven-plugin.version}`=1.7.1) resolves `${os.detected.classifier}` to pick the right native `protoc`/`protoc-gen-grpc-java` executables.
- Version pins (root `pom.xml` properties): `protobuf.version`=3.25.5 (both `protoc` and `protobuf-java`), `grpc.version`=1.64.0.

## Gotchas
- Regenerate by rebuilding this module (any goal that runs `generate-sources`, e.g. `./mvnw -pl vision-proto compile`) — nothing under `target/generated-sources` is hand-maintained; never edit generated files directly.
- Java's protobuf runtime stays on the `3.25.x` version line while Python's (`cv-service/pyproject.toml`) pins `protobuf>=5.26.1,<6` — different-looking major versions for the **same** underlying release generation (protobuf unified its cross-language version numbering; Java/C++ kept the legacy `3.25.x` line for that generation while Python jumped to `5.26.x`). Not a real skew, and not something to "fix" — both consume the same `.proto` and produce the same wire format.

## Status
Fully implemented (Task T3) — codegen wiring only, no adapter logic here (that lives in `adapter-cv-grpc`, Phase 2).
