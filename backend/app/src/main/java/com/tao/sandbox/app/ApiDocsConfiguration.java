package com.tao.sandbox.app;

import com.tao.sandbox.config.SandboxProperties.ServiceType;
import com.tao.sandbox.spec.ServiceDescriptor;
import com.tao.sandbox.spec.SpecRegistry;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springdoc.core.properties.AbstractSwaggerUiConfigProperties.SwaggerUrl;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Names the document Swagger UI shows; the endpoints themselves are scanned from the beans. */
@Configuration(proxyBeanMethods = false)
class ApiDocsConfiguration {

    /**
     * Puts every mocked REST service's own contract in Swagger UI's document picker, next to the
     * control panel — so the mock APIs a client integrates against are browsable and try-out-able
     * in the same UI. Built from the registry rather than listed in yaml, because the services
     * are configuration and a second list would drift.
     *
     * <p>SOAP services are absent by nature: their contract is a WSDL, which Swagger UI cannot
     * render. It is served at {@code /__tao/services/{id}/spec} and at the endpoint's own
     * {@code ?wsdl} instead.
     */
    @Bean
    ApplicationRunner mockApisInSwaggerUi(SwaggerUiConfigProperties ui, SpecRegistry registry) {
        return args -> {
            Set<SwaggerUrl> urls = new LinkedHashSet<>();
            urls.add(new SwaggerUrl("control-panel", "/__tao/openapi", "control panel"));

            for (ServiceDescriptor service : registry.services()) {
                if (service.type() == ServiceType.REST) {
                    urls.add(
                            new SwaggerUrl(
                                    service.id(),
                                    "/__tao/services/" + service.id() + "/spec",
                                    service.name() + " (mock)"));
                }
            }

            ui.setUrls(urls);
        };
    }

    @Bean
    OpenAPI controlPanelApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("tao sandbox — control panel")
                                .description(
                                        "The API the dashboard uses: status, service and mock catalogs, "
                                                + "scenarios, save/validate, resolve dry-runs and the request log. "
                                                + "The mock endpoints themselves are not in this document — they "
                                                + "imitate other people's contracts, served at their configured "
                                                + "base paths.")
                                .version("0.1.0"));
    }
}
