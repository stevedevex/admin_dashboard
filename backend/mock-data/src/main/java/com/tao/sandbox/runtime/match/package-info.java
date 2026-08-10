/**
 * How a request is read, and how a value becomes part of an address.
 *
 * <p>{@link com.tao.sandbox.runtime.match.RequestFacade} is where REST and SOAP converge: each
 * protocol says how to read its own requests, and everything downstream is shared. {@link
 * com.tao.sandbox.runtime.match.Normaliser} decides whether a saved mock is ever reachable, which is
 * why it is one implementation and not one per caller.
 *
 * <p>The bottom of the runtime. Knows nothing about contracts, storage or HTTP responses.
 */
package com.tao.sandbox.runtime.match;
