package com.tao.sandbox.control.view;

/**
 * A request composed from the contract, ready to send or to edit.
 *
 * @param method null for SOAP, where the contract fixes both the verb and the endpoint and neither
 *     is the caller's to choose
 * @param note what the draft could not fill in, and what that means for the answer. A request that
 *     resolves to an operation's default rather than to the file somebody had in mind looks like a
 *     miss otherwise, and this is the difference between a puzzle and an instruction.
 */
public record PlaygroundDraft(
        String method, String path, String body, String contentType, String note) {}
