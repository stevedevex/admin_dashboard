package com.tao.sandbox.control.view;

import java.util.Map;

/**
 * The file name a set of key values resolves to.
 *
 * @param normalised what each value became on the way — lowercased, trimmed, leading zeros
 *     stripped. Returned so the author sees that {@code 00005678} and {@code IBM} are stored as
 *     {@code 5678} and {@code ibm}, rather than discovering it later when a mock they are sure
 *     they saved is not the one being served.
 */
public record MockName(String fileName, Map<String, String> normalised) {}
