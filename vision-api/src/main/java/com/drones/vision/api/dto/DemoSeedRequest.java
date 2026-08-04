package com.drones.vision.api.dto;

import com.drones.vision.api.demo.DemoPlan;

/**
 * Body of {@code POST /api/demo/seed} — every field optional, and the whole body may be absent
 * (the green button posts nothing at all and gets {@link DemoPlan#DEFAULT}).
 *
 * @param assets       how many simulated assets to create; absent means 10
 * @param users        how many demo users to create; absent means 10
 * @param startStreams how many of those assets to put on the air; absent means 3
 */
public record DemoSeedRequest(Integer assets, Integer users, Integer startStreams) {

    /** Resolves this partial request into a plan, filling absent fields from {@link DemoPlan#DEFAULT}. */
    public DemoPlan toPlan() {
        return new DemoPlan(assets == null ? DemoPlan.DEFAULT.assets() : assets,
                users == null ? DemoPlan.DEFAULT.users() : users,
                startStreams == null ? DemoPlan.DEFAULT.startStreams() : startStreams);
    }
}
