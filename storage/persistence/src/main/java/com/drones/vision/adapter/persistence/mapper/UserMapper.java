package com.drones.vision.adapter.persistence.mapper;

import com.drones.vision.adapter.persistence.entity.UserEntity;
import com.drones.vision.identity.domain.model.User;
import com.drones.vision.kernel.UserId;

/**
 * {@link User} &harr; {@link UserEntity} mapping, extracted from {@code JpaUserRepository}
 * (docs/plans/active/LAYERING-REFACTOR-PLAN.md §3/§7 row C).
 */
public final class UserMapper {

    private UserMapper() {
    }

    public static UserEntity toEntity(User user) {
        return new UserEntity(user.id().value(), user.username(), user.displayName(), user.email(),
                user.passwordHash(), user.enabled(), user.mustChangePassword(), user.memberships());
    }

    public static User toDomain(UserEntity entity) {
        return new User(new UserId(entity.id()), entity.username(), entity.displayName(), entity.email(),
                entity.passwordHash(), entity.enabled(), entity.mustChangePassword(), entity.memberships());
    }
}
