package com.drones.vision.adapter.mjpeg;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link MjpegSettings#defaults()} to the exact literals this module hardcoded before this
 * record existed ({@code docs/LAYERING-REFACTOR-PLAN.md} §1.3's "byte-identical default"
 * guardrail) and exercises the compact-constructor validation.
 */
class MjpegSettingsTest {

    @Test
    void defaultsReproduceEveryLiteralThisModuleHardcodedBeforeThisRecordExisted() {
        MjpegSettings settings = MjpegSettings.defaults();

        assertEquals(Duration.ofMillis(5_000L), settings.readTimeout());
        assertEquals(4, settings.publisherBufferCapacity());
        assertEquals(Duration.ofMillis(20_000L), settings.closeJoinTimeout());

        MjpegSettings.Transmit transmit = settings.transmit();
        assertEquals("127.0.0.1", transmit.bindHost());
        assertTrue(transmit.maxViewerThreads().isEmpty(),
                "no literal thread cap existed before this record -- absent must mean unbounded");
        assertEquals(0.75f, transmit.jpegQuality());
        assertTrue(transmit.loop());
        assertEquals(Duration.ofMillis(5_000L), transmit.viewerJoinTimeout());
    }

    @Test
    void rejectsNonPositiveOrNullTopLevelFields() {
        MjpegSettings.Transmit transmit = MjpegSettings.Transmit.defaults();

        assertThrows(NullPointerException.class, () -> new MjpegSettings(null, 4, Duration.ofSeconds(20), transmit));
        assertThrows(IllegalArgumentException.class,
                () -> new MjpegSettings(Duration.ZERO, 4, Duration.ofSeconds(20), transmit));
        assertThrows(IllegalArgumentException.class,
                () -> new MjpegSettings(Duration.ofSeconds(5), 0, Duration.ofSeconds(20), transmit));
        assertThrows(IllegalArgumentException.class,
                () -> new MjpegSettings(Duration.ofSeconds(5), 4, Duration.ZERO, transmit));
        assertThrows(NullPointerException.class,
                () -> new MjpegSettings(Duration.ofSeconds(5), 4, Duration.ofSeconds(20), null));
    }

    @Test
    void rejectsInvalidTransmitFields() {
        assertThrows(IllegalArgumentException.class, () -> new MjpegSettings.Transmit(
                "", OptionalInt.empty(), 0.75f, true, Duration.ofSeconds(5)));
        assertThrows(NullPointerException.class, () -> new MjpegSettings.Transmit(
                null, OptionalInt.empty(), 0.75f, true, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new MjpegSettings.Transmit(
                "127.0.0.1", OptionalInt.of(0), 0.75f, true, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new MjpegSettings.Transmit(
                "127.0.0.1", OptionalInt.of(-1), 0.75f, true, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new MjpegSettings.Transmit(
                "127.0.0.1", OptionalInt.empty(), 0f, true, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new MjpegSettings.Transmit(
                "127.0.0.1", OptionalInt.empty(), 1.01f, true, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new MjpegSettings.Transmit(
                "127.0.0.1", OptionalInt.empty(), 0.75f, true, Duration.ZERO));

        // A present, positive cap and a boundary-valid quality of exactly 1.0 must both be accepted.
        MjpegSettings.Transmit accepted = new MjpegSettings.Transmit(
                "0.0.0.0", OptionalInt.of(8), 1.0f, false, Duration.ofSeconds(1));
        assertEquals(8, accepted.maxViewerThreads().getAsInt());
        assertFalse(accepted.loop());
    }
}
