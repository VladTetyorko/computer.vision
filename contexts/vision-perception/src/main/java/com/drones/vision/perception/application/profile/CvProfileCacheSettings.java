package com.drones.vision.perception.application.profile;

import java.time.Duration;
import java.util.Objects;

/**
 * Tunables for {@link CvProfileCache} — today just the lazy-reload TTL. A settings record rather
 * than a bare {@code Duration} constructor parameter so a later wave can add a field without
 * rippling into every call site (java-clean-code &sect;3).
 *
 * <p>{@code ttl} is wired from {@code vision.cv.profiles.cache-ttl} (default 60s) in W5
 * (docs/plans/active/CV-SETTINGS-CONTEXT.md, W2 &rarr; W5 handoff) — this module knows nothing of Spring
 * properties, so the default lives at the wiring layer, not here.
 *
 * @param ttl how long a read-only {@link CvProfileCache#snapshot()} may serve a previously loaded
 *            snapshot before reloading from {@link com.drones.vision.perception.domain.port.CvProfileRepositoryPort};
 *            a write (create/update/delete/bind/unbind) always reloads immediately regardless of
 *            {@code ttl} — see {@link CvProfileCache}'s own javadoc. Must be positive.
 */
public record CvProfileCacheSettings(Duration ttl) {

    public CvProfileCacheSettings {
        Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("CvProfileCacheSettings ttl must be positive: " + ttl);
        }
    }
}
