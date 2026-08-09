package com.tao.sandbox.control.view;

import com.tao.sandbox.store.MockMeta;

/**
 * A payload to store, with whatever the sidecars should say about it.
 *
 * @param meta null or empty removes the sidecar rather than leaving the previous one in place. A
 *     status an author cleared but that keeps being applied is indistinguishable, on screen, from
 *     one they never cleared.
 * @param request the call this mock was written for, sent once by whoever created it from a
 *     recorded call. Unlike the other two, null leaves any stored request alone rather than
 *     clearing it: every ordinary save carries none, and deleting on those would throw the record
 *     away the first time somebody edited the payload.
 */
public record MockSaveRequest(String body, String envelopeHeader, MockMeta meta, String request) {}
