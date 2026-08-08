package com.tao.sandbox.store;

/**
 * A named set of mocks.
 *
 * @param parent scenario this one inherits from, or null. Single-parent by design: a degraded
 *     scenario stores only its deltas, and a diamond would make "which file wins" unanswerable.
 */
public record Scenario(String id, String name, String description, String parent) {}
