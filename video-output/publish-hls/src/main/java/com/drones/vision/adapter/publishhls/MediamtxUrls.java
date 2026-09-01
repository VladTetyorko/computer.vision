package com.drones.vision.adapter.publishhls;

import com.drones.vision.kernel.StreamId;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Builds every mediamtx URL {@link MediamtxStreamPublisher} formats <b>except</b> the
 * playback-server {@code /get} query, which {@link MediamtxPlaybackUrls} already owns (shared with
 * {@link MediamtxReplayFrameExtractor}) — this class is the RTSP push/read URL and the HLS/WHEP
 * viewing URL counterpart to that one.
 *
 * <p>Package-private, stateless, pure string formatting — no I/O, no validation beyond what {@link
 * URI#create(String)} itself performs on the result. Mirrors {@link MediamtxPlaybackUrls}'s own
 * shape: a trailing slash on the base is tolerated (stripped before appending).
 *
 * <h2>Two auth idioms (docs/plans/active/ASSET-FLOWS-PLAN.md &sect;2 S6)</h2>
 * RTSP ({@link #pushUrl(URI, StreamId, MediaCredentials)}/{@link #readUrl}) embeds {@code
 * user:pass@} in the URL's authority — the mechanism both FFmpeg's rtsp muxer/demuxer and mediamtx
 * itself support natively. WHEP ({@link #whepUrl(URI, StreamId, MediaCredentials)}) instead appends
 * {@code ?user=&pass=} query parameters — mediamtx's own documented mechanism for HTTP-based
 * protocols a browser client drives directly (a WHEP session is a JS {@code fetch()} POST the
 * platform never controls, unlike this app's own outbound HTTP calls, which can send a real {@code
 * Authorization} header instead — see {@code MediamtxControlApi}/{@code HlsProxyController} for
 * that idiom). Every credential-aware method here is careful to be the ONLY thing that sees the
 * plaintext password; callers that also log the URL (e.g. {@code MediamtxStreamPublisher}'s
 * lifecycle logs) must build a separate, credential-free display string via the plain (non-{@code
 * MediaCredentials}) overload instead of logging the authenticated one.
 */
final class MediamtxUrls {

    private static final String HLS_PLAYLIST_SUFFIX = "/index.m3u8";
    private static final String WHEP_PATH_SUFFIX = "/whep";

    private MediamtxUrls() {
    }

    /**
     * @return {@code {rtspPushBase}/{streamId}} — the RTSP URL the encoder pushes frames to,
     *         credential-free. Use only for logging/display; {@link #pushUrl(URI, StreamId,
     *         MediaCredentials)} is what {@code H264RecorderFactory} must actually push to once
     *         mediamtx requires publish auth.
     */
    static String pushUrl(URI rtspPushBase, StreamId id) {
        return withoutTrailingSlash(rtspPushBase.toString()) + "/" + id.value();
    }

    /** Same as {@link #pushUrl(URI, StreamId)}, with the publisher credential embedded in the authority when present. */
    static String pushUrl(URI rtspPushBase, StreamId id, MediaCredentials credentials) {
        return withUserinfo(pushUrl(rtspPushBase, id), credentials.hasPublisherCredentials()
                ? credentials.publisherUsername() : null, credentials.publisherPassword());
    }

    /**
     * Same address {@link #pushUrl(URI, StreamId)} formats, used as a <b>read</b> client instead
     * (mediamtx's live RTSP output for the same path — {@link MediamtxLiveFrameGrabber}), with the
     * viewer credential embedded in the authority when present.
     */
    static String readUrl(URI rtspBase, StreamId id, MediaCredentials credentials) {
        return withUserinfo(pushUrl(rtspBase, id), credentials.hasViewerCredentials()
                ? credentials.viewerUsername() : null, credentials.viewerPassword());
    }

    /** @return {@code {hlsViewBase}/{streamId}/index.m3u8} — mediamtx's HLS playlist URL for the stream. */
    static String viewUrl(URI hlsViewBase, StreamId id) {
        return withoutTrailingSlash(hlsViewBase.toString()) + "/" + id.value() + HLS_PLAYLIST_SUFFIX;
    }

    /** @return {@code {whepViewBase}/{streamId}/whep} — mediamtx's WHEP signaling URL for the stream, credential-free. */
    static String whepUrl(URI whepViewBase, StreamId id) {
        return withoutTrailingSlash(whepViewBase.toString()) + "/" + id.value() + WHEP_PATH_SUFFIX;
    }

    /**
     * Same as {@link #whepUrl(URI, StreamId)}, with {@code ?user=&pass=} appended when the viewer
     * credential is present — this URL is handed to the browser verbatim (see class javadoc for why
     * query parameters, not a header, carry the credential here).
     */
    static String whepUrl(URI whepViewBase, StreamId id, MediaCredentials credentials) {
        return appendCredentialQuery(whepUrl(whepViewBase, id),
                credentials.hasViewerCredentials() ? credentials.viewerUsername() : null, credentials.viewerPassword());
    }

    private static String withUserinfo(String url, String username, String password) {
        if (username == null) {
            return url;
        }
        int schemeEnd = url.indexOf("://") + 3;
        return url.substring(0, schemeEnd) + username + ":" + (password == null ? "" : password) + "@"
                + url.substring(schemeEnd);
    }

    static String appendCredentialQuery(String url, String username, String password) {
        if (username == null) {
            return url;
        }
        String separator = url.indexOf('?') >= 0 ? "&" : "?";
        return url + separator + "user=" + urlEncode(username) + "&pass=" + urlEncode(password == null ? "" : password);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String withoutTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
