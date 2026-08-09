package com.tao.sandbox.control.view;

/**
 * A scenario to create.
 *
 * @param parent the scenario to inherit from, or null for a root scenario.
 *     <p>Named {@code parent} rather than {@code extends}, which is what {@code scenario.yaml}
 *     calls it on disk and what an earlier draft of the control-panel contract used. Two reasons:
 *     {@code extends} is a Java keyword and cannot be a record component, so binding it would need
 *     an annotation from whichever of the two Jackson versions on this classpath happens to win;
 *     and {@code GET /__tao/scenarios} already returns this field as {@code parent}. One name per
 *     concept across the API is worth more than matching the file format, which has its own reader.
 */
public record ScenarioRequest(String id, String name, String description, String parent) {}
