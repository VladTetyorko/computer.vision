package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.ControlProfileEntity;
import com.drones.vision.flight.domain.model.ActionMap;
import com.drones.vision.flight.domain.model.ChannelMap;
import com.drones.vision.flight.domain.model.ControlProfile;
import com.drones.vision.flight.domain.model.ControlProfileId;
import com.drones.vision.flight.domain.model.OwnedControlProfile;
import com.drones.vision.flight.domain.model.VehicleKind;
import com.drones.vision.kernel.UserId;

/**
 * {@link ControlProfileEntity} &harr; {@link OwnedControlProfile}
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C4).
 *
 * <p>{@code vehicleKind} is stored as its enum name and parsed back through {@link
 * VehicleKind#valueOf}: a stored kind this build no longer knows is a genuine data problem worth
 * failing on, not something to paper over with {@link VehicleKind#UNKNOWN} — that value means "the
 * vehicle has not said what it is", and quietly reusing it here would put a fabricated answer in
 * front of an operator.
 */
public final class ControlProfileMapper {

    private ControlProfileMapper() {
    }

    /**
     * @param profile the domain profile to store
     * @return its row
     */
    public static ControlProfileEntity toEntity(OwnedControlProfile profile) {
        ControlProfile layout = profile.profile();
        return new ControlProfileEntity(layout.id().value(), profile.owner().value(), layout.kind().name(),
                layout.code(), layout.displayName(), profile.active(), profile.updatedAt(),
                layout.channelMap().bindings(), layout.actionMap().bindings());
    }

    /**
     * @param entity the row to read
     * @return the domain profile
     * @throws IllegalArgumentException if the row holds a vehicle kind or a binding this build
     *                                   cannot make sense of
     */
    public static OwnedControlProfile toDomain(ControlProfileEntity entity) {
        ControlProfile layout = new ControlProfile(new ControlProfileId(entity.id()),
                VehicleKind.valueOf(entity.vehicleKind()), entity.code(), entity.displayName(),
                new ChannelMap(entity.channelMap()), new ActionMap(entity.actionMap()));
        return new OwnedControlProfile(new UserId(entity.ownerUserId()), layout, entity.active(),
                entity.updatedAt());
    }
}
