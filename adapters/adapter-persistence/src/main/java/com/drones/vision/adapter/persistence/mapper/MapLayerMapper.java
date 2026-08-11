package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.LayerGrantEmbeddable;
import com.drones.vision.adapter.persistence.entity.MapLayerEntity;
import com.drones.vision.domain.model.GroupId;
import com.drones.vision.domain.model.LayerGrant;
import com.drones.vision.domain.model.LayerId;
import com.drones.vision.domain.model.MapLayer;
import com.drones.vision.domain.model.Ownership;
import com.drones.vision.domain.model.UserId;

import java.util.List;

/**
 * {@link MapLayer} ⟷ {@link MapLayerEntity} (docs/plans/done/MAP-REWORK-PLAN.md §4.4).
 *
 * <p>{@code ownership} flattens to owner/group, the same choice {@code AssetMapper}/{@code
 * MarkMapper} make. The grant list converts element-wise through {@link LayerGrantEmbeddable} — the
 * one persistence-local mirror type in this module (see that class's javadoc for why a JPA
 * {@code @Embeddable} cannot be the domain record itself).
 */
public final class MapLayerMapper {

    private MapLayerMapper() {
    }

    public static MapLayerEntity toEntity(MapLayer layer) {
        List<LayerGrantEmbeddable> grants = layer.grants().stream()
                .map(grant -> new LayerGrantEmbeddable(grant.subjectType(), grant.subjectId(), grant.level()))
                .toList();
        return new MapLayerEntity(layer.id().value(), layer.name(), layer.kind(),
                layer.ownership().ownerId().value(), layer.ownership().groupId().value(), grants,
                layer.createdAt());
    }

    public static MapLayer toDomain(MapLayerEntity entity) {
        List<LayerGrant> grants = entity.grants().stream()
                .map(grant -> new LayerGrant(grant.subjectType(), grant.subjectId(), grant.level()))
                .toList();
        Ownership ownership = new Ownership(new UserId(entity.ownerUserId()), new GroupId(entity.groupId()));
        return new MapLayer(new LayerId(entity.id()), entity.name(), entity.kind(), ownership, grants,
                entity.createdAt());
    }
}
