package com.drones.vision.kernel;

import java.util.regex.Pattern;

/**
 * Typed identity for a {@link DeviceCategory}, e.g. {@code "fpv-drone"}.
 *
 * <p>Modeled as a value record rather than a bare {@code String} so category
 * identities cannot be mixed up with other string-typed identifiers at
 * compile time. Categories are reference data (see {@code
 * CategoryRepositoryPort}), not code, so their slugs double as stable,
 * human-readable keys — hence the lower-case-kebab format requirement,
 * enforced here rather than left to callers.
 *
 * @param slug lower-case-kebab identifier (e.g. {@code "fpv-drone"}); must not be blank and must match {@code [a-z0-9]+(-[a-z0-9]+)*}
 */
public record CategoryId(String slug) {

    private static final Pattern KEBAB_CASE = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    public CategoryId {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("CategoryId slug must not be blank");
        }
        if (!KEBAB_CASE.matcher(slug).matches()) {
            throw new IllegalArgumentException("CategoryId slug must be lower-case-kebab: " + slug);
        }
    }
}
