/**
 * Reconciling an OpenAPI document with the operations named in configuration.
 *
 * <p>Handles 3.0 and 3.1; the parser abstracting their JSON Schema differences is the main reason to
 * use it rather than reading the YAML directly. Status, media type and response schema are read from
 * the contract, so a {@code POST} answers 201 without anyone configuring it.
 *
 * <p>Problems are appended to a list rather than thrown, so startup reports every fault at once.
 */
package com.tao.sandbox.spec.openapi;
