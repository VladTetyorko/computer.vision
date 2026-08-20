package com.drones.vision.adapter.cvgrpc;

import java.time.Duration;
import java.util.Objects;

/**
 * Plain, framework-free settings for {@link GrpcReferenceIndexPort}'s {@code BuildReferenceIndex}
 * upload (docs/plans/active/VISUAL-GEO-V2-PLAN.md &sect;3.6) — a separate record from {@link
 * GrpcCvSettings} even though its two fields mirror {@link GrpcCvSettings#uploadTimeout()}/{@link
 * GrpcCvSettings#uploadChunkBytes()} exactly: the geolocation feature is independently flag-gated
 * ({@code vision.geo.visual.enabled}, D9) and this module's other settings/wiring must never force
 * a dependency between the two features. {@code vision-app}'s wiring binds a {@code
 * VisionGeoProperties} record to {@code vision.geo.visual.upload.*} and maps it to one of these
 * before handing it to {@link GrpcReferenceIndexPort}'s constructor — this class must never be
 * constructed from a {@code @ConfigurationProperties} type directly, same rule {@link GrpcCvSettings}
 * documents for itself.
 *
 * @param uploadTimeout    per-call deadline covering the whole {@code BuildReferenceIndex} stream —
 *                         archive framing, the upload round trip, AND cv-service's own build time
 *                         (encoding/indexing/calibration), since {@code BuildReferenceIndex} is bidi
 *                         and this deadline covers the entire call, not just the client-to-server
 *                         leg; must be positive. Defaults far longer than {@link
 *                         GrpcCvSettings#uploadTimeout()}'s 300s (a dataset upload) because building
 *                         a reference index is real compute (descriptor encoding across up to {@code
 *                         vision.geo.visual.region.max-tiles} tiles), not just a transfer
 * @param uploadChunkBytes target size of each streamed {@code ReferencePackChunk}; must be positive.
 *                         Same default as {@link GrpcCvSettings#uploadChunkBytes()} (256 KiB) — no
 *                         reason for the two chunk sizes to differ, but kept as separate config so
 *                         they CAN diverge without touching the unrelated feature's settings
 */
public record GeoUploadSettings(Duration uploadTimeout, int uploadChunkBytes) {

    /** Default {@link #uploadTimeout()} — 30 minutes; see this field's own javadoc for why it dwarfs upload's 300s. */
    static final long UPLOAD_TIMEOUT_MINUTES = 30;

    /** Default {@link #uploadChunkBytes()} — 256 KiB, matching {@link GrpcCvSettings#CHUNK_BYTES}. */
    static final int CHUNK_BYTES = 262_144;

    public GeoUploadSettings {
        Objects.requireNonNull(uploadTimeout, "uploadTimeout must not be null");
        if (uploadTimeout.isNegative() || uploadTimeout.isZero()) {
            throw new IllegalArgumentException("uploadTimeout must be positive, was " + uploadTimeout);
        }
        if (uploadChunkBytes <= 0) {
            throw new IllegalArgumentException("uploadChunkBytes must be positive, was " + uploadChunkBytes);
        }
    }

    /** {@link #uploadTimeout()} = 30 minutes, {@link #uploadChunkBytes()} = 256 KiB. */
    public static GeoUploadSettings defaults() {
        return new GeoUploadSettings(Duration.ofMinutes(UPLOAD_TIMEOUT_MINUTES), CHUNK_BYTES);
    }
}
