package com.tao.sandbox.control.view;

import com.tao.sandbox.config.SandboxProperties.StoreType;
import java.util.List;

/**
 * What the sandbox is and whether it is healthy. The dashboard's first call.
 *
 * @param root where the store keeps its mocks, for display only
 * @param scenarioHeader the per-request override header, so the dashboard can tell someone how to
 *     reach a scenario other than the active one without changing it for everybody
 * @param startupProblems normally empty — the service refuses to start when configuration is
 *     broken. Present so a future non-fatal warning has somewhere to appear rather than being
 *     invented at the point one is first needed.
 */
public record SandboxStatus(
        StoreType store,
        String root,
        String activeScenario,
        String scenarioHeader,
        int serviceCount,
        List<String> startupProblems) {}
