package com.drones.vision.api.controller;

import com.drones.vision.api.dto.AfterActionManifestResponse;
import com.drones.vision.api.exception.ApiExceptionHandler;
import com.drones.vision.api.security.CurrentUser;
import com.drones.vision.api.support.afteraction.AfterActionArchiveWriter;
import com.drones.vision.api.support.afteraction.AfterActionAssembler;
import com.drones.vision.api.support.afteraction.AfterActionPackage;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.UsageId;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

import java.util.Objects;

/**
 * Driving REST adapter for the after-action evidence package (docs/plans/active/
 * AFTER-ACTION-PLAN.md, the frozen wire contract &sect;3): one flight, everything the platform
 * knows about it, with an explicit account of what is missing or approximate.
 *
 * <p>Two endpoints, one collaborator ({@link AfterActionAssembler}) plus {@link CurrentUser} for
 * the acting viewer — mirroring {@link UsageTimelineController}'s own "service + acting-user"
 * shape. No {@code @PreAuthorize}: like every other controller in this module, authority is
 * resolved by threading {@link CurrentUser#scope()}/{@link CurrentUser#viewer()} down into the
 * assembler, never by a Spring Security annotation — {@code vision-api} carries zero dependency on
 * {@code org.springframework.security} (see {@link CurrentUser}'s own javadoc).
 *
 * <h2>Status codes (&sect;3.3, frozen)</h2>
 * Both endpoints share one error shape, entirely {@link AfterActionAssembler#assemble}'s own
 * exceptions through {@link ApiExceptionHandler}: {@link java.util.NoSuchElementException} (unknown
 * {@code usageId}; {@code usageId} not belonging to {@code assetId}; the asset not visible to the
 * viewer — all three collapse to {@code 404}, never leaking that a resource exists elsewhere) and
 * {@link com.drones.vision.platform.AccessDeniedException} (the viewer may see the asset but may
 * not export its evidence package &rarr; {@code 403}). This controller adds no authority logic of
 * its own.
 *
 * <h2>The archive is never buffered whole in memory</h2>
 * {@link #archive} resolves the {@link AfterActionPackage} synchronously (so a 404/403 lands on
 * the response before any bytes are written — a {@link StreamingResponseBody} whose body throws
 * cannot change an already-committed status), then hands the already-assembled package to {@link
 * AfterActionArchiveWriter}, which streams the ZIP directly onto the response's output stream.
 */
@RestController
public class AfterActionController {

    private final AfterActionAssembler assembler;
    private final CurrentUser currentUser;
    private final JsonMapper jsonMapper = new JsonMapper();
    private final AfterActionArchiveWriter archiveWriter = new AfterActionArchiveWriter(jsonMapper);

    public AfterActionController(AfterActionAssembler assembler, CurrentUser currentUser) {
        this.assembler = Objects.requireNonNull(assembler, "assembler must not be null");
        this.currentUser = Objects.requireNonNull(currentUser, "currentUser must not be null");
    }

    /**
     * The JSON manifest: what the archive would contain, including its gaps, without downloading
     * anything (D2) — also what the UI renders.
     *
     * @param assetId the asset the usage belongs to, as a canonical UUID string
     * @param usageId the usage (flight) to describe, as a canonical UUID string
     * @return the manifest
     */
    @GetMapping("/api/assets/{assetId}/usages/{usageId}/after-action")
    public AfterActionManifestResponse manifest(@PathVariable String assetId, @PathVariable String usageId) {
        AfterActionPackage pkg = assemble(assetId, usageId);
        return AfterActionManifestResponse.from(pkg);
    }

    /**
     * The ZIP archive: eight entries, always all eight (&sect;3.2) — a part that is {@code ABSENT}
     * or {@code FORBIDDEN} still gets its file, never a silent omission.
     *
     * @param assetId the asset the usage belongs to, as a canonical UUID string
     * @param usageId the usage (flight) to describe, as a canonical UUID string
     * @return the archive, streamed
     */
    @GetMapping("/api/assets/{assetId}/usages/{usageId}/after-action/archive")
    public ResponseEntity<StreamingResponseBody> archive(@PathVariable String assetId, @PathVariable String usageId) {
        AfterActionPackage pkg = assemble(assetId, usageId);
        AfterActionManifestResponse manifest = AfterActionManifestResponse.from(pkg);
        StreamingResponseBody body = out -> archiveWriter.write(pkg, manifest, out);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"after-action-" + usageId + ".zip\"")
                .body(body);
    }

    private AfterActionPackage assemble(String assetId, String usageId) {
        return assembler.assemble(AssetId.of(assetId), UsageId.of(usageId), currentUser.scope(), currentUser.viewer(),
                currentUser.userId().value().toString());
    }
}
