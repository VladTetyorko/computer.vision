package com.drones.vision.app.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * mediamtx read/publish credentials this app authenticates with ({@code vision.media.*}) —
 * docs/plans/active/ASSET-FLOWS-PLAN.md &sect;2 "S6 auth model": {@code ./mediamtx.yml} now gates
 * {@code read}/{@code playback} on every path behind one viewer account, and {@code publish} on
 * every path except the {@code ingest/} zero-config funnel behind one publisher account (that
 * prefix stays open-publish by design — candidates are quarantined until an operator accepts
 * them, see that file's own comments).
 *
 * <p>Deliberately its own top-level {@code vision.media} namespace, not nested under {@code
 * vision.publish}: unlike {@code vision.publish.*} (egress-only — encoder/resilience/cadence/the
 * mediamtx endpoints {@code MediamtxStreamPublisher}/{@code MediamtxProxyPublisher} push to or
 * dial), these credentials are consumed by collaborators on both sides of the hexagon that have
 * nothing else in common: {@code wiring.PublishWiring} (the publish adapters, both push and
 * proxy modes) and the {@code vision-api} HLS reverse proxy ({@code HlsProxyController}, via
 * {@code PublishWiring}'s bridging into {@code VisionApiProperties.HlsProxy}) — see {@code
 * com.drones.vision.adapter.publishhls.MediaCredentials}'s own javadoc for the full list of
 * readers.
 *
 * <h2>Defaults are not blank, on purpose</h2>
 * Unlike most optional credential pairs in this codebase ({@link VisionPublishProperties.Mediamtx#apiUser()},
 * left unset by default because mediamtx's Control API auth is usually satisfied by IP/network
 * trust instead), these four default to a real, clearly-placeholder account
 * ({@code vision-viewer}/{@code change-me}, {@code vision-publisher}/{@code change-me}) — CLAUDE.md
 * rule 1 forbids an empty-string credential default, and {@code ./mediamtx.yml}'s own {@code
 * authInternalUsers} block ships the identical {@code change-me} passwords for the same two
 * accounts, so the compiled default and the shipped mediamtx config agree out of the box. Sending
 * these defaults to a mediamtx that has no auth configured at all is harmless — an unauthenticated
 * action simply ignores whatever {@code user}/{@code pass} a client happens to send — so there is
 * no "off" mode to represent here the way {@link com.drones.vision.adapter.publishhls.MediaCredentials#none()}
 * exists for; {@code PublishWiring} builds a real {@code MediaCredentials} from this record
 * unconditionally.
 *
 * <p><b>Operators MUST override these defaults</b> before exposing this stack beyond local
 * dev/demo — same "DEV-ONLY, must be changed" posture as {@code
 * VisionPersistenceProperties}'s seeded dev users. Overriding only here (Spring side) without also
 * editing the matching {@code pass:} lines in {@code ./mediamtx.yml} breaks auth: mediamtx's own
 * env-var loader has no override mechanism for {@code authInternalUsers} (a list-of-struct config
 * field), so the two are independent copies of the same secret that must be changed together — see
 * {@code mediamtx.yml}'s own header comment and {@code docker-compose.yml}'s {@code
 * VISION_MEDIA_AUTH_*} block for the full explanation.
 *
 * @param auth the viewer/publisher credential pair; defaulted as a whole when absent
 */
@ConfigurationProperties(prefix = "vision.media")
public record VisionMediaProperties(Auth auth) {

    public VisionMediaProperties {
        if (auth == null) {
            auth = new Auth(Auth.DEFAULT_VIEWER_USERNAME, Auth.DEFAULT_VIEWER_PASSWORD,
                    Auth.DEFAULT_PUBLISHER_USERNAME, Auth.DEFAULT_PUBLISHER_PASSWORD);
        }
    }

    /**
     * @param viewerUsername    mediamtx {@code read}/{@code playback} account username; default
     *                          {@value #DEFAULT_VIEWER_USERNAME}
     * @param viewerPassword    paired password; default {@value #DEFAULT_VIEWER_PASSWORD} — a
     *                          placeholder, must be changed before this stack is exposed beyond
     *                          local dev/demo (see class javadoc)
     * @param publisherUsername mediamtx {@code publish} account username (every path except {@code
     *                          ingest/}); default {@value #DEFAULT_PUBLISHER_USERNAME}
     * @param publisherPassword paired password; default {@value #DEFAULT_PUBLISHER_PASSWORD} — same
     *                          "must be changed" caveat as {@code viewerPassword}
     */
    public record Auth(@DefaultValue(Auth.DEFAULT_VIEWER_USERNAME) String viewerUsername,
                        @DefaultValue(Auth.DEFAULT_VIEWER_PASSWORD) String viewerPassword,
                        @DefaultValue(Auth.DEFAULT_PUBLISHER_USERNAME) String publisherUsername,
                        @DefaultValue(Auth.DEFAULT_PUBLISHER_PASSWORD) String publisherPassword) {

        static final String DEFAULT_VIEWER_USERNAME = "vision-viewer";
        static final String DEFAULT_VIEWER_PASSWORD = "change-me";
        static final String DEFAULT_PUBLISHER_USERNAME = "vision-publisher";
        static final String DEFAULT_PUBLISHER_PASSWORD = "change-me";
    }
}
