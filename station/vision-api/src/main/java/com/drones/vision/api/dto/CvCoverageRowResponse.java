package com.drones.vision.api.dto;

import com.drones.vision.perception.application.profile.CoverageRow;
import com.drones.vision.perception.application.profile.ProfileSource;

import java.util.List;

/**
 * One row of {@code GET /api/cv/coverage}'s {@code 200} body (docs/plans/active/CV-SETTINGS-PLAN.md
 * &sect;5.2's frozen wire contract) — a per-asset resolved-profile summary.
 *
 * <p>{@code profileId}/{@code profileName} are the {@link CvProfileResponse#PLATFORM_DEFAULT_ID}/
 * {@link CvProfileResponse#PLATFORM_DEFAULT_NAME} sentinels when {@link CoverageRow#source()} is
 * {@link ProfileSource#PLATFORM} — unlike {@link EffectiveCvProfileResponse}, {@code models.ts}
 * declares every field here non-optional (§5.2's frozen shape has no {@code profileId?}), so a
 * genuine {@code null} is not on the table for this row; the synthesized sentinel fills the gap the
 * same way {@link CvProfileResponse#platformDefault} does for the nested-profile case.
 *
 * @param assetId          the asset this row is for, as a canonical UUID string
 * @param assetName        the asset's display name
 * @param categoryId       the asset's category slug
 * @param categoryName     the asset's category's human-readable name — looked up by {@link
 *                         CvProfileController} from {@code CategoryService#categories()} (not
 *                         carried by {@link CoverageRow} itself), falling back to {@code
 *                         categoryId} when the category was deleted concurrently, the same fallback
 *                         {@code DefaultAssetService#toSummary} takes for {@code AssetSummary#categoryName()}
 * @param profileId        the resolved profile's id, or the platform-default sentinel
 * @param profileName      the resolved profile's name, or the platform-default sentinel
 * @param source           {@code "ASSET"}/{@code "CATEGORY"}/{@code "ORGANIZATION"}/{@code "PLATFORM"}
 * @param detectionEnabled the resolved configuration's detection switch
 * @param model            the resolved configuration's model id
 * @param labelFilter      the resolved configuration's label allow-list
 * @param labelDenyFilter  the resolved configuration's label deny-list
 */
public record CvCoverageRowResponse(String assetId, String assetName, String categoryId, String categoryName,
                                     String profileId, String profileName, String source, boolean detectionEnabled,
                                     String model, List<String> labelFilter, List<String> labelDenyFilter) {

    /**
     * Maps one domain coverage row to the wire, synthesizing the platform-default sentinel when
     * {@code row}'s source is {@link ProfileSource#PLATFORM}.
     *
     * @param row          the coverage row to map
     * @param categoryName the asset's category's human-readable name, resolved by the caller
     * @return the response row for {@code row}
     */
    public static CvCoverageRowResponse from(CoverageRow row, String categoryName) {
        boolean platform = row.source() == ProfileSource.PLATFORM;
        String profileId = platform ? CvProfileResponse.PLATFORM_DEFAULT_ID : row.profileId().value().toString();
        String profileName = platform ? CvProfileResponse.PLATFORM_DEFAULT_NAME : row.profileName();
        return new CvCoverageRowResponse(row.assetId().value().toString(), row.name(), row.categoryId().slug(),
                categoryName, profileId, profileName, row.source().name(), row.detectionEnabled(),
                row.model().id(), List.copyOf(row.labelFilter()), List.copyOf(row.labelDenyFilter()));
    }
}
