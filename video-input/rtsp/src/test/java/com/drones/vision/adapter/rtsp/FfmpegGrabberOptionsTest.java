package com.drones.vision.adapter.rtsp;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit-level checks of {@link FfmpegGrabberOptions}'s per-protocol option seams — moved out of
 * {@code FfmpegVideoSourceTest} alongside the production split (docs/plans/active/LAYERING-REFACTOR-PLAN.md
 * §5.1). Constructing an {@link FFmpegFrameGrabber} and calling {@code setOption}/{@code
 * setMaxDelay} only assigns fields — no network I/O happens until {@code start()}, which none of
 * these tests call — so this needs neither a live camera nor even a reachable socket, mirroring
 * adapter-publish-hls's {@code configureRecorder} test seam.
 */
class FfmpegGrabberOptionsTest {

    // -- docs/plans/done/MVP2-PLAN.md V-c: RTSP demuxer latency tuning --------------------

    @Test
    void configureRtspOptionsAppliesDefaultLowLatencyDemuxerTuning() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("rtsp://127.0.0.1:1/ignored");
        FfmpegSettings settings = FfmpegSettings.defaults();

        FfmpegGrabberOptions.configureRtspOptions(grabber, Map.of(), settings);

        assertEquals(String.valueOf(settings.probesizeBytes()), grabber.getOption("probesize"));
        assertEquals(FfmpegSettings.microsOption(settings.analyzeDuration()), grabber.getOption("analyzeduration"));
        assertEquals(String.valueOf(settings.reorderQueueSize()), grabber.getOption("reorder_queue_size"));
        // max_delay is NOT read back via getOption() -- see FfmpegSettings#maxDelay()'s javadoc:
        // it must go through the dedicated setMaxDelay(int) setter, not the generic string-option
        // map, so it is asserted via getMaxDelay() instead.
        assertEquals((int) (settings.maxDelay().toNanos() / 1_000L), grabber.getMaxDelay());
        // Pre-existing options must still be applied unchanged alongside the new ones.
        assertEquals(settings.transport(), grabber.getOption("rtsp_transport"));
        assertEquals(FfmpegSettings.microsOption(settings.openTimeout()), grabber.getOption("timeout"));
        assertEquals(FfmpegSettings.microsOption(settings.openTimeout()), grabber.getOption("rw_timeout"));
    }

    /** Every one of the four new options must be overridable via device options, same idiom as {@code timeout}. */
    @Test
    void configureRtspOptionsHonorsDeviceOptionOverrides() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("rtsp://127.0.0.1:1/ignored");
        Map<String, String> overrides = Map.of(
                "probesize", "65536",
                "analyzeduration", "2000000",
                "reorder_queue_size", "32",
                "max_delay", "250000");

        FfmpegGrabberOptions.configureRtspOptions(grabber, overrides, FfmpegSettings.defaults());

        assertEquals("65536", grabber.getOption("probesize"));
        assertEquals("2000000", grabber.getOption("analyzeduration"));
        assertEquals("32", grabber.getOption("reorder_queue_size"));
        assertEquals(250_000, grabber.getMaxDelay());
    }

    /** A malformed {@code max_delay} override must fall back to the default, never crash setup. */
    @Test
    void configureRtspOptionsFallsBackToDefaultMaxDelayOnAMalformedOverride() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("rtsp://127.0.0.1:1/ignored");
        FfmpegSettings settings = FfmpegSettings.defaults();

        FfmpegGrabberOptions.configureRtspOptions(grabber, Map.of("max_delay", "not-a-number"), settings);

        assertEquals((int) (settings.maxDelay().toNanos() / 1_000L), grabber.getMaxDelay());
    }

    /**
     * Contract regression: the {@code file} protocol must never see any RTSP grabber configuration
     * at all — {@code FfmpegGrabberOptions#newGrabber} only calls {@code configureRtspOptions}
     * inside its {@code uri.getScheme().equals("rtsp")} branch. Asserted here directly against a
     * fresh grabber (the production {@code file:} path never calls any {@code configure*Options}
     * method at all) rather than re-deriving scheme logic in the test — a fresh grabber's {@code
     * getOption} for any of these keys is {@code null} (never set), and {@code getMaxDelay()} stays
     * at {@code FrameGrabber}'s own default.
     */
    @Test
    void fileProtocolNeverReceivesRtspDemuxerTuning() {
        FFmpegFrameGrabber fileGrabber = new FFmpegFrameGrabber("/tmp/does-not-need-to-exist-for-this-check.mp4");

        assertNull(fileGrabber.getOption("probesize"));
        assertNull(fileGrabber.getOption("analyzeduration"));
        assertNull(fileGrabber.getOption("reorder_queue_size"));
        assertNull(fileGrabber.getOption("rtsp_transport"));
        assertNull(fileGrabber.getOption("timeout"));
        assertEquals(-1, fileGrabber.getMaxDelay(), "max_delay must stay at FrameGrabber's own unset default");
    }

    // -- docs/plans/active/DRONE-INFRA-PLAN.md I-h: SRT + UDP/MPEG-TS ingest -----------------

    /**
     * Pins the {@code mode} default-inference rule for an any-address host (docs/plans/active/DRONE-INFRA-PLAN.md
     * I-h: "the natural choice for {@code srt://0.0.0.0:port}" is {@code listener}).
     */
    @Test
    void configureSrtOptionsAppliesDefaultsAndInfersListenerModeForAnAnyAddressHost() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("srt://0.0.0.0:9000");
        URI uri = URI.create("srt://0.0.0.0:9000");
        FfmpegSettings settings = FfmpegSettings.defaults();

        FfmpegGrabberOptions.configureSrtOptions(grabber, uri, Map.of(), settings);

        long expectedLatencyMicros = settings.srtLatency().toMillis() * 1000L;
        assertEquals(String.valueOf(expectedLatencyMicros), grabber.getOption("latency"),
                "default latency must be converted from ms (this module's option unit) to us (FFmpeg's own unit)");
        assertEquals(FfmpegGrabberOptions.SRT_MODE_LISTENER, grabber.getOption("mode"),
                "an any-address host (0.0.0.0) must default to listener mode -- the app binds and waits");
        assertNull(grabber.getOption("streamid"), "streamid has no default -- absent means unset");
        assertNull(grabber.getOption("passphrase"), "passphrase has no default -- absent means unset");
        assertNull(grabber.getOption("pbkeylen"), "pbkeylen must only be set when a passphrase is given");
    }

    /** A real host (not an any-address host) must default to caller mode -- the app dials out to it. */
    @Test
    void configureSrtOptionsInfersCallerModeForARealHost() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("srt://encoder.example:9000");
        URI uri = URI.create("srt://encoder.example:9000");

        FfmpegGrabberOptions.configureSrtOptions(grabber, uri, Map.of(), FfmpegSettings.defaults());

        assertEquals(FfmpegGrabberOptions.SRT_MODE_CALLER, grabber.getOption("mode"));
    }

    /**
     * Every device-option override must be honored, and a {@code passphrase} must also set {@code
     * pbkeylen} to this class's default key length -- see {@link
     * FfmpegGrabberOptions#OPTION_SRT_PASSPHRASE}'s javadoc for why this is set explicitly rather
     * than left to libsrt's own internal default.
     */
    @Test
    void configureSrtOptionsHonorsDeviceOptionOverridesAndSetsPbkeylenWhenAPassphraseIsGiven() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("srt://encoder.example:9000");
        URI uri = URI.create("srt://encoder.example:9000");
        Map<String, String> overrides = Map.of(
                "latency", "250",
                "mode", "listener",
                "streamid", "drone-42",
                "passphrase", "a-real-passphrase");

        FfmpegGrabberOptions.configureSrtOptions(grabber, uri, overrides, FfmpegSettings.defaults());

        assertEquals("250000", grabber.getOption("latency"), "250ms override must convert to 250000us");
        assertEquals("listener", grabber.getOption("mode"), "an explicit override must win over the inferred default");
        assertEquals("drone-42", grabber.getOption("streamid"));
        assertEquals("a-real-passphrase", grabber.getOption("passphrase"));
        assertEquals("16", grabber.getOption("pbkeylen"));
    }

    /**
     * Format is always forced to {@code mpegts}, and {@code fifo_size}/{@code overrun_nonfatal} get
     * low-latency defaults while {@code buffer_size} stays unset (no forced default -- an OS-level
     * knob this module leaves alone unless asked).
     */
    @Test
    void configureUdpOptionsForcesMpegtsFormatAndAppliesLowLatencyDefaults() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("udp://127.0.0.1:9002");
        FfmpegSettings settings = FfmpegSettings.defaults();

        FfmpegGrabberOptions.configureUdpOptions(grabber, Map.of(), settings);

        assertEquals("mpegts", grabber.getFormat());
        assertEquals(String.valueOf(settings.udpFifoSizePackets()), grabber.getOption("fifo_size"));
        assertEquals(FfmpegGrabberOptions.DEFAULT_UDP_OVERRUN_NONFATAL, grabber.getOption("overrun_nonfatal"));
        assertNull(grabber.getOption("buffer_size"), "buffer_size has no forced default -- absent means OS default");
        // Internal safety default, not a StreamDescriptor option -- see FfmpegSettings#udpTimeout()'s
        // javadoc for why an always-applied read timeout is a production-critical fix, not just a
        // test convenience: it bounds a udp source's native open() call so a source nobody ever
        // sends a packet to cannot hold JavaCV's process-wide start() lock forever.
        assertEquals(FfmpegSettings.microsOption(settings.udpTimeout()), grabber.getOption("timeout"));
    }

    /** Every UDP device-option override must be honored, same idiom as SRT/RTSP above. */
    @Test
    void configureUdpOptionsHonorsDeviceOptionOverrides() {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber("udp://127.0.0.1:9002");
        Map<String, String> overrides = Map.of(
                "fifo_size", "4096",
                "overrun_nonfatal", "0",
                "buffer_size", "131072");

        FfmpegGrabberOptions.configureUdpOptions(grabber, overrides, FfmpegSettings.defaults());

        assertEquals("4096", grabber.getOption("fifo_size"));
        assertEquals("0", grabber.getOption("overrun_nonfatal"));
        assertEquals("131072", grabber.getOption("buffer_size"));
    }
}
