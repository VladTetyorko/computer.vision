package com.drones.vision.adapter.publishhls;

import java.net.URI;
import java.time.Instant;

/**
 * Builds mediamtx's playback-server {@code /get} query URL — the one place that knows the exact
 * query-string shape mediamtx's playback HTTP server (docs/plans/done/OPS-CORE-PLAN.md §R) expects: {@code
 * {playbackBase}/get?path=<name>&start=<RFC3339>&duration=<seconds>}.
 *
 * <p>Shared by {@link MediamtxStreamPublisher#playbackUrl} (an arbitrary, caller-supplied clip
 * window, surfaced to a viewer) and {@link MediamtxReplayFrameExtractor} (a fixed one-second window
 * used purely to make mediamtx seek to the wanted instant before this process decodes a single
 * frame from it, docs/plans/done/CV-TRAINING-V2-PLAN.md §6) — extracted here so mediamtx's query shape is
 * expressed in exactly one place instead of twice.
 *
 * <p>Package-private, stateless, pure string formatting — no I/O, no validation beyond what {@link
 * URI#create(String)} itself performs on the result.
 */
final class MediamtxPlaybackUrls {

    private static final String PLAYBACK_GET_PATH = "/get";

    private MediamtxPlaybackUrls() {
    }

    /**
     * @param playbackBase    base URL of mediamtx's playback HTTP server, e.g. {@code
     *                        http://localhost:19996}; a trailing slash is tolerated (stripped
     *                        before appending). Must not be {@code null} — callers check for an
     *                        unconfigured base themselves, mirroring {@code
     *                        MediamtxStreamPublisher#playbackUrl}'s and {@code
     *                        MediamtxReplayFrameExtractor#frameAt}'s own honest-absence handling.
     * @param pathName        mediamtx path name — the same {@code streamId.value()} every
     *                        published stream already publishes/records under
     * @param start           window start, formatted via {@link Instant#toString()}
     *                        (RFC3339/ISO-8601 with a trailing {@code Z}) — exactly what mediamtx's
     *                        playback server parses with, verified against its own {@code
     *                        internal/playback/on_get.go} source
     * @param durationSeconds window length in whole seconds
     * @return the fully formatted {@code /get} query URL string
     */
    static String getUrl(URI playbackBase, String pathName, Instant start, long durationSeconds) {
        return withoutTrailingSlash(playbackBase.toString()) + PLAYBACK_GET_PATH
                + "?path=" + pathName + "&start=" + start + "&duration=" + durationSeconds;
    }

    /**
     * Same as {@link #getUrl(URI, String, Instant, long)}, with {@code &user=&pass=} appended when
     * {@code credentials} carries a viewer credential (docs/plans/active/ASSET-FLOWS-PLAN.md &sect;2
     * S6 — mediamtx's "playback" action is gated behind the same viewer account as "read"). Every
     * caller that also logs this URL must build the credential-free form via the other overload for
     * that log line — see {@link MediamtxUrls}'s class javadoc for the same rule applied there.
     */
    static String getUrl(URI playbackBase, String pathName, Instant start, long durationSeconds,
                          MediaCredentials credentials) {
        return MediamtxUrls.appendCredentialQuery(getUrl(playbackBase, pathName, start, durationSeconds),
                credentials.hasViewerCredentials() ? credentials.viewerUsername() : null, credentials.viewerPassword());
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
