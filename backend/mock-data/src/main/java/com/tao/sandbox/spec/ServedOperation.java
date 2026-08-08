package com.tao.sandbox.spec;

import com.tao.sandbox.config.SandboxProperties.KeyStrategy;
import com.tao.sandbox.runtime.match.KeySpec;
import java.util.List;

/**
 * What the resolution pipeline needs to know about an operation, regardless of protocol.
 *
 * <p>REST and SOAP definitions carry different routing information — a path and method versus an
 * envelope element — but resolution needs neither. Narrowing to this interface is what keeps one
 * pipeline serving both.
 */
public interface ServedOperation {

    String serviceId();

    String operationId();

    List<KeySpec> keys();

    KeyStrategy strategy();
}
