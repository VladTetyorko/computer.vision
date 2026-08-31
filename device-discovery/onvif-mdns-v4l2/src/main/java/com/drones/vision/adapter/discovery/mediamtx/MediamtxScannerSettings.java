package com.drones.vision.adapter.discovery.mediamtx;

import java.net.URI;
import java.util.Objects;

/**
 * Configuration {@link MediamtxPathScanner} needs to poll mediamtx's Control API and turn a ready
 * push path into a candidate's suggested RTSP URI (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md
 * &sect;11, "Z3 amendment (2026-08-31) -- poll, not hook").
 *
 * <p>Deliberately just data, with no defaulted/convenience constructor (CLAUDE.md rule 10) --
 * {@code vision-app}'s wiring is responsible for sourcing {@code apiBase}/{@code rtspBase} from the
 * SAME {@code vision.publish.mediamtx.*} properties {@code MediamtxStreamPublisher} already uses to
 * reach this exact mediamtx instance (this scanner talks to no other mediamtx), rather than a
 * parallel {@code vision.discovery.mediamtx.api-url}/{@code rtsp-base} pair that could silently
 * drift out of sync with it.
 *
 * @param apiBase    mediamtx's Control API base this JVM can reach, e.g. {@code
 *                   http://localhost:19997} standalone or {@code http://mediamtx:9997} in
 *                   docker-compose -- the same reachability {@code vision.publish.mediamtx.api-base}
 *                   already solves; {@code /v3/paths/list} is appended by this class
 * @param rtspBase   mediamtx's RTSP base this JVM can reach, used to build a ready path's suggested
 *                   stream URI -- the same reachability {@code vision.publish.mediamtx.rtsp-base}
 *                   already solves. Once a candidate built from this base is registered, it is
 *                   pulled back by {@code adapter-rtsp} exactly like any other RTSP camera (MEDIA-SOT)
 * @param pathPrefix mediamtx path-name prefix a device push must live under to be reported as a
 *                   candidate (the convention devices push to, e.g. {@code ingest/}, documented in
 *                   {@code mediamtx.yml}); paths outside this prefix are vision's own published
 *                   streams and are never reported -- see {@link MediamtxPathScanner}'s class
 *                   javadoc for why. Must include any delimiter the caller wants matched (e.g. the
 *                   trailing {@code /}); this class does no normalization of its own
 */
public record MediamtxScannerSettings(URI apiBase, URI rtspBase, String pathPrefix) {

    public MediamtxScannerSettings {
        Objects.requireNonNull(apiBase, "apiBase must not be null");
        Objects.requireNonNull(rtspBase, "rtspBase must not be null");
        if (pathPrefix == null || pathPrefix.isBlank()) {
            throw new IllegalArgumentException("pathPrefix must not be blank");
        }
    }
}
