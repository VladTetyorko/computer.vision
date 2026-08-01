package com.drones.vision.api;

import com.drones.vision.api.exceptions.PayloadTooLargeException;
import com.drones.vision.domain.model.AssetId;
import com.drones.vision.domain.model.AssetImage;
import com.drones.vision.domain.port.out.AssetImageRepositoryPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * Driving REST adapter for CONTRACT 2's asset image endpoints (docs/UX-REWORK-PLAN.md §U-d item
 * 3): {@code PUT}/{@code GET}/{@code DELETE /api/assets/{id}/image}.
 *
 * <p>Constructor-injected directly with {@link AssetImageRepositoryPort} — a deliberate deviation
 * from this codebase's usual "controllers call a driving-port service, driven ports only
 * read-only" convention (see {@code .claude/skills/java-clean-code/SKILL.md} §1 and this module's
 * own Conventions in MODULE.md): storing/fetching/deleting bytes keyed by an asset id has no
 * business rule beyond the wire-boundary concerns already enforced right here (max size, allowed
 * content types) — wrapping the port's three one-line pass-throughs in a same-shaped application
 * service would be ceremony with no behavior of its own. This mirrors the port's own javadoc: it
 * carries no asset-existence check either (no referential integrity, matching every other
 * repository port's convention in this codebase).
 *
 * <p>An unknown asset id behaves exactly like a known asset with no stored image: {@code GET}
 * 404s (no image found, for either reason), {@code PUT} stores the bytes regardless (harmless — an
 * orphaned image never surfaces, since only known/listed assets are ever asked about), and {@code
 * DELETE} is an idempotent no-op either way.
 */
@RestController
public class AssetImageController {

    /** CONTRACT 2's pinned limit: 2MB. */
    static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE);

    private final AssetImageRepositoryPort assetImageRepositoryPort;

    public AssetImageController(AssetImageRepositoryPort assetImageRepositoryPort) {
        this.assetImageRepositoryPort =
                Objects.requireNonNull(assetImageRepositoryPort, "assetImageRepositoryPort must not be null");
    }

    /**
     * Stores (or replaces) an asset's image.
     *
     * @param id          the asset the image belongs to, as a canonical UUID string
     * @param contentType the body's declared content type; must be {@code image/jpeg} or
     *                    {@code image/png}
     * @param body        the raw image bytes
     * @throws IllegalArgumentException  if {@code contentType} is missing/unsupported, or the body
     *                                    is empty (400)
     * @throws PayloadTooLargeException  if the body exceeds {@value #MAX_IMAGE_BYTES} bytes (413)
     */
    @PutMapping("/api/assets/{id}/image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void put(@PathVariable String id,
                     @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                     @RequestBody byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("image body must not be empty");
        }
        if (body.length > MAX_IMAGE_BYTES) {
            throw new PayloadTooLargeException(
                    "image is " + body.length + " bytes, exceeding the " + MAX_IMAGE_BYTES + "-byte limit");
        }
        String normalizedContentType = normalizeContentType(contentType);
        assetImageRepositoryPort.save(AssetId.of(id), new AssetImage(body, normalizedContentType));
    }

    /**
     * Fetches an asset's stored image.
     *
     * @param id the asset id, as a canonical UUID string
     * @return the image bytes with their stored content type
     * @throws NoSuchElementException if no image is stored for {@code id} (404) — the same
     *                                 response whether the asset itself is unknown or simply has
     *                                 no image
     */
    @GetMapping("/api/assets/{id}/image")
    public ResponseEntity<byte[]> get(@PathVariable String id) {
        AssetImage image = assetImageRepositoryPort.findByAssetId(AssetId.of(id))
                .orElseThrow(() -> new NoSuchElementException("No image stored for asset: " + id));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.data());
    }

    /**
     * Removes an asset's stored image, if any. Idempotent — no image stored (or an unknown asset
     * id) is a no-op, same as {@link AssetImageRepositoryPort#deleteByAssetId}'s own contract.
     *
     * @param id the asset id, as a canonical UUID string
     */
    @DeleteMapping("/api/assets/{id}/image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        assetImageRepositoryPort.deleteByAssetId(AssetId.of(id));
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException(
                    "Content-Type header is required (expected image/jpeg or image/png)");
        }
        String normalized = contentType.toLowerCase(Locale.ROOT).trim();
        if (!SUPPORTED_CONTENT_TYPES.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Unsupported image Content-Type: " + contentType + " (expected image/jpeg or image/png)");
        }
        return normalized;
    }
}
