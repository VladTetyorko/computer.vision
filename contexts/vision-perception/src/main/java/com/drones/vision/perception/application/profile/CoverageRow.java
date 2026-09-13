package com.drones.vision.perception.application.profile;

import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.model.ModelRef;

import java.util.List;
import java.util.Objects;

/**
 * One row of the fleet-wide "what CV will do on each asset" table — {@link
 * CvProfileService#coverage} 's read model, mirroring the frozen wire contract of {@code GET
 * /api/cv/coverage} (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.2): {@code {"assetId","name",
 * "categoryId","profileId","profileName","source","detectionEnabled","model","labelFilter",
 * "labelDenyFilter"}}.
 *
 * <p>{@code profileId}/{@code profileName} follow the same nullability as {@link
 * EffectiveProfile}: {@code null} exactly when {@code source} is {@link ProfileSource#PLATFORM}.
 *
 * <p><b>Every field below is an EFFECTIVE (folded) value, never a raw, possibly-null {@link
 * com.drones.vision.perception.domain.model.CvProfile} knob</b> (docs/plans/active/
 * CV-ORCHESTRATION-PLAN.md &sect;4.7, decision E22, wave W7.1): {@code
 * DefaultCvProfileService#toCoverageRow} builds this row from {@link EffectiveProfile#config()} —
 * the result of {@link CvProfileResolver#resolve} folding every bound tier — not from the matched
 * profile's own fields directly, so a tier that leaves a knob unset (inherit) is already resolved to
 * whatever the fold produced by the time it reaches this record. This row intentionally carries no
 * per-knob provenance of its own (unlike {@link EffectiveProfile#sources()}) — it is a fleet-wide
 * summary row, not a single asset's detail view.
 *
 * @param assetId          the asset this row is for
 * @param name             the asset's display name
 * @param categoryId       the asset's category
 * @param profileId        the resolved profile's id, or {@code null} iff {@code source} is {@link
 *                         ProfileSource#PLATFORM}
 * @param profileName      the resolved profile's name, or {@code null} iff {@code source} is
 *                         {@link ProfileSource#PLATFORM}
 * @param source           which layer resolved this asset's configuration
 * @param detectionEnabled the resolved, effective configuration's detection switch
 * @param model            the resolved, effective configuration's model
 * @param labelFilter      the resolved, effective configuration's label allow-list; empty means all
 *                         labels; defensively copied
 * @param labelDenyFilter  the resolved, effective configuration's label deny-list; empty means none
 *                         denied; defensively copied
 */
public record CoverageRow(AssetId assetId, String name, CategoryId categoryId, CvProfileId profileId,
                           String profileName, ProfileSource source, boolean detectionEnabled, ModelRef model,
                           List<String> labelFilter, List<String> labelDenyFilter) {

    public CoverageRow {
        Objects.requireNonNull(assetId, "assetId must not be null");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("CoverageRow name must not be blank");
        }
        Objects.requireNonNull(categoryId, "categoryId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(labelFilter, "labelFilter must not be null");
        Objects.requireNonNull(labelDenyFilter, "labelDenyFilter must not be null");
        boolean namesAProfile = profileId != null || profileName != null;
        if (source == ProfileSource.PLATFORM && namesAProfile) {
            throw new IllegalArgumentException("CoverageRow profileId/profileName must be null for PLATFORM");
        }
        if (source != ProfileSource.PLATFORM && (profileId == null || profileName == null)) {
            throw new IllegalArgumentException(
                    "CoverageRow profileId/profileName must not be null when source is not PLATFORM");
        }
        labelFilter = List.copyOf(labelFilter);
        labelDenyFilter = List.copyOf(labelDenyFilter);
    }
}
