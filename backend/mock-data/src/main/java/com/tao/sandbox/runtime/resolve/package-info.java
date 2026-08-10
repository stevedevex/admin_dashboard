/**
 * Where a contract, the match rules and the store meet.
 *
 * <p>Three things live here, and each exists because the question it answers was being answered in
 * more than one place:
 *
 * <ul>
 *   <li>{@link com.tao.sandbox.runtime.resolve.MockPipeline} — extract, look up, trace. One
 *       implementation, so REST, SOAP and the dry run cannot resolve differently.
 *   <li>{@link com.tao.sandbox.runtime.resolve.OperationLocator} — which operation a request is for.
 *   <li>{@link com.tao.sandbox.runtime.resolve.MockNaming} — what a mock is called. A mock is
 *       reachable only if the name it was saved under is the name resolution computes from a request.
 * </ul>
 *
 * <p>How much of a request a stored name has to account for is the operation's {@code strategy}:
 * every declared key, the first one present, or — under {@code BEST_MATCH} — any subset of them,
 * with the most specific stored name winning. The ranking itself belongs to the store, which is
 * the only thing that knows which names exist; see {@code FilesystemMockRepository}.
 *
 * <p>{@link com.tao.sandbox.runtime.resolve.ResolutionTrace} is built once and used three ways: the
 * request log, the dry run, and the diagnostic returned on a miss. That reuse is why resolution is
 * one service rather than something spread across the protocol handlers.
 */
package com.tao.sandbox.runtime.resolve;
