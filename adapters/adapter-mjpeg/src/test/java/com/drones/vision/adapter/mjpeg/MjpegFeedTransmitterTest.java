package com.drones.vision.adapter.mjpeg;

import com.drones.vision.domain.model.FeedId;
import com.drones.vision.domain.model.FeedSpec;
import com.drones.vision.domain.model.StreamDescriptor;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MjpegFeedTransmitterTest {

    @Test
    void supportsMjpegProtocolWithAFileSourceOnly() {
        MjpegFeedTransmitter transmitter = new MjpegFeedTransmitter();
        try {
            assertTrue(transmitter.supports(new FeedSpec("mjpeg", URI.create("file:///tmp/clip.mp4"), Map.of())),
                    "mjpeg protocol with a file: source must be supported");
            assertFalse(transmitter.supports(new FeedSpec("mjpeg", URI.create("http://example.com/clip.mp4"), Map.of())),
                    "mjpeg protocol must be rejected when the source is not a file: URI");
            assertFalse(transmitter.supports(new FeedSpec("rtsp", URI.create("file:///tmp/clip.mp4"), Map.of())),
                    "rtsp protocol (not mjpeg) must be rejected");
            assertFalse(transmitter.supports(null));
        } finally {
            transmitter.close();
        }
    }

    @Test
    void startRejectsAnUnsupportedSpec() {
        MjpegFeedTransmitter transmitter = new MjpegFeedTransmitter();
        try {
            FeedSpec unsupported = new FeedSpec("mjpeg", URI.create("http://example.com/clip.mp4"), Map.of());
            assertThrows(IllegalArgumentException.class, () -> transmitter.start(FeedId.random(), unsupported));
        } finally {
            transmitter.close();
        }
    }

    @Test
    void startRejectsANonexistentSourceFile(@TempDir Path tempDir) {
        MjpegFeedTransmitter transmitter = new MjpegFeedTransmitter();
        try {
            Path missing = tempDir.resolve("does-not-exist.mp4");
            FeedSpec spec = new FeedSpec("mjpeg", missing.toUri(), Map.of());

            assertThrows(IllegalArgumentException.class, () -> transmitter.start(FeedId.random(), spec));
        } finally {
            transmitter.close();
        }
    }

    @Test
    void startRejectsADirectoryAsTheSourceFile(@TempDir Path tempDir) {
        MjpegFeedTransmitter transmitter = new MjpegFeedTransmitter();
        try {
            FeedSpec spec = new FeedSpec("mjpeg", tempDir.toUri(), Map.of());

            assertThrows(IllegalArgumentException.class, () -> transmitter.start(FeedId.random(), spec));
        } finally {
            transmitter.close();
        }
    }

    @Test
    void startReturnsAnHttpDescriptorPointingAtTheFeedPathUnderAnEphemeralPort(@TempDir Path tempDir) throws Exception {
        Path videoFile = createTestVideo(tempDir, 64, 48, 5, 25);
        MjpegFeedTransmitter transmitter = new MjpegFeedTransmitter();
        try {
            FeedId id = FeedId.random();
            FeedSpec spec = new FeedSpec("mjpeg", videoFile.toUri(), Map.of());

            StreamDescriptor descriptor = transmitter.start(id, spec);

            assertEquals("mjpeg", descriptor.protocol());
            assertEquals("http", descriptor.uri().getScheme());
            assertEquals("127.0.0.1", descriptor.uri().getHost());
            assertTrue(descriptor.uri().getPort() > 0, "expected a real ephemeral port, got " + descriptor.uri().getPort());
            assertEquals("/feed-" + id.value(), descriptor.uri().getPath());
            assertEquals(Map.of(), descriptor.options());
        } finally {
            transmitter.close();
        }
    }

    @Test
    void twoFeedsFromTheSameTransmitterShareOneServerButDistinctContexts(@TempDir Path tempDir) throws Exception {
        Path videoFile = createTestVideo(tempDir, 64, 48, 5, 25);
        MjpegFeedTransmitter transmitter = new MjpegFeedTransmitter();
        try {
            FeedSpec spec = new FeedSpec("mjpeg", videoFile.toUri(), Map.of());
            StreamDescriptor first = transmitter.start(FeedId.random(), spec);
            StreamDescriptor second = transmitter.start(FeedId.random(), spec);

            assertEquals(first.uri().getPort(), second.uri().getPort(), "both feeds must share one HTTP server/port");
            assertFalse(first.uri().getPath().equals(second.uri().getPath()), "each feed must get a distinct context path");
        } finally {
            transmitter.close();
        }
    }

    @Test
    void stopOnAnUnknownIdIsANoop() {
        MjpegFeedTransmitter transmitter = new MjpegFeedTransmitter();
        try {
            assertDoesNotThrow(() -> transmitter.stop(FeedId.random()));
        } finally {
            transmitter.close();
        }
    }

    @Test
    void stopIsIdempotent(@TempDir Path tempDir) throws Exception {
        Path videoFile = createTestVideo(tempDir, 64, 48, 5, 25);
        MjpegFeedTransmitter transmitter = new MjpegFeedTransmitter();
        try {
            FeedId id = FeedId.random();
            FeedSpec spec = new FeedSpec("mjpeg", videoFile.toUri(), Map.of());
            transmitter.start(id, spec);

            assertDoesNotThrow(() -> transmitter.stop(id));
            assertDoesNotThrow(() -> transmitter.stop(id), "stop() must be idempotent");
        } finally {
            transmitter.close();
        }
    }

    @Test
    void closeIsIdempotentAndSafeWithoutAnyFeedEverStarted() {
        MjpegFeedTransmitter transmitter = new MjpegFeedTransmitter();

        assertDoesNotThrow(transmitter::close);
        assertDoesNotThrow(transmitter::close, "close() must be idempotent");
    }

    @Test
    void closeIsIdempotentAfterAFeedWasStarted(@TempDir Path tempDir) throws Exception {
        Path videoFile = createTestVideo(tempDir, 64, 48, 5, 25);
        MjpegFeedTransmitter transmitter = new MjpegFeedTransmitter();
        transmitter.start(FeedId.random(), new FeedSpec("mjpeg", videoFile.toUri(), Map.of()));

        assertDoesNotThrow(transmitter::close);
        assertDoesNotThrow(transmitter::close, "close() must be idempotent");
    }

    private static Path createTestVideo(Path dir, int width, int height, int frameCount, double fps) throws Exception {
        Path file = dir.resolve("mjpeg-feed-transmitter-test-" + frameCount + "-" + fps + ".mp4");
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(file.toFile(), width, height)) {
            recorder.setFormat("mp4");
            recorder.setFrameRate(fps);
            recorder.start();
            for (int i = 0; i < frameCount; i++) {
                recorder.record(solidFrame(width, height, i));
            }
        }
        return file;
    }

    /** A single-color synthetic BGR frame, shaded by frame index, for the test-only recorder. */
    private static Frame solidFrame(int width, int height, int frameIndex) {
        int channels = 3;
        int stride = width * channels;
        ByteBuffer buffer = ByteBuffer.allocateDirect(stride * height);
        byte value = (byte) (frameIndex * 17);
        for (int i = 0; i < buffer.capacity(); i++) {
            buffer.put(value);
        }
        buffer.rewind();

        Frame frame = new Frame();
        frame.imageWidth = width;
        frame.imageHeight = height;
        frame.imageDepth = Frame.DEPTH_UBYTE;
        frame.imageChannels = channels;
        frame.imageStride = stride;
        frame.image = new Buffer[] {buffer};
        return frame;
    }
}
