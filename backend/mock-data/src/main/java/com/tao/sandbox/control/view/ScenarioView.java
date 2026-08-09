package com.tao.sandbox.control.view;

/**
 * A scenario as the dashboard lists it.
 *
 * @param parent the {@code extends} target; null means a root scenario
 * @param mockCount how many mocks this scenario <em>owns</em>, not how many it can serve. A
 *     degraded scenario storing two overrides on top of a full baseline is a two-mock scenario —
 *     reporting fourteen would hide exactly the thing the number is read for.
 */
public record ScenarioView(String id, String name, String description, String parent, int mockCount) {}
