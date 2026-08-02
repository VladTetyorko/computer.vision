package com.drones.vision.adapter.publishhls;

import com.drones.vision.domain.model.StreamId;

import java.net.URI;

/**
 * Builds every mediamtx URL {@link MediamtxStreamPublisher} formats <b>except</b> the
 * playback-server {@code /get} query, which {@link MediamtxPlaybackUrls} already owns (shared with
 * {@link MediamtxReplayFrameExtractor}) — this class is the RTSP push URL and the HLS/WHEP viewing
 * URL counterpart to that one.
 *
 * <p>Package-private, stateless, pure string formatting — no I/O, no validation beyond what {@link
 * URI#create(String)} itself performs on the result. Mirrors {@link MediamtxPlaybackUrls}'s own
 * shape: a trailing slash on the base is tolerated (stripped before appending).
 */
final class MediamtxUrls {

    private static final String HLS_PLAYLIST_SUFFIX = "/index.m3u8";
    private static final String WHEP_PATH_SUFFIX = "/whep";

    private MediamtxUrls() {
    }

    /** @return {@code {rtspPushBase}/{streamId}} — the RTSP URL the encoder pushes frames to. */
    static String pushUrl(URI rtspPushBase, StreamId id) {
        return withoutTrailingSlash(rtspPushBase.toString()) + "/" + id.value();
    }

    /** @return {@code {hlsViewBase}/{streamId}/index.m3u8} — mediamtx's HLS playlist URL for the stream. */
    static String viewUrl(URI hlsViewBase, StreamId id) {
        return withoutTrailingSlash(hlsViewBase.toString()) + "/" + id.value() + HLS_PLAYLIST_SUFFIX;
    }

    /** @return {@code {whepViewBase}/{streamId}/whep} — mediamtx's WHEP signaling URL for the stream. */
    static String whepUrl(URI whepViewBase, StreamId id) {
        return withoutTrailingSlash(whepViewBase.toString()) + "/" + id.value() + WHEP_PATH_SUFFIX;
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
