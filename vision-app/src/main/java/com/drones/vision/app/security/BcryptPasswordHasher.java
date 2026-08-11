package com.drones.vision.app.security;

import com.drones.vision.identity.domain.port.PasswordHasherPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Objects;

/**
 * The one {@link PasswordHasherPort} implementation (docs/plans/done/U-AUTH-PLAN.md, wave 3) — BCrypt, via
 * Spring Security's {@link BCryptPasswordEncoder}.
 *
 * <p><strong>This is the only place in the codebase that references BCrypt.</strong> The
 * domain/application layers stay framework-free and see only the port; every login and every user
 * creation hashes/verifies through here. {@code verify} is a constant-time comparison performed by
 * {@link BCryptPasswordEncoder#matches}.
 */
public final class BcryptPasswordHasher implements PasswordHasherPort {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public String hash(String rawPassword) {
        if (rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("rawPassword must not be blank");
        }
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean verify(String rawPassword, String hash) {
        Objects.requireNonNull(hash, "hash must not be null");
        if (rawPassword == null) {
            return false;
        }
        return encoder.matches(rawPassword, hash);
    }
}
