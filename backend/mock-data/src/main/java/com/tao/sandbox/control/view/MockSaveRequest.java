package com.tao.sandbox.control.view;

import com.tao.sandbox.store.MockMeta;

/**
 * A payload to store, with whatever the sidecars should say about it.
 *
 * @param meta null or empty removes the sidecar rather than leaving the previous one in place. A
 *     status an author cleared but that keeps being applied is indistinguishable, on screen, from
 *     one they never cleared.
 */
public record MockSaveRequest(String body, String envelopeHeader, MockMeta meta) {}
