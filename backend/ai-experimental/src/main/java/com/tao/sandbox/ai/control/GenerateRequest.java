package com.tao.sandbox.ai.control;

/**
 * What the dashboard sends to have a payload generated.
 *
 * <p>No schema and no format: both are the sandbox's to determine from the operation. A client
 * that supplied its own schema could have a payload generated against a contract the service does
 * not serve, and the validation that follows would then be checking the wrong thing.
 *
 * @param prompt optional. Absent means "a representative, fully populated response", which is the
 *     common case — most authors want the shape filled in, not a particular story.
 * @param current what the editor already holds, when it holds anything. Sent as context rather
 *     than as an instruction: most requests made against an existing payload are adjustments —
 *     "make three of them cats" — and answering those by discarding the file and inventing a new
 *     one throws away work the author did. Whether to adapt it or start over is the model's call,
 *     since only the prompt says which was meant.
 */
public record GenerateRequest(String serviceId, String operationId, String prompt, String current) {}
