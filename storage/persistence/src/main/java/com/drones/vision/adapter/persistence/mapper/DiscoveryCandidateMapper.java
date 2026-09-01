package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.DiscoveryCandidateEntity;
import com.drones.vision.kernel.AssetId;
import com.drones.vision.kernel.CategoryId;
import com.drones.vision.kernel.StreamDescriptor;
import com.drones.vision.warehouse.domain.model.DiscoveredDevice;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidate;
import com.drones.vision.warehouse.domain.model.DiscoveryCandidateId;

import java.net.URI;
import java.util.Map;

/**
 * {@link DiscoveryCandidate} &harr; {@link DiscoveryCandidateEntity} mapping
 * (docs/plans/active/ZERO-CONFIG-ONBOARDING-CONTEXT.md §11, Z2c). The nested {@link
 * DiscoveredDevice} is flattened onto the entity's own columns in both directions — see the
 * entity's own javadoc for why {@code suggestedStreamProtocol}/{@code suggestedStreamUri}/{@code
 * suggestedStreamOptions} travel together as one nullable group.
 */
public final class DiscoveryCandidateMapper {

    private DiscoveryCandidateMapper() {
    }

    public static DiscoveryCandidateEntity toEntity(DiscoveryCandidate candidate) {
        DiscoveredDevice discovered = candidate.discovered();
        StreamDescriptor stream = discovered.suggestedStream();
        String suggestedStreamProtocol = stream == null ? null : stream.protocol();
        String suggestedStreamUri = stream == null ? null : stream.uri().toString();
        Map<String, String> suggestedStreamOptions = stream == null ? null : stream.options();
        String suggestedCategory = discovered.suggestedCategory() == null ? null : discovered.suggestedCategory().slug();
        return new DiscoveryCandidateEntity(candidate.id().value(), candidate.identityKey(), discovered.method(),
                discovered.name(), discovered.address().toString(), suggestedCategory, suggestedStreamProtocol,
                suggestedStreamUri, suggestedStreamOptions, discovered.details(), candidate.firstSeen(),
                candidate.lastSeen(), candidate.status(),
                candidate.registeredAsset() == null ? null : candidate.registeredAsset().value());
    }

    public static DiscoveryCandidate toDomain(DiscoveryCandidateEntity entity) {
        CategoryId suggestedCategory = entity.suggestedCategory() == null ? null
                : new CategoryId(entity.suggestedCategory());
        StreamDescriptor suggestedStream = entity.suggestedStreamProtocol() == null ? null
                : new StreamDescriptor(entity.suggestedStreamProtocol(), URI.create(entity.suggestedStreamUri()),
                        entity.suggestedStreamOptions() == null ? Map.of() : entity.suggestedStreamOptions());
        DiscoveredDevice discovered = new DiscoveredDevice(entity.method(), entity.name(),
                URI.create(entity.address()), suggestedCategory, suggestedStream, entity.details());
        AssetId registeredAsset = entity.registeredAssetId() == null ? null : new AssetId(entity.registeredAssetId());
        return new DiscoveryCandidate(new DiscoveryCandidateId(entity.id()), entity.identityKey(), discovered,
                entity.firstSeen(), entity.lastSeen(), entity.status(), registeredAsset);
    }
}
