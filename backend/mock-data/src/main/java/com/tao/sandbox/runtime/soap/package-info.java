/**
 * Client-facing SOAP endpoints: one POST per service, 1.1 and 1.2 at the same address.
 *
 * <p>No CXF and no Spring-WS. A JAX-WS client is satisfied by any server returning a well-formed
 * envelope, and the frameworks buy nothing a mock needs. The version is taken from the request and
 * echoed back, so one configuration serves both kinds of client.
 *
 * <p>{@code ?wsdl} serves the contract with its address rewritten to point here. Without that, a
 * client resolving its endpoint from the WSDL reads the real service's address and calls production —
 * which presents as the sandbox being ignored, and is genuinely hard to diagnose.
 */
package com.tao.sandbox.runtime.soap;
