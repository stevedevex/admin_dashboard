/**
 * Reading a WSDL with DOM, and pulling the XSD out of it.
 *
 * <p>No WSDL library: only a handful of things are needed — the operations, the element that
 * identifies each one on the wire, the address, the target namespace and the response schema — and a
 * library would supply all of that plus a binding model this service has no use for.
 *
 * <p>Contracts split across several documents are the normal shape, not the exception, so every
 * {@code import}/{@code include} is followed transitively and every document contributes on equal
 * terms.
 */
package com.tao.sandbox.spec.wsdl;
