package com.drones.vision.api.controller;

import com.drones.vision.api.exception.PayloadTooLargeException;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.warehouse.application.asset.AssetService;
import com.drones.vision.platform.VisibilityScope;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.warehouse.domain.model.AssetImage;
import com.drones.vision.warehouse.domain.port.AssetImageRepositoryPort;
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
 * Driving REST adapter for CONTRACT 2's asset image endpoints (docs/plans/done/UX-REWORK-PLAN.md §U-d item
 * 3): {@code PUT}/{@code GET}/{@code DELETE /api/assets/{id}/image}.
 *
 * <p>Constructor-injected with {@link AssetService}, {@link CurrentUser}, and {@link
 * AssetImageRepositoryPort} directly — a deliberate (partial) deviation from this codebase's usual
 * "controllers call a driving-port service, driven ports only read-only" convention (see {@code
 * .claude/skills/java-clean-code/SKILL.md} §1 and this module's own Conventions in MODULE.md):
 * storing/fetching/deleting bytes keyed by an asset id has no business rule beyond the
 * wire-boundary concerns already enforced right here (max size, allowed content types), so
 * wrapping the port's three one-line pass-throughs in a same-shaped application service would be
 * ceremony with no behavior of its own. {@link AssetService} is used only for its scope-check side
 * effect below, never to read or write image bytes itself. This mirrors the port's own javadoc: it
 * carries no asset-existence check either (no referential integrity, matching every other
 * repository port's convention in this codebase) — existence/visibility is enforced here instead.
 *
 * <h2>Visibility scoping (docs/plans/done/U-SCOPE-PLAN.md, U-e slice 2, feature 1)</h2>
 * Every operation first re-reads the target asset through {@link
 * AssetService#details(VisibilityScope, AssetId)} with {@link CurrentUser#scope()} — the same
 * {@code requireInScope} guard {@link AssetController} uses before its mutations — so an
 * out-of-scope (or genuinely unknown) asset id 404s ({@link NoSuchElementException}) before any
 * image operation runs. <strong>This is a deliberate tightening versus this class's earlier
 * behavior</strong>: {@code PUT}/{@code DELETE} on an unknown asset id used to be a harmless
 * no-op/orphan write (an orphaned image never surfaced, since only known/listed assets were ever
 * asked about); both now 404 instead, exactly like {@code GET} already did — closing the same
 * cross-tenant leak an unscoped {@code GET} would otherwise have had: without this guard, any
 * authenticated caller could read, overwrite, or delete another group's asset image just by
 * guessing/knowing its UUID. With auth off (the default) the scope is unbounded, so this guard is a
 * no-op and behavior is unchanged from before scoping.
 */
@RestController
public class AssetImageController {

    /** CONTRACT 2's pinned limit: 2MB. */
    static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE);

    private final AssetService assetService;
    private final CurrentUser currentUser;
    private final AssetImageRepositoryPort assetImageRepositoryPort;

    public AssetImageController(AssetService assetService, CurrentUser currentUser,
                                 AssetImageRepositoryPort assetImageRepositoryPort) {
        this.assetService = Objects.requireNonNull(assetService, "assetService must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
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
     * @throws NoSuchElementException    if {@code id} is unknown or outside the caller's scope (404)
     */
    @PutMapping("/api/assets/{id}/image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void put(@PathVariable String id,
                     @RequestHeader(value = HttpHeaders.CONTENT_TYPE, required = false) String contentType,
                     @RequestBody byte[] body) {
        AssetId assetId = AssetId.of(id);
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("image body must not be empty");
        }
        if (body.length > MAX_IMAGE_BYTES) {
            throw new PayloadTooLargeException(
                    "image is " + body.length + " bytes, exceeding the " + MAX_IMAGE_BYTES + "-byte limit");
        }
        // Parse/validate the body (a malformed request is a 400) before the scope guard's 404, so
        // a bad request never depends on the caller's scope — same ordering as AssetController.
        String normalizedContentType = normalizeContentType(contentType);
        requireInScope(assetId);
        assetImageRepositoryPort.save(assetId, new AssetImage(body, normalizedContentType));
    }

    /**
     * Fetches an asset's stored image.
     *
     * @param id the asset id, as a canonical UUID string
     * @return the image bytes with their stored content type
     * @throws NoSuchElementException if {@code id} is unknown or outside the caller's scope, or no
     *                                 image is stored for it (404) — the same response in every case
     */
    @GetMapping("/api/assets/{id}/image")
    public ResponseEntity<byte[]> get(@PathVariable String id) {
        AssetId assetId = AssetId.of(id);
        requireInScope(assetId);
        AssetImage image = assetImageRepositoryPort.findByAssetId(assetId)
                .orElseThrow(() -> new NoSuchElementException("No image stored for asset: " + id));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.data());
    }

    /**
     * Removes an asset's stored image, if any. Idempotent for a known, in-scope asset with no
     * stored image — same as {@link AssetImageRepositoryPort#deleteByAssetId}'s own contract — but
     * an unknown or out-of-scope asset id now 404s rather than silently no-op'ing (see class
     * javadoc).
     *
     * @param id the asset id, as a canonical UUID string
     * @throws NoSuchElementException if {@code id} is unknown or outside the caller's scope (404)
     */
    @DeleteMapping("/api/assets/{id}/image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        AssetId assetId = AssetId.of(id);
        requireInScope(assetId);
        assetImageRepositoryPort.deleteByAssetId(assetId);
    }

    /**
     * Guards an operation: re-reads {@code id} through the caller's scope so an out-of-scope (or
     * unknown) asset 404s ({@link NoSuchElementException}) before the operation runs — the same
     * guard {@link AssetController#requireInScope} uses.
     */
    private void requireInScope(AssetId id) {
        assetService.details(currentUser.scope(), id);
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
