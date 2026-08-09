package com.tao.sandbox.control.view;

import java.util.Map;

/**
 * Key values to compute a file name from.
 *
 * @param keys keyed by the operation's key names as {@code GET /__tao/services} reports them —
 *     {@code tickerSymbol}, not the {@code xpath:…} declaration it was derived from. The full
 *     declaration is accepted too, so a caller holding either form is never wrong.
 */
public record MockNameRequest(String serviceId, String operationId, Map<String, String> keys) {}
