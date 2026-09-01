package com.drones.vision.adapter.publishhls;

/**
 * mediamtx read/publish credentials this module's collaborators authenticate with
 * (docs/plans/active/ASSET-FLOWS-PLAN.md &sect;2 "S6 auth model"): a viewer pair (mediamtx's {@code
 * read}/{@code playback} actions, gated on every path) and a publisher pair (mediamtx's {@code
 * publish} action, gated on every path except the {@code ingest/} zero-config funnel, which stays
 * open-publish by design — see {@code mediamtx.yml}'s own comments).
 *
 * <p>Each pair is independently nullable: {@code null}/blank means "send no credentials for that
 * role" — the correct behaviour whenever mediamtx auth is off (most unit tests, a dev stack without
 * the {@code mediamtx.yml} auth mount configured) or a role genuinely has none configured, not a
 * caller bug worth validating against. This is a deliberate top-level record, not nested inside
 * {@link PublishSettings}: unlike encoder/resilience/cadence (which are {@link
 * MediamtxStreamPublisher}-only tunables), these credentials are shared by {@link
 * MediamtxStreamPublisher} (publisher creds for its RTSP push, viewer creds for the WHEP/playback
 * URLs it hands to browsers verbatim), {@link MediamtxProxyPublisher} (viewer creds only — it never
 * pushes, see its own class javadoc), {@link MediamtxReplayFrameExtractor}, and {@link
 * MediamtxLiveFrameGrabber} (viewer creds only, both server-side reads).
 *
 * @param viewerUsername    mediamtx "read"/"playback" account username; {@code
 *                          vision.media.auth.viewer-username}
 * @param viewerPassword    paired password; {@code vision.media.auth.viewer-password}
 * @param publisherUsername mediamtx "publish" account username (every path except {@code ingest/});
 *                          {@code vision.media.auth.publisher-username}
 * @param publisherPassword paired password; {@code vision.media.auth.publisher-password}
 */
public record MediaCredentials(String viewerUsername, String viewerPassword, String publisherUsername,
                                String publisherPassword) {

    private static final MediaCredentials NONE = new MediaCredentials(null, null, null, null);

    /** No credentials for either role — mediamtx auth disabled/unconfigured (most unit tests). */
    public static MediaCredentials none() {
        return NONE;
    }

    boolean hasViewerCredentials() {
        return viewerUsername != null && !viewerUsername.isBlank();
    }

    boolean hasPublisherCredentials() {
        return publisherUsername != null && !publisherUsername.isBlank();
    }
}
