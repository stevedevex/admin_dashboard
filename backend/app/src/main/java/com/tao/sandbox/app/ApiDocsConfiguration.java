package com.tao.sandbox.app;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Names the document Swagger UI shows; the endpoints themselves are scanned from the beans. */
@Configuration(proxyBeanMethods = false)
class ApiDocsConfiguration {

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
