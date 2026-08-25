package com.drones.vision.adapter.persistence.entity;

import com.drones.vision.flight.domain.model.ActionBinding;
import com.drones.vision.flight.domain.model.ControlBinding;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JPA row for {@code control_profiles} — an operator's saved controller layout
 * (docs/plans/active/CONTROLLER-SETUP-CONTEXT.md wave C4). {@link
 * com.drones.vision.adapter.persistence.mapper.ControlProfileMapper} owns the mapping both ways.
 *
 * <p>Unlike most entities here, {@code id} is <b>not</b> synthetic: a {@link
 * com.drones.vision.flight.domain.model.ControlProfile} carries its own identity, because the
 * browser refers to a profile by id when activating or editing it.
 *
 * <p>{@code channelMap}/{@code actionMap} are jsonb lists of the domain records themselves — a
 * profile is only ever read back whole, never queried into by individual binding, the same
 * convention {@code VehicleProfileEntity#messages} follows. The domain records validate on
 * construction, so a row that somehow held a nonsense binding fails loudly at read time rather than
 * quietly producing a profile nobody could have configured.
 */
@Entity
@Table(name = "control_profiles")
public class ControlProfileEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "vehicle_kind", nullable = false, length = 32)
    private String vehicleKind;

    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "channel_map", columnDefinition = "jsonb", nullable = false)
    private List<ControlBinding> channelMap = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action_map", columnDefinition = "jsonb", nullable = false)
    private List<ActionBinding> actionMap = new ArrayList<>();

    /** How the owner's transmitter is arranged; affects only how the layout is drawn (V25). */
    @Column(name = "stick_mode", nullable = false)
    private short stickMode;

    @Column(name = "forward_is_up", nullable = false)
    private boolean forwardIsUp;

    /** JPA only. */
    protected ControlProfileEntity() {
    }

    public ControlProfileEntity(UUID id, UUID ownerUserId, String vehicleKind, String code, String displayName,
                                 boolean active, Instant updatedAt, List<ControlBinding> channelMap,
                                 List<ActionBinding> actionMap, short stickMode, boolean forwardIsUp) {
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.vehicleKind = vehicleKind;
        this.code = code;
        this.displayName = displayName;
        this.active = active;
        this.updatedAt = updatedAt;
        this.channelMap = new ArrayList<>(channelMap);
        this.actionMap = new ArrayList<>(actionMap);
        this.stickMode = stickMode;
        this.forwardIsUp = forwardIsUp;
    }

    public UUID id() {
        return id;
    }

    public UUID ownerUserId() {
        return ownerUserId;
    }

    public String vehicleKind() {
        return vehicleKind;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public boolean active() {
        return active;
    }

    public short stickMode() {
        return stickMode;
    }

    public boolean forwardIsUp() {
        return forwardIsUp;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public List<ControlBinding> channelMap() {
        return List.copyOf(channelMap);
    }

    public List<ActionBinding> actionMap() {
        return List.copyOf(actionMap);
    }

    /** Moves the "this is the layout my next session uses" flag — the one field {@code activate} writes. */
    public void setActive(boolean active) {
        this.active = active;
    }
}
