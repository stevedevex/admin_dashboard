package com.tao.sandbox.ai;

import com.azure.core.credential.TokenCredential;
import com.openai.azure.AzureOpenAIServiceVersion;
import com.tao.sandbox.ai.llm.azure.AzureCredentials;
import com.tao.sandbox.ai.llm.azure.AzureModelAccess;
import com.tao.sandbox.spec.SpecRegistry;
import com.tao.sandbox.validate.MockValidator;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 * produced what. So an unconfigured sandbox has no {@link ChatModel} and no {@link
 * PayloadGenerator} at all, the control panel reports the capability as unavailable, and the
 * dashboard disables the action and says why.
 *
 * <p>That rule is also why the model is built here rather than by {@code
 * spring-ai-starter-model-openai}. The starter auto-configures a {@code ChatModel} from its own
 * properties the moment it is on the classpath, which would put a client in the context that this
 * module's whole design says should not be there.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {

    /**
     * How the sandbox proves who it is, and whether it still can.
     *
     * <p>Keyed on the property rather than on another bean: {@code @ConditionalOnBean} depends on
     * the order configurations are processed, and a capability silently absent because two
     * configurations were evaluated the wrong way round is a very expensive kind of quiet.
     */
    @Bean
    @ConditionalOnMissingBean(AzureModelAccess.class)
    @ConditionalOnProperty(prefix = "tao.sandbox.ai", name = "endpoint")
    AzureModelAccess azureModelAccess(AiProperties properties) {
        TokenCredential credential = AzureCredentials.of(properties.auth());
        return new AzureModelAccess(credential, properties);
    }

    /**
     * Azure OpenAI, reached through Spring AI's OpenAI model.
     *
     * <p>There is no Azure chat module to reach for any more — Spring AI withdrew it after
     * 2.0.0-M4 and its own reference page now redirects here, because the wire format was always
     * OpenAI's. What Azure needs beyond that is declared rather than hand-built: {@code
     * azure(true)} selects its URL shape, {@code deploymentName} names what the endpoint calls the
     * model, and the service version carries the {@code api-version} query parameter Azure requires
     * and OpenAI has no concept of.
     *
     * <p>The version is pinned by configuration rather than tracking latest: the response shape is
     * what gets parsed, and a silently newer contract is exactly the kind of break nobody
     * attributes to a version they never chose.
     */
    @Bean
    @ConditionalOnMissingBean(ChatModel.class)
    @ConditionalOnProperty(prefix = "tao.sandbox.ai", name = "endpoint")
    ChatModel payloadChatModel(OpenAiChatOptions options) {
        return OpenAiChatModel.builder().options(options).build();
    }

    /**
     * The defaults every generated payload is asked for under.
     *
     * <p>A bean rather than a local, because {@link PayloadGenerator} adds one thing per call — the
     * schema the answer must satisfy — and mutating these is how it does that without restating the
     * endpoint, the credential and the deployment on every request.
     */
    @Bean
    @ConditionalOnMissingBean(OpenAiChatOptions.class)
    @ConditionalOnProperty(prefix = "tao.sandbox.ai", name = "endpoint")
    OpenAiChatOptions payloadChatOptions(AiProperties properties, AzureModelAccess access) {
        return OpenAiChatOptions.builder()
                .azure(true)
                .baseUrl(properties.endpoint())
                .credential(access.asCredential())
                .deploymentName(properties.model())
                .azureOpenAIServiceVersion(AzureOpenAIServiceVersion.fromString(properties.apiVersion()))
                .model(properties.model())
                .temperature(properties.temperature())
                .build();
    }

    /**
     * The generator, when there is something for it to call.
     *
     * <p>Conditional on the same property as the model rather than on the model bean, so the two
     * appear and disappear together for one stated reason.
     */
    @Bean
    @ConditionalOnProperty(prefix = "tao.sandbox.ai", name = "endpoint")
    PayloadGenerator payloadGenerator(
            SpecRegistry registry,
            MockValidator validator,
            ChatModel model,
            OpenAiChatOptions options,
            AzureModelAccess access) {
        return new PayloadGenerator(registry, validator, model, options, access);
    }
}
