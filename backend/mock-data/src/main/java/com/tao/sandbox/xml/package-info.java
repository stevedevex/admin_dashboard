/**
 * DOM and XPath, wrapped so the JDK's ceremony appears once.
 *
 * <p>Secure-by-default parsing lives here — external entities off, DTDs off — which is why nothing
 * else in the module constructs a {@code DocumentBuilderFactory} of its own.
 *
 * <p>Depends on nothing. Used by the WSDL loader, the SOAP runtime and validation.
 */
package com.tao.sandbox.xml;
