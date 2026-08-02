package com.drones.vision.adapter.cvgrpc;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GrpcCvSettings}'s compact-constructor validation and {@link
 * GrpcCvSettings#defaults()}. The detectWidth/jpegQuality boundary-rejection tests and the keepalive
 * constant sanity check moved here from {@code GrpcDetectionPortTest} (docs/LAYERING-REFACTOR-PLAN.md
 * &sect;5.1/E4): this record is now the single place either value is validated, so a
 * {@code GrpcDetectionPort}/{@code GrpcDatasetUploadPort} constructor can no longer be reached with an
 * invalid value in the first place -- there is nothing left for a port-level test to prove about that.
 */
class GrpcCvSettingsTest {

    @Test
    void defaultsMatchEveryLiteralThePreRefactorConstantsUsedToHardcode() {
        GrpcCvSettings settings = GrpcCvSettings.defaults();

        assertEquals(Duration.ofSeconds(2), settings.responseTimeout());
        assertEquals(Duration.ofSeconds(20), settings.keepAliveTime());
        assertEquals(Duration.ofSeconds(5), settings.keepAliveTimeout());
        assertTrue(settings.keepAliveWithoutCalls());
        assertEquals(Duration.ofSeconds(5), settings.channelShutdownTimeout());
        assertTrue(settings.plaintext());
        assertEquals(Duration.ofSeconds(300), settings.uploadTimeout());
        assertEquals(262_144, settings.uploadChunkBytes());
        assertEquals(640, settings.detectWidth());
        assertEquals(0.8f, settings.jpegQuality());
    }

    @Test
    void constructorRejectsDetectWidthBelowMinimum() {
        GrpcCvSettings defaults = GrpcCvSettings.defaults();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> defaults.withDetectWidth(GrpcCvSettings.MIN_DETECT_WIDTH - 1));
        assertTrue(ex.getMessage().contains("detectWidth"), "message should mention detectWidth: " + ex.getMessage());
    }

    @Test
    void constructorAcceptsDetectWidthAtMinimum() {
        GrpcCvSettings settings = GrpcCvSettings.defaults().withDetectWidth(GrpcCvSettings.MIN_DETECT_WIDTH);
        assertEquals(GrpcCvSettings.MIN_DETECT_WIDTH, settings.detectWidth());
    }

    @Test
    void constructorRejectsNonPositiveJpegQuality() {
        GrpcCvSettings defaults = GrpcCvSettings.defaults();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> defaults.withJpegQuality(0f));
        assertTrue(ex.getMessage().contains("jpegQuality"), "message should mention jpegQuality: " + ex.getMessage());
    }

    @Test
    void constructorRejectsJpegQualityAboveOne() {
        GrpcCvSettings defaults = GrpcCvSettings.defaults();
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> defaults.withJpegQuality(1.01f));
        assertTrue(ex.getMessage().contains("jpegQuality"), "message should mention jpegQuality: " + ex.getMessage());
    }

    @Test
    void constructorAcceptsJpegQualityAtUpperBound() {
        GrpcCvSettings settings = GrpcCvSettings.defaults().withJpegQuality(1.0f);
        assertEquals(1.0f, settings.jpegQuality());
    }

    @Test
    void constructorRejectsNonPositiveDurations() {
        GrpcCvSettings defaults = GrpcCvSettings.defaults();
        assertThrows(IllegalArgumentException.class, () -> new GrpcCvSettings(Duration.ZERO,
                defaults.keepAliveTime(), defaults.keepAliveTimeout(), defaults.keepAliveWithoutCalls(),
                defaults.channelShutdownTimeout(), defaults.plaintext(), defaults.uploadTimeout(),
                defaults.uploadChunkBytes(), defaults.detectWidth(), defaults.jpegQuality()));
        assertThrows(IllegalArgumentException.class, () -> new GrpcCvSettings(defaults.responseTimeout(),
                Duration.ofSeconds(-1), defaults.keepAliveTimeout(), defaults.keepAliveWithoutCalls(),
                defaults.channelShutdownTimeout(), defaults.plaintext(), defaults.uploadTimeout(),
                defaults.uploadChunkBytes(), defaults.detectWidth(), defaults.jpegQuality()));
    }

    @Test
    void constructorRejectsNonPositiveUploadChunkBytes() {
        GrpcCvSettings defaults = GrpcCvSettings.defaults();
        assertThrows(IllegalArgumentException.class, () -> new GrpcCvSettings(defaults.responseTimeout(),
                defaults.keepAliveTime(), defaults.keepAliveTimeout(), defaults.keepAliveWithoutCalls(),
                defaults.channelShutdownTimeout(), defaults.plaintext(), defaults.uploadTimeout(),
                0, defaults.detectWidth(), defaults.jpegQuality()));
    }

    @Test
    void constructorRejectsNullDurations() {
        GrpcCvSettings defaults = GrpcCvSettings.defaults();
        assertThrows(NullPointerException.class, () -> new GrpcCvSettings(null,
                defaults.keepAliveTime(), defaults.keepAliveTimeout(), defaults.keepAliveWithoutCalls(),
                defaults.channelShutdownTimeout(), defaults.plaintext(), defaults.uploadTimeout(),
                defaults.uploadChunkBytes(), defaults.detectWidth(), defaults.jpegQuality()));
    }

    @Test
    void withDetectWidthAndWithJpegQualityChangeOnlyThatField() {
        GrpcCvSettings defaults = GrpcCvSettings.defaults();
        GrpcCvSettings narrower = defaults.withDetectWidth(320);

        assertEquals(320, narrower.detectWidth());
        assertEquals(defaults.jpegQuality(), narrower.jpegQuality());
        assertEquals(defaults.responseTimeout(), narrower.responseTimeout());
        assertEquals(defaults.keepAliveTime(), narrower.keepAliveTime());

        GrpcCvSettings lowerQuality = defaults.withJpegQuality(0.5f);
        assertEquals(0.5f, lowerQuality.jpegQuality());
        assertEquals(defaults.detectWidth(), lowerQuality.detectWidth());
    }

    /**
     * docs/REMOTE-CV-PLAN.md "Transport decisions" P1: HTTP/2 keepalive on a real-TCP channel must
     * ping often enough (and confirm loss fast enough) to catch a half-open connection within
     * seconds, not app-level-timeout later. Actually proving a dropped connection is detected within
     * N seconds needs a real flaky-network harness (out of unit-test reach, and exactly what the task
     * calls out as not worth making flaky here) -- this asserts the constants themselves are sane and
     * wired: a timeout comfortably shorter than the ping interval, and both well under {@code
     * GrpcCvSettings.RESPONSE_TIMEOUT_SECONDS} so keepalive detects loss before the app-level path
     * would. {@code GrpcDetectionPortTest}'s real-TCP tests already build a {@code GrpcDetectionPort}
     * via the host/port constructor these defaults feed, so a misconfigured {@code
     * ManagedChannelBuilder} call (e.g. a bad time unit) would fail that suite, not just this test.
     */
    @Test
    void keepaliveConstantsAreSaneForAFlakyLink() {
        assertTrue(GrpcCvSettings.KEEPALIVE_TIME_SECONDS > 0,
                "keepalive ping interval must be positive");
        assertTrue(GrpcCvSettings.KEEPALIVE_TIMEOUT_SECONDS > 0,
                "keepalive ack timeout must be positive");
        assertTrue(GrpcCvSettings.KEEPALIVE_TIMEOUT_SECONDS < GrpcCvSettings.KEEPALIVE_TIME_SECONDS,
                "keepalive ack timeout should be well under the ping interval, not stacked on top of it");
        assertTrue(GrpcCvSettings.KEEPALIVE_TIME_SECONDS + GrpcCvSettings.KEEPALIVE_TIMEOUT_SECONDS
                        < TimeUnit.MINUTES.toSeconds(1),
                "a half-open connection should be caught within about a minute, not lingering for many");
        assertTrue(GrpcCvSettings.KEEPALIVE_WITHOUT_CALLS,
                "an idle stream (no in-flight detect() calls) is exactly the case that needs probing");
    }
}
