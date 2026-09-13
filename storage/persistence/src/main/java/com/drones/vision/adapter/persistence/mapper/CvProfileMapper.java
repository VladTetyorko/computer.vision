package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.CvProfileBindingEntity;
import com.drones.vision.adapter.persistence.entity.CvProfileEntity;
import com.drones.vision.kernel.GroupId;
import com.drones.vision.perception.domain.model.BindingScope;
import com.drones.vision.perception.domain.model.CvProfile;
import com.drones.vision.perception.domain.model.CvProfileBinding;
import com.drones.vision.perception.domain.model.CvProfileId;
import com.drones.vision.perception.domain.model.Intent;
import com.drones.vision.perception.domain.model.ModelRef;

/**
 * {@link CvProfile} &harr; {@link CvProfileEntity} and {@link CvProfileBinding} &harr; {@link
 * CvProfileBindingEntity} mapping (docs/plans/active/CV-SETTINGS-PLAN.md §5.3, CV-SETTINGS wave
 * W3; widened to the per-knob patch shape by wave W7.2, decision E22).
 *
 * <p>{@code scopeKind} is stored as its enum name and parsed back through {@link
 * BindingScope#valueOf} — the same "a stored value this build no longer knows is a genuine data
 * problem worth failing on" reasoning {@code ControlProfileMapper} already applies to {@code
 * VehicleKind}, kept here rather than an {@code @Enumerated} field because {@code scopeKind} is
 * part of {@link CvProfileBindingEntity}'s composite {@code @IdClass} key. {@code intent} (wave
 * W7.2) is stored and parsed back the same way, via {@link Intent#valueOf}.
 *
 * <p>{@code model} is one domain field split across two nullable columns ({@code modelId}/{@code
 * modelVersion}) — {@link #toEntity(CvProfile)} passes both {@code null} when {@link
 * CvProfile#model()} itself is {@code null} (inherit), never a null id alongside a non-null
 * version or vice versa, so the database's own {@code ck_cv_profiles_model_pair} CHECK constraint
 * (see {@code V36__cv_profile_patch.sql}) can never see this mapper's own writes violate it.
 */
public final class CvProfileMapper {

    private CvProfileMapper() {
    }

    /**
     * @param profile the domain profile to store
     * @return its row
     */
    public static CvProfileEntity toEntity(CvProfile profile) {
        return new CvProfileEntity(profile.id().value(), profile.name(), profile.description(), profile.builtIn(),
                profile.groupId() == null ? null : profile.groupId().value(),
                profile.model() == null ? null : profile.model().id(),
                profile.model() == null ? null : profile.model().version(), profile.confidenceThreshold(),
                profile.inferenceFps(), profile.labelFilter(), profile.labelDenyFilter(),
                profile.detectionEnabled(), profile.tracking(), profile.eventRule(),
                profile.intent() == null ? null : profile.intent().name(), profile.createdAt(),
                profile.updatedAt());
    }

    /**
     * @param entity the row to read
     * @return the domain profile
     */
    public static CvProfile toDomain(CvProfileEntity entity) {
        return new CvProfile(new CvProfileId(entity.id()), entity.name(), entity.description(), entity.builtIn(),
                entity.groupId() == null ? null : new GroupId(entity.groupId()),
                entity.modelId() == null ? null : new ModelRef(entity.modelId(), entity.modelVersion()),
                entity.confidenceThreshold(), entity.inferenceFps(), entity.labelFilter(),
                entity.labelDenyFilter(), entity.detectionEnabled(), entity.tracking(), entity.eventRule(),
                entity.intent() == null ? null : Intent.valueOf(entity.intent()), entity.createdAt(),
                entity.updatedAt());
    }

    /**
     * @param binding the domain binding to store
     * @return its row
     */
    public static CvProfileBindingEntity toEntity(CvProfileBinding binding) {
        return new CvProfileBindingEntity(binding.scopeKind().name(), binding.scopeId(),
                binding.profileId().value(), binding.createdAt());
    }

    /**
     * @param entity the row to read
     * @return the domain binding
     * @throws IllegalArgumentException if the row holds a scope kind this build cannot make sense of
     */
    public static CvProfileBinding toDomain(CvProfileBindingEntity entity) {
        return new CvProfileBinding(BindingScope.valueOf(entity.scopeKind()), entity.scopeId(),
                new CvProfileId(entity.profileId()), entity.createdAt());
    }
}
