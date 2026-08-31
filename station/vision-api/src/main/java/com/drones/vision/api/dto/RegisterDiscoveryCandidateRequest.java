package com.drones.vision.api.dto;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.warehouse.application.discovery.RegisterFromCandidateCommand;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;

import java.util.Map;

/**
 * Request body for {@code POST /api/discovery/inbox/{id}/register} (docs/plans/active/
 * ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;11, Z2c) — the operator's Identify/confirm-step overrides
 * on top of the candidate's own {@code DiscoveredDevice}. Everything a {@link DiscoveredDevice}
 * cannot supply on its own ({@code displayName}, {@code category}, and optionally {@code
 * attributes}/{@code identity}) comes from this request; {@code ownership} is never taken from the
 * caller — it is always {@link com.drones.vision.api.security.CurrentUser#ownership()}, the same
 * "the request never picks who owns it" rule {@link CreateAssetRequest}'s own controller
 * ({@code AssetController#create}) already follows.
 *
 * @param displayName human-readable name for the new asset; must not be blank
 * @param category    the asset's category slug; must be a known category (validated by the service)
 * @param attributes  free-form key/value attributes; may be {@code null} (treated as empty)
 * @param identity    serial/make/model/registration facts; may be {@code null} if none are known yet
 */
public record RegisterDiscoveryCandidateRequest(String displayName, String category, Map<String, String> attributes,
                                                  IdentityRequest identity) {

    /**
     * Converts this request into a {@link RegisterFromCandidateCommand}, filling in {@code
     * ownership} from the acting user rather than the request body.
     *
     * @param ownership the acting user's ownership — who will own the new asset
     * @return the command for {@code DiscoveryInboxService#register}
     * @throws IllegalArgumentException if {@code displayName} is blank or {@code category} is not
     *                                   a lower-case-kebab slug
     */
    public RegisterFromCandidateCommand toCommand(Ownership ownership) {
        return new RegisterFromCandidateCommand(displayName, new CategoryId(category),
                attributes == null ? Map.of() : attributes, identity == null ? null : identity.toIdentity(),
                ownership);
    }
}
