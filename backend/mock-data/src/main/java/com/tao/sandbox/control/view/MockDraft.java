package com.tao.sandbox.control.view;

import java.util.SequencedMap;

/**
 * The mock a recorded call is asking for.
 *
 * <p>A proposal, not a creation: nothing is written until an author fills the payload in and
 * saves it the ordinary way. Creating the file here would put an empty response into the library
 * — served, well-formed and meaningless, which is the upstream behaviour the sandbox exists to
 * eliminate.
 *
 * @param mockId where it would be saved, ready for {@code PUT /__tao/mocks/{id}}
 * @param keys what identified the call, normalised — the same values that produced the file name,
 *     shown so an author can see why it is called what it is called
 * @param exists true when something is already stored at that address, so the dashboard can warn
 *     rather than let an author discover it from a 428 on save
 * @param skeleton an empty payload shaped like the declared response, or null when the contract
 *     declares nothing to build one from
 * @param requestBody the call that motivated this, to be stored beside the mock as provenance
 * @param note why the proposed name is what it is, when that is not obvious — a call whose keys
 *     did not satisfy the operation's strategy resolves to the operation's default, and a file
 *     named from the partial keys would sit there unreachable
 */
public record MockDraft(
        String mockId,
        String serviceId,
        String operationId,
        String scenarioId,
        String fileName,
        SequencedMap<String, String> keys,
        boolean exists,
        String skeleton,
        String requestBody,
        String note) {}
