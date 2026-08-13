package com.drones.vision.adapter.rtsp;

import com.drones.vision.perception.domain.model.FeedId;
import com.drones.vision.perception.domain.model.FeedSpec;
import com.drones.vision.kernel.StreamDescriptor;

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

class RtspFeedTransmitterTest {

    private static final URI TARGET_BASE = URI.create("rtsp://localhost:8554");

    @Test
    void supportsRtspProtocolWithAFileSourceOnly() {
        RtspFeedTransmitter transmitter = new RtspFeedTransmitter(TARGET_BASE);

        assertTrue(transmitter.supports(new FeedSpec("rtsp", URI.create("file:///tmp/clip.mp4"), Map.of())),
                "rtsp protocol with a file: source must be supported");
        assertFalse(transmitter.supports(new FeedSpec("rtsp", URI.create("http://example.com/clip.mp4"), Map.of())),
                "rtsp protocol must be rejected when the source is not a file: URI");
        assertFalse(transmitter.supports(new FeedSpec("file", URI.create("file:///tmp/clip.mp4"), Map.of())),
                "file protocol (not rtsp) must be rejected");
        assertFalse(transmitter.supports(null));
    }

    @Test
    void startRejectsAnUnsupportedSpec() {
        RtspFeedTransmitter transmitter = new RtspFeedTransmitter(TARGET_BASE);
        FeedSpec unsupported = new FeedSpec("rtsp", URI.create("http://example.com/clip.mp4"), Map.of());

        assertThrows(IllegalArgumentException.class, () -> transmitter.start(FeedId.random(), unsupported));
    }

    @Test
    void startRejectsANonexistentSourceFile(@TempDir Path tempDir) {
        RtspFeedTransmitter transmitter = new RtspFeedTransmitter(TARGET_BASE);
        Path missing = tempDir.resolve("does-not-exist.mp4");
        FeedSpec spec = new FeedSpec("rtsp", missing.toUri(), Map.of());

        assertThrows(IllegalArgumentException.class, () -> transmitter.start(FeedId.random(), spec));
    }

    @Test
    void startReturnsADescriptorPointingAtTheFeedPathUnderTheTargetBase(@TempDir Path tempDir) throws Exception {
        Path videoFile = createTestVideo(tempDir, 64, 48, 5, 25);
        RtspFeedTransmitter transmitter = new RtspFeedTransmitter(TARGET_BASE);
        FeedId id = FeedId.random();
        FeedSpec spec = new FeedSpec("rtsp", videoFile.toUri(), Map.of());

        try {
            StreamDescriptor descriptor = transmitter.start(id, spec);

            assertEquals("rtsp", descriptor.protocol());
            assertEquals(URI.create(TARGET_BASE + "/feed-" + id.value()), descriptor.uri());
            assertEquals(Map.of(), descriptor.options());
        } finally {
            assertDoesNotThrow(() -> transmitter.stop(id));
        }
    }

    @Test
    void stopOnAnUnknownIdIsANoop() {
        RtspFeedTransmitter transmitter = new RtspFeedTransmitter(TARGET_BASE);

        assertDoesNotThrow(() -> transmitter.stop(FeedId.random()));
    }

    @Test
    void stopIsIdempotent(@TempDir Path tempDir) throws Exception {
        Path videoFile = createTestVideo(tempDir, 64, 48, 5, 25);
        RtspFeedTransmitter transmitter = new RtspFeedTransmitter(TARGET_BASE);
        FeedId id = FeedId.random();
        FeedSpec spec = new FeedSpec("rtsp", videoFile.toUri(), Map.of());

        transmitter.start(id, spec);

        assertDoesNotThrow(() -> transmitter.stop(id));
        assertDoesNotThrow(() -> transmitter.stop(id), "stop() must be idempotent");
    }

    private static Path createTestVideo(Path dir, int width, int height, int frameCount, double fps)
            throws Exception {
        Path file = dir.resolve("rtsp-feed-transmitter-test-" + frameCount + "-" + fps + ".mp4");
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
