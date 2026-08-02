package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for {@code adapter-v4l2}'s RX ({@code vision.v4l2.*}), docs/LAYERING-REFACTOR-PLAN.md
 * &sect;2.2, wave F1.
 *
 * <p>{@code adapter-v4l2} has only two tunables and, per &sect;1.3 rule 4, has no dedicated settings
 * record of its own — {@code V4l2VideoSource}'s canonical constructor takes {@code
 * (int publisherBufferCapacity, long closeJoinTimeoutMillis)} directly, so {@code
 * wiring.VideoSourceWiring} wires those two fields straight from this record.
 *
 * <p>{@code default-video-size}/{@code default-framerate}/{@code default-input-format} are
 * deliberately <b>not</b> fields here: {@code V4l2VideoSource} has no constructor parameters for
 * them today (its recognized {@code StreamDescriptor} options — {@code video_size}/{@code
 * framerate}/{@code input_format} — are per-device, passed straight through to FFmpeg's own {@code
 * v4l2} demuxer with no app-level default layer to bind onto); adding properties with no consumer
 * would be dead configuration, so this is a documented gap, not an oversight.
 *
 * @param publisherBufferCapacity RX buffer capacity, frames; default {@value #DEFAULT_PUBLISHER_BUFFER_CAPACITY}
 * @param closeJoinTimeout        RX {@code close()}'s bounded join; default 20s
 */
@ConfigurationProperties(prefix = "vision.v4l2")
public record VisionV4l2Properties(
        @DefaultValue(VisionV4l2Properties.DEFAULT_PUBLISHER_BUFFER_CAPACITY) int publisherBufferCapacity,
        @DefaultValue("20s") Duration closeJoinTimeout) {

    static final String DEFAULT_PUBLISHER_BUFFER_CAPACITY = "4";
}
