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
 * @param uncheckedCount how many that exclusion covers, which is what makes the other two counts
 *     readable. Verdicts are remembered in memory and populated only by validation actually
 *     happening, so after a restart every mock is unchecked and both buckets are zero — and a zero
 *     that means "nobody has looked" must not be reported the same way as one that means "we
 *     looked and found none". Sending the denominator is the server's job for the same reason
 *     sending the counts is: the dashboard cannot derive it without listing the whole library.
 * @param unreachableCount mocks whose name no request produces. Counted apart from the validation
 *     buckets because it is a different kind of wrong: those describe a payload, this describes an
 *     address, and a mock can be flawless on one and hopeless on the other.
 */
public record SummaryView(
        int serviceCount,
        int servicesWithoutSchema,
        int scenarioCount,
        String activeScenarioId,
        int mockCount,
        int invalidCount,
        int incompleteCount,
        int uncheckedCount,
        int unreachableCount,
        long largestMockBytes) {}
