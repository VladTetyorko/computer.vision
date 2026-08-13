package com.drones.vision.adapter.cvgrpc;

import com.drones.vision.learning.domain.model.DatasetId;
import com.drones.vision.learning.domain.model.DatasetUpload;
import com.drones.vision.learning.domain.port.DatasetUploadPort;
import com.drones.vision.proto.v1.DatasetChunk;
import com.drones.vision.proto.v1.TrainingGrpc;
import com.drones.vision.proto.v1.UploadAck;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@link DatasetUploadPort} over the generated {@code Training/UploadDataset} <b>client-streaming</b>
 * gRPC RPC — ships a YOLO-format dataset (docs/plans/done/CV-TRAINING-PLAN.md &sect;5) straight onto the wire as
 * a streamed zip archive. This is the transport half of docs/plans/done/CV-TRAINING-V2-PLAN.md &sect;A/&sect;B:
 * dataset delivery to the training host rides the same gRPC connection detection/registry/training
 * already use, instead of a human rsyncing a zip the platform wrote to its own disk.
 *
 * <h2>Channel reuse</h2>
 * Like {@link GrpcModelRegistryPort} and {@link GrpcTrainingPort}, this class takes a pre-built
 * {@link ManagedChannel} rather than a host/port pair and never shuts it down — {@code vision-app}'s
 * wiring hands it the <em>same</em> channel {@link GrpcDetectionPort} and its siblings already share,
 * so the platform holds exactly one connection to the training host, not a fourth independently
 * configured one. Channel lifecycle stays wherever the channel was built.
 *
 * <h2>Async stub, not blocking</h2>
 * {@code Training/UploadDataset} is client-streaming; grpc-java's blocking stub only supports unary
 * and server-streaming calls (contrast {@link GrpcModelRegistryPort}'s unary RPCs and {@link
 * GrpcTrainingPort#startTraining}'s server-streaming one, both on {@code newBlockingStub}). This
 * class instead uses {@link TrainingGrpc#newStub(io.grpc.Channel)} and bridges the async {@link
 * StreamObserver} pair back to {@link DatasetUploadPort#upload}'s synchronous contract with a {@link
 * CountDownLatch} (see {@link UploadAckObserver}): the calling thread writes every {@link
 * DatasetChunk} onto the request observer, half-closes it, then blocks until the response
 * observer's terminal signal — an {@link UploadAck} (via {@code onNext}+{@code onCompleted}) or a
 * transport {@code onError} — releases the latch.
 *
 * <h2>Framing — the archive is never fully materialized</h2>
 * {@link #upload} builds a {@link ZipOutputStream} directly over a small internal {@link
 * ChunkingOutputStream} that buffers writes and emits one {@link DatasetChunk} — carrying the
 * dataset id and up to {@link GrpcCvSettings#uploadChunkBytes()} bytes of the growing zip — every
 * time that many bytes accumulate, plus one final (possibly smaller) chunk when the zip is closed.
 * The archive's bytes exist only as this one rolling buffer; nothing is ever staged whole in memory
 * or on disk, matching the design decision docs/plans/done/CV-TRAINING-V2-PLAN.md &sect;D makes explicit
 * (streaming small chunks, not a message-size bump). Zip entries are written in this exact order,
 * matching the byte-for-byte layout the platform's old filesystem-zip export step used:
 * <ol>
 *   <li>{@code data.yaml} — the caller's {@code dataYaml} string, UTF-8.</li>
 *   <li>For each {@link DatasetUploadPort.ExportEntry}, in list order: {@code images/<imageName>}
 *   (raw image bytes) then {@code labels/<stem>.txt}, where {@code <stem>} is {@code imageName}
 *   with everything from its last {@code '.'} onward stripped (no {@code '.'} &rArr; the whole
 *   name is the stem).</li>
 * </ol>
 *
 * <h2>Deadline</h2>
 * The whole upload — archive framing plus the round trip — is bounded by {@link
 * GrpcCvSettings#uploadTimeout()} ({@code AbstractStub#withDeadlineAfter}), the same idiom {@link
 * GrpcModelRegistryPort#CALL_TIMEOUT_SECONDS} uses for its control-plane calls. Unlike {@link
 * GrpcTrainingPort#startTraining}, which deliberately arms no deadline because a training job's
 * length is unbounded, an upload is genuinely bounded work (framing and sending some tens of MB), so
 * a fixed deadline is safe and appropriate here.
 *
 * <h2>Failure semantics</h2>
 * <ul>
 *   <li><b>{@code UploadAck.ok=false}</b> (cv-service reachable but refused the content — e.g. a
 *   corrupt archive, an unsafe entry, a size-cap breach): throws {@link IllegalStateException}
 *   naming cv-service's own refusal message — the same "reachable but refused" convention {@link
 *   GrpcModelRegistryPort#promote} uses for {@code Ack.ok=false}.</li>
 *   <li><b>Transport failure or deadline exceeded</b>: the raw {@link StatusRuntimeException}
 *   captured off the response observer's {@code onError} propagates to the caller <em>unwrapped</em>
 *   — never degraded to a quiet failure, matching every other RPC in this module.</li>
 * </ul>
 * Both failure shapes are exactly what {@code DefaultTrainingJobService#recordFailure}
 * (vision-application) turns into an honest {@code FAILED} training-job message.
 *
 * <h2>Threading</h2>
 * Plain class, no Spring. {@link #upload} blocks the calling thread for the whole upload (bounded by
 * the deadline above), matching {@link DatasetUploadPort}'s synchronous contract. No caching or
 * de-duplication is performed here — every call is an independent RPC; re-uploading the same
 * dataset id is the <em>server's</em> own idempotent replace (docs/plans/done/CV-TRAINING-V2-PLAN.md &sect;2),
 * not something this client needs to guard against or short-circuit. Safe for concurrent use from
 * multiple threads uploading different datasets (the underlying {@link ManagedChannel} and stub
 * already are); a single {@code upload} call is not meant to be invoked concurrently for the same
 * dataset id.
 */
public final class GrpcDatasetUploadPort implements DatasetUploadPort {

    private static final System.Logger LOG = System.getLogger(GrpcDatasetUploadPort.class.getName());

    private static final String DATA_YAML_ENTRY = "data.yaml";
    private static final String IMAGES_PREFIX = "images/";
    private static final String LABELS_PREFIX = "labels/";

    private final TrainingGrpc.TrainingStub stub;
    private final GrpcCvSettings settings;

    /**
     * @param channel  a channel to a training host, typically the same one {@link
     *                 GrpcDetectionPort}/{@link GrpcModelRegistryPort}/{@link GrpcTrainingPort} use
     *                 (see class javadoc's "Channel reuse"). Never closed by this class.
     * @param settings supplies {@link GrpcCvSettings#uploadTimeout()} (this class's per-call
     *                 deadline) and {@link GrpcCvSettings#uploadChunkBytes()} (the zip-chunk framing
     *                 size) — the same settings object {@link GrpcDetectionPort} uses for its own
     *                 {@code vision.cv.*} tunables.
     */
    public GrpcDatasetUploadPort(ManagedChannel channel, GrpcCvSettings settings) {
        Objects.requireNonNull(channel, "channel must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.stub = TrainingGrpc.newStub(channel);
    }

    @Override
    public DatasetUpload upload(DatasetId datasetId, String dataYaml, List<ExportEntry> entries) {
        Objects.requireNonNull(datasetId, "datasetId must not be null");
        Objects.requireNonNull(dataYaml, "dataYaml must not be null");
        Objects.requireNonNull(entries, "entries must not be null");

        String id = datasetId.value().toString();
        LOG.log(System.Logger.Level.INFO,
                () -> "Uploading dataset " + id + " (" + entries.size() + " sample(s)) to cv-service");

        UploadAckObserver responseObserver = new UploadAckObserver(id);
        StreamObserver<DatasetChunk> requestObserver = stub
                .withDeadlineAfter(settings.uploadTimeout().toMillis(), TimeUnit.MILLISECONDS)
                .uploadDataset(responseObserver);

        try (ChunkingOutputStream chunker = new ChunkingOutputStream(id, requestObserver, settings.uploadChunkBytes());
             ZipOutputStream zip = new ZipOutputStream(chunker)) {
            writeEntry(zip, DATA_YAML_ENTRY, dataYaml.getBytes(StandardCharsets.UTF_8));
            for (ExportEntry entry : entries) {
                writeEntry(zip, IMAGES_PREFIX + entry.imageName(), entry.imageBytes());
                writeEntry(zip, LABELS_PREFIX + labelName(entry.imageName()),
                        entry.labelFileText().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to build the dataset archive for upload " + id, e);
        }
        requestObserver.onCompleted();

        UploadAck ack = responseObserver.awaitAck();
        LOG.log(System.Logger.Level.INFO, () -> "Uploaded dataset " + id + ": " + ack.getBytesReceived()
                + " bytes, " + ack.getFileCount() + " file(s)");
        return new DatasetUpload(datasetId, Instant.now(), entries.size(), ack.getBytesReceived());
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    /**
     * Strips {@code imageName} down to its stem the same way the platform's old filesystem-zip
     * export step did: everything from the last {@code '.'} onward is removed; a name with no
     * {@code '.'} is used whole.
     */
    private static String labelName(String imageName) {
        int dot = imageName.lastIndexOf('.');
        String stem = dot < 0 ? imageName : imageName.substring(0, dot);
        return stem + ".txt";
    }

    /**
     * The response side of {@code Training/UploadDataset}'s async pair — captures the terminal
     * {@link UploadAck} or transport error and releases a {@link CountDownLatch} so the calling
     * (synchronous, per {@link DatasetUploadPort}) thread can block on it. {@link #onNext} and
     * {@link #onError}/{@link #onCompleted} always run on a gRPC callback thread, never the caller's
     * — the fields they set are read by {@link #awaitAck} only after {@link CountDownLatch#await()}
     * returns, so the latch's own happens-before guarantee is all the safe-publication this class
     * needs (no {@code volatile}/atomics required).
     */
    private static final class UploadAckObserver implements StreamObserver<UploadAck> {

        private final String datasetId;
        private final CountDownLatch done = new CountDownLatch(1);
        private UploadAck ack;
        private Throwable error;

        UploadAckObserver(String datasetId) {
            this.datasetId = datasetId;
        }

        @Override
        public void onNext(UploadAck value) {
            ack = value;
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            done.countDown();
        }

        @Override
        public void onCompleted() {
            done.countDown();
        }

        /**
         * Blocks until the terminal signal arrives, then either returns a successful {@link
         * UploadAck} ({@code ok=true}) or throws. No wait-timeout is applied here beyond {@link
         * GrpcCvSettings#uploadTimeout()}'s call deadline already armed on the RPC (see class
         * javadoc "Deadline") — that deadline guarantees this latch is eventually released one way
         * or another, so a second timeout here would be redundant.
         *
         * @throws StatusRuntimeException  unwrapped, if the RPC itself failed (transport error or
         *                                 deadline exceeded)
         * @throws IllegalStateException   if cv-service explicitly refused the upload ({@code
         *                                  ok=false}), if it closed the stream without ever sending
         *                                  an ack, or if this thread was interrupted while waiting
         */
        UploadAck awaitAck() {
            try {
                done.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while awaiting cv-service's dataset upload acknowledgement: " + datasetId, e);
            }

            if (error instanceof StatusRuntimeException sre) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "Failed to upload dataset " + datasetId + " to cv-service", sre);
                throw sre;
            }
            if (error != null) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "Failed to upload dataset " + datasetId + " to cv-service", error);
                throw new IllegalStateException("Failed to upload dataset " + datasetId + " to cv-service", error);
            }
            if (ack == null) {
                throw new IllegalStateException(
                        "cv-service closed the dataset upload stream without an acknowledgement: " + datasetId);
            }
            if (!ack.getOk()) {
                LOG.log(System.Logger.Level.WARNING,
                        () -> "cv-service rejected dataset upload " + datasetId + ": " + ack.getMessage());
                throw new IllegalStateException("cv-service rejected the dataset upload: " + ack.getMessage());
            }
            return ack;
        }
    }

    /**
     * Batches {@link OutputStream#write} calls into a {@link GrpcCvSettings#uploadChunkBytes()}-byte
     * buffer and emits one {@link DatasetChunk} onto the request observer every time it fills, plus a
     * final (possibly smaller) chunk on {@link #close()} — so the archive being built is never held
     * whole in memory, only this one rolling buffer. Not thread-safe; used only by the single thread
     * running {@link #upload} while it drives the {@link ZipOutputStream} wrapping this stream.
     */
    private static final class ChunkingOutputStream extends OutputStream {

        private final String datasetId;
        private final StreamObserver<DatasetChunk> requestObserver;
        private final byte[] buffer;
        private int length;

        ChunkingOutputStream(String datasetId, StreamObserver<DatasetChunk> requestObserver, int chunkBytes) {
            this.datasetId = datasetId;
            this.requestObserver = requestObserver;
            this.buffer = new byte[chunkBytes];
        }

        @Override
        public void write(int b) {
            buffer[length++] = (byte) b;
            if (length == buffer.length) {
                flush();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            int remaining = len;
            int offset = off;
            while (remaining > 0) {
                int toCopy = Math.min(buffer.length - length, remaining);
                System.arraycopy(b, offset, buffer, length, toCopy);
                length += toCopy;
                offset += toCopy;
                remaining -= toCopy;
                if (length == buffer.length) {
                    flush();
                }
            }
        }

        @Override
        public void flush() {
            if (length == 0) {
                return;
            }
            requestObserver.onNext(DatasetChunk.newBuilder()
                    .setDatasetId(datasetId)
                    .setContent(ByteString.copyFrom(buffer, 0, length))
                    .build());
            length = 0;
        }

        @Override
        public void close() {
            flush();
        }
    }
}
