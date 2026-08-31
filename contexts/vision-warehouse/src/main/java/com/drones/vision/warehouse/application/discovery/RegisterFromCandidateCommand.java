package com.drones.vision.warehouse.application.discovery;

import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.Ownership;
import com.drones.vision.warehouse.domain.model.Identity;

import java.util.Map;

/**
 * The operator's own input to {@link DiscoveryInboxService#register} — everything a discovery
 * candidate's own {@link com.drones.vision.warehouse.domain.model.DiscoveredDevice} cannot supply
 * on its own (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md &sect;11 Z2a): the wizard's
 * Identify/confirm step, prefilled from the candidate and edited by the operator before the click
 * that actually registers it.
 *
 * <p>{@code attributes}/{@code identity} are optional, mirroring {@link
 * com.drones.vision.warehouse.application.asset.AssetSpec}'s own defaults — {@code null} becomes an
 * empty map / {@link Identity#NONE} respectively, so a caller that knows nothing yet need not
 * construct either explicitly. {@code ownership} is not optional: {@link
 * DiscoveryInboxService#register} checks it against the acting scope before creating anything (see
 * that method's own javadoc), the same "does the target group stay within the caller's subtree"
 * gate {@code DefaultGroupService#create} already applies to a new group's parent.
 *
 * @param displayName human-readable name for the new asset; must not be blank
 * @param category    the asset's category; must be a known category (validated by {@link
 *                    com.drones.vision.warehouse.application.asset.AssetService#createFromCandidate})
 * @param attributes  free-form key/value attributes; {@code null} treated as empty
 * @param identity    serial/make/model/registration facts, or {@code null} for {@link Identity#NONE}
 * @param ownership   who will own the new asset
 */
public record RegisterFromCandidateCommand(String displayName, CategoryId category, Map<String, String> attributes,
                                            Identity identity, Ownership ownership) {

    public RegisterFromCandidateCommand {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("RegisterFromCandidateCommand displayName must not be blank");
        }
        if (category == null) {
            throw new IllegalArgumentException("RegisterFromCandidateCommand category must not be null");
        }
        if (ownership == null) {
            throw new IllegalArgumentException("RegisterFromCandidateCommand ownership must not be null");
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        identity = identity == null ? Identity.NONE : identity;
    }
}
