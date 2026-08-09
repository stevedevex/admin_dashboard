package com.tao.sandbox.control.view;

import java.util.List;

/**
 * A scenario as the dashboard lists it.
 *
 * @param parent the {@code extends} target; null means a root scenario
 * @param mockCount how many mocks this scenario <em>owns</em>, not how many it can serve. A
 *     degraded scenario storing two overrides on top of a full baseline is a two-mock scenario —
 *     reporting fourteen would hide exactly the thing the number is read for.
 * @param serviceIds services with a mock <em>visible</em> in this scenario, inherited included —
 *     the opposite convention from {@code mockCount}, because this answers "what does the
 *     scenario cover", and coverage is precisely what inheritance provides
 */
public record ScenarioView(
        String id,
        String name,
        String description,
        String parent,
        int mockCount,
        List<String> serviceIds) {}
