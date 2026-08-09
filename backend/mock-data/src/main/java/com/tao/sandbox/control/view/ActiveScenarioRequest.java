package com.tao.sandbox.control.view;

/** Which scenario the sandbox should serve to callers that do not ask for one. */
public record ActiveScenarioRequest(String scenarioId) {}
