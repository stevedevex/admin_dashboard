package com.tao.sandbox.spec.wsdl;

import static org.assertj.core.api.Assertions.assertThat;

import com.tao.sandbox.config.SandboxProperties.KeyStrategy;
import com.tao.sandbox.config.SandboxProperties.OperationConfig;
import com.tao.sandbox.config.SandboxProperties.ServiceConfig;
import com.tao.sandbox.config.SandboxProperties.ServiceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.namespace.QName;
import org.junit.jupiter.api.Test;

/**
 * The shapes a WSDL arrives in. The single-document contracts are covered end to end by the
 * control-panel tests against the sample services; what is exercised here is the split contract —
 * interface WSDL and service WSDL joined by {@code <wsdl:import>} — which no sample service uses.
 */
class WsdlSpecLoaderTest {

    private static final String NS = "http://example.org/split";

    /**
     * A contract-first stack routinely publishes the binding and address in one document and
     * imports the portType, messages and schema from another. Every part of the loaded service
     * must come out whole: reading only the top-level document would find a binding pointing at
     * operations that do not exist.
     */
    @Test
    void aContractSplitAcrossWsdlDocumentsYieldsItsOperationsSchemaAndAddress() {
        List<String> problems = new ArrayList<>();
        SoapServiceDefinition definition = new WsdlSpecLoader().load(splitService(), problems);

        assertThat(problems).isEmpty();
        assertThat(definition).isNotNull();

        // The operation, identified on the wire by the input element declared in the imported doc.
        assertThat(definition.served()).containsKey("Echo");
        assertThat(definition.elementToOperation()).containsEntry(new QName(NS, "EchoRequest"), "Echo");

        // The soapAction, declared by the binding in the top-level doc.
        assertThat(definition.served().get("Echo").soapAction()).isEqualTo("http://example.org/split/Echo");

        // The real address, declared in the top-level doc — replaced when the WSDL is served.
        assertThat(definition.originalAddress()).isEqualTo("http://real-host.example/split");

        // The schema and the response element, both from the imported doc, so validation works.
        assertThat(definition.schemas().responseElement("Echo")).contains(new QName(NS, "EchoResponse"));
        assertThat(definition.schemas().compiled()).isPresent();

        // The imported doc is retained for serving, so a client following the import stays here.
        assertThat(definition.imports()).containsKey("split-interface.wsdl");
    }

    private ServiceConfig splitService() {
        return new ServiceConfig(
                "split",
                null,
                ServiceType.SOAP,
                null,
                "classpath:specs/split/split-service.wsdl",
                "",
                "/soap/split",
                Map.of("sp", NS),
                null,
                List.of(
                        new OperationConfig(
                                null,
                                "Echo",
                                List.of("xpath:/soapenv:Envelope/soapenv:Body/sp:EchoRequest/sp:text"),
                                KeyStrategy.ALL)));
    }
}
