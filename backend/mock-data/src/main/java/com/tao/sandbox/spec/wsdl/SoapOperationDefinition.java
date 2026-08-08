package com.tao.sandbox.spec.wsdl;

import com.tao.sandbox.config.SandboxProperties.KeyStrategy;
import com.tao.sandbox.runtime.match.KeySpec;
import com.tao.sandbox.spec.ServedOperation;
import java.util.List;

public record SoapOperationDefinition(
        String serviceId, String operationId, String soapAction, List<KeySpec> keys, KeyStrategy strategy)
        implements ServedOperation {}
