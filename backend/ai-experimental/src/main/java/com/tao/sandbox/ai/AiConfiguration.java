package com.tao.sandbox.ai;

import com.azure.core.credential.TokenCredential;
import com.tao.sandbox.ai.llm.LlmClient;
import com.tao.sandbox.ai.llm.azure.AzureCredentials;
import com.tao.sandbox.ai.llm.azure.AzureOpenAiLlmClient;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.validate.MockValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * What this module contributes to the application.
 *
 * <p>No auto-configuration file of its own, and no component scan of its own: the mock-data module
 * already component-scans {@code com.tao.sandbox}, which is where this package lives, so being on
 * the classpath is the whole wiring. A second scan over the same namespace would find these
 * classes again and register the controller twice, which fails at startup.
 *
 * <p><strong>Nothing generates unless a provider is configured.</strong> There is no stand-in
 * implementation: an offline generator that produced schema-shaped placeholder data would look
 * exactly like the feature working, and the one thing a reader cannot check by eye is which
 * produced what. So an unconfigured sandbox has no {@link LlmClient} and no {@link
 * PayloadGenerator} at all, the control panel reports the capability as unavailable, and the
 * dashboard disables the action and says why.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {

    /**
     * Present only when an endpoint is configured, which is what "AI is available" means here.
     *
     * <p>Keyed on the property rather than on another bean: {@code @ConditionalOnBean} depends on
     * the order configurations are processed, and a capability silently absent because two
     * configurations were evaluated the wrong way round is a very expensive kind of quiet.
     */
    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    @ConditionalOnProperty(prefix = "tao.sandbox.ai", name = "endpoint")
    LlmClient azureOpenAiLlmClient(AiProperties properties) {
        TokenCredential credential = AzureCredentials.of(properties.auth());

        // Built here rather than injected. An auto-configured RestClient.Builder comes from a Boot
        // module this application does not have, and depending on it meant the context failed to
        // start the moment AI was configured — a failure the unconfigured path never showed. One
        // outbound integration does not justify pulling that module in; if shared customizations
        // are ever wanted, injecting the builder is a one-line change once it is on the classpath.
        return new AzureOpenAiLlmClient(RestClient.builder().build(), credential, properties);
    }

    /**
     * The generator, when there is something for it to call.
     *
     * <p>Conditional on the same property as the client rather than on the client bean, so the two
     * appear and disappear together for one stated reason. It takes a client that is always
     * present, which keeps the absence of a provider a question answered once, at the HTTP edge,
     * instead of a null check threaded through generation.
     */
    @Bean
    @ConditionalOnProperty(prefix = "tao.sandbox.ai", name = "endpoint")
    PayloadGenerator payloadGenerator(
            SpecRegistry registry, MockValidator validator, LlmClient client, AiProperties properties) {
        return new PayloadGenerator(registry, validator, client, properties);
    }
}
