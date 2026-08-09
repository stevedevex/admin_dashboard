package com.tao.sandbox.control.view;

/**
 * Headline numbers for the dashboard, in one call.
 *
 * <p>One endpoint rather than the dashboard fanning out and counting client-side: it stays one
 * request as the library grows, and "what counts as invalid" stays a server decision.
 *
 * @param mockCount mocks owned across all scenarios — inherited visibility is not double-counted
 * @param invalidCount among mocks validation has actually assessed; an unchecked mock counts in
 *     neither bucket, because nothing is known about it
 */
public record SummaryView(
        int serviceCount,
        int servicesWithoutSchema,
        int scenarioCount,
        String activeScenarioId,
        int mockCount,
        int invalidCount,
        int incompleteCount,
        long largestMockBytes) {}
