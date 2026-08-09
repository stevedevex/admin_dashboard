package com.tao.sandbox.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How the AI capability is configured.
 *
 * <p>Bound as records, like {@link com.tao.sandbox.config.SandboxProperties}: configuration is
 * immutable once the context is up, and a configuration that cannot work fails at startup rather
 * than on the first request that happens to need it. That matters more here than elsewhere — a
 * half-specified credential is not discovered until someone presses a button, and by then the
 * failure looks like the feature being broken rather than the deployment being incomplete.
 *
 * <p>No secret has a default, and none should be written here. See {@link Auth#clientSecret()}.
 *
 * @param endpoint the provider's base URL, and the switch for the whole capability. Empty means
 *     no provider is configured: no client is built, no generator exists, and the dashboard
 *     disables the action. The sandbox is fully usable with no AI configuration at all.
 * @param model the deployment name to call. Reported back so an author knows what answered.
 * @param apiVersion the Azure OpenAI API version. Dated rather than semantic, and pinned rather
 *     than tracking latest: the response shape is what this code parses, and a silently newer
 *     contract is exactly the kind of break nobody attributes to a version they never chose.
 * @param scope the OAuth scope the access token is requested for. Configurable because it is not
 *     the same string in every cloud — sovereign and government clouds publish their own audience,
 *     and a token issued for the wrong one is rejected with an error that names none of this.
 * @param temperature low by default. Payload generation wants plausible and schema-shaped rather
 *     than inventive, and creativity here arrives as fields the contract never declared.
 */
@Validated
@ConfigurationProperties(prefix = "tao.sandbox.ai")
public record AiProperties(
        @DefaultValue("") String endpoint,
        @DefaultValue("gpt-4o-mini") @NotBlank String model,
        @DefaultValue("2024-10-21") @NotBlank String apiVersion,
        @DefaultValue("https://cognitiveservices.azure.com/.default") @NotBlank String scope,
        @DefaultValue("0.4") double temperature,
        @DefaultValue @Valid Auth auth) {

    /**
     * The bound defaults, for tests and for anywhere a generator is constructed directly.
     *
     * <p>Spring applies {@code @DefaultValue} when it binds; nothing does when the record is
     * built by hand, and a test asserting against a null model is asserting about its own setup.
     */
    public static AiProperties defaults() {
        return new AiProperties(
                "",
                "gpt-4o-mini",
                "2024-10-21",
                "https://cognitiveservices.azure.com/.default",
                0.4,
                new Auth(Auth.Mode.DEFAULT, "", "", ""));
    }

    /**
     * How the sandbox proves who it is.
     *
     * <p>Every mode ends at the same place — a bearer token for {@link AiProperties#scope()} — so
     * nothing downstream of the credential knows which was used. Choosing between them is
     * environment, not code.
     *
     * @param mode which credential to build. See {@link Mode}.
     * @param tenantId the directory the service principal belongs to. Required for {@link
     *     Mode#SPN}, ignored otherwise.
     * @param clientId the application id for {@link Mode#SPN}, or the managed identity's client id
     *     for {@link Mode#UAMI}. For a system-assigned identity leave it blank.
     * @param clientSecret the service principal's secret, for {@link Mode#SPN} only.
     *     <p><strong>Never write this in a committed properties file.</strong> Supply it from the
     *     environment — {@code TAO_SANDBOX_AI_AUTH_CLIENTSECRET} binds to it — or from a secret
     *     store. A secret committed to a repository is a secret disclosed, and rotating it is the
     *     only remedy. This is also why {@link Mode#UAMI} is the right answer once deployed: there
     *     is no secret to hold, leak or rotate.
     */
    public record Auth(
            @DefaultValue("DEFAULT") Mode mode,
            @DefaultValue("") String tenantId,
            @DefaultValue("") String clientId,
            @DefaultValue("") String clientSecret) {

        public enum Mode {
            /**
             * Whatever the environment already offers, in the Azure SDK's usual order — a developer's
             * CLI login, environment variables, a managed identity when deployed. The default
             * because it is the mode that needs no configuration to work on a laptop.
             */
            DEFAULT,

            /** A service principal with a secret. The mode for a local run that must not use a login. */
            SPN,

            /** A user-assigned managed identity. The mode for a deployment, and the one with no secret. */
            UAMI
        }
    }
}
