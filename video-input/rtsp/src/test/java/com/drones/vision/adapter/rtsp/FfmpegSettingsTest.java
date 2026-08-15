package com.drones.vision.adapter.rtsp;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FfmpegSettings#defaults()} must reproduce, byte-for-byte, every literal this record
 * replaced (docs/plans/active/LAYERING-REFACTOR-PLAN.md §1.3's "behavior guardrail": a structural move, not a
 * retune) — pinned here against the exact values the pre-split {@code FfmpegVideoSource}/{@code
 * RtspFeedTransmitter} constants held. Compact-constructor validation is also asserted directly,
 * matching this codebase's convention for every other validated domain/settings record.
 */
class FfmpegSettingsTest {

    @Test
    void defaultsReproduceEveryPreExistingLiteralExactly() {
        FfmpegSettings settings = FfmpegSettings.defaults();

        assertEquals("tcp", settings.transport());
        assertEquals(Duration.ofSeconds(10), settings.openTimeout());
        assertEquals(32_768, settings.probesizeBytes());
        assertEquals(Duration.ofSeconds(1), settings.analyzeDuration());
        assertEquals(0, settings.reorderQueueSize());
        assertEquals(Duration.ofMillis(100), settings.maxDelay());
        assertEquals(Duration.ofSeconds(5), settings.udpTimeout());
        assertEquals(512, settings.udpFifoSizePackets());
        assertEquals(Duration.ofMillis(120), settings.srtLatency());
        assertEquals(4, settings.publisherBufferCapacity());
        assertEquals(Duration.ofSeconds(20), settings.closeJoinTimeout());

        FfmpegSettings.Transmit transmit = settings.transmit();
        assertEquals(2, transmit.gopSeconds());
        assertEquals("ultrafast", transmit.preset());
        assertEquals(15.0, transmit.fallbackFps());
        assertEquals(Duration.ofSeconds(5), transmit.connectTimeout());
        assertEquals("zerolatency", transmit.tune());
    }

    @Test
    void microsOptionConvertsWholeMicrosecondsToADecimalString() {
        assertEquals("10000000", FfmpegSettings.microsOption(Duration.ofSeconds(10)));
        assertEquals("100000", FfmpegSettings.microsOption(Duration.ofMillis(100)));
        assertEquals("5000000", FfmpegSettings.microsOption(Duration.ofSeconds(5)));
    }

    @Test
    void compactConstructorRejectsABlankTransport() {
        assertThrows(IllegalArgumentException.class, () -> new FfmpegSettings(
                " ", Duration.ofSeconds(1), 1, Duration.ofSeconds(1), 0, Duration.ofMillis(1),
                Duration.ofSeconds(1), 1, Duration.ofMillis(1), 1, Duration.ofSeconds(1),
                new FfmpegSettings.Transmit(1, "ultrafast", 15.0, Duration.ofSeconds(1), "zerolatency")));
    }

    @Test
    void compactConstructorRejectsANonPositiveDuration() {
        assertThrows(IllegalArgumentException.class, () -> new FfmpegSettings(
                "tcp", Duration.ZERO, 1, Duration.ofSeconds(1), 0, Duration.ofMillis(1),
                Duration.ofSeconds(1), 1, Duration.ofMillis(1), 1, Duration.ofSeconds(1),
                new FfmpegSettings.Transmit(1, "ultrafast", 15.0, Duration.ofSeconds(1), "zerolatency")));
    }

    @Test
    void compactConstructorRejectsANegativeReorderQueueSize() {
        assertThrows(IllegalArgumentException.class, () -> new FfmpegSettings(
                "tcp", Duration.ofSeconds(1), 1, Duration.ofSeconds(1), -1, Duration.ofMillis(1),
                Duration.ofSeconds(1), 1, Duration.ofMillis(1), 1, Duration.ofSeconds(1),
                new FfmpegSettings.Transmit(1, "ultrafast", 15.0, Duration.ofSeconds(1), "zerolatency")));
    }

    @Test
    void compactConstructorRejectsANullTransmit() {
        assertThrows(NullPointerException.class, () -> new FfmpegSettings(
                "tcp", Duration.ofSeconds(1), 1, Duration.ofSeconds(1), 0, Duration.ofMillis(1),
                Duration.ofSeconds(1), 1, Duration.ofMillis(1), 1, Duration.ofSeconds(1), null));
    }

    @Test
    void transmitCompactConstructorRejectsANonPositiveGopSeconds() {
        assertThrows(IllegalArgumentException.class,
                () -> new FfmpegSettings.Transmit(0, "ultrafast", 15.0, Duration.ofSeconds(1), "zerolatency"));
    }

    @Test
    void transmitCompactConstructorRejectsABlankPreset() {
        assertThrows(IllegalArgumentException.class,
                () -> new FfmpegSettings.Transmit(1, " ", 15.0, Duration.ofSeconds(1), "zerolatency"));
    }
}
