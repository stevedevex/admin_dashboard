package com.tao.sandbox.ai.llm.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import com.tao.sandbox.ai.AiProperties;

/**
 * Builds the credential the configured mode asks for.
 *
 * <p>Everything downstream sees a {@link TokenCredential} and nothing else, which is what makes a
 * service principal on a laptop and a managed identity in Azure the same code path with different
 * environment. The differences are all here, and they are all configuration.
 *
 * <p>Each mode validates its own requirements and fails with a message naming the property that is
 * missing. A credential that is built but cannot work defers the failure to the first token
 * request, where it surfaces as an authentication error against a provider — which sends whoever
 * is debugging it to the wrong system entirely.
 */
public final class AzureCredentials {

    private AzureCredentials() {}

    public static TokenCredential of(AiProperties.Auth auth) {
        return switch (auth.mode()) {
            case SPN -> servicePrincipal(auth);
            case UAMI -> managedIdentity(auth);
            case DEFAULT -> new DefaultAzureCredentialBuilder().build();
        };
    }

    private static TokenCredential servicePrincipal(AiProperties.Auth auth) {
        require(auth.tenantId(), "tao.sandbox.ai.auth.tenant-id");
        require(auth.clientId(), "tao.sandbox.ai.auth.client-id");
        require(auth.clientSecret(), "tao.sandbox.ai.auth.client-secret");

        return new ClientSecretCredentialBuilder()
                .tenantId(auth.tenantId())
                .clientId(auth.clientId())
                .clientSecret(auth.clientSecret())
                .build();
    }

    /**
     * A managed identity, user-assigned when a client id is given.
     *
     * <p>A blank client id is left blank rather than rejected: that selects the system-assigned
     * identity, which is a legitimate deployment and the only one available on some hosts.
     */
    private static TokenCredential managedIdentity(AiProperties.Auth auth) {
        ManagedIdentityCredentialBuilder builder = new ManagedIdentityCredentialBuilder();

        if (!auth.clientId().isBlank()) {
            builder.clientId(auth.clientId());
        }

        return builder.build();
    }

    private static void require(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "%s is required when tao.sandbox.ai.auth.mode is SPN".formatted(property));
        }
    }
}
