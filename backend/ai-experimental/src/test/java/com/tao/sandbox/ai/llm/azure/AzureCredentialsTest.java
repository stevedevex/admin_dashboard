package com.tao.sandbox.ai.llm.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.ManagedIdentityCredential;
import com.tao.sandbox.ai.AiProperties.Auth;
import org.junit.jupiter.api.Test;

/**
 * Which credential each mode builds, and what it refuses to build without.
 *
 * <p>No token is requested here — that needs Azure. What is worth pinning down without it is that
 * the mode selects what it says it selects, and that a half-specified service principal fails at
 * construction naming the missing property rather than at first use as an authentication error
 * against a provider, which sends whoever is debugging it to the wrong system.
 */
class AzureCredentialsTest {

    @Test
    void defaultModeChainsWhateverTheEnvironmentOffers() {
        assertThat(AzureCredentials.of(new Auth(Auth.Mode.DEFAULT, "", "", "")))
                .isInstanceOf(DefaultAzureCredential.class);
    }

    @Test
    void spnBuildsAClientSecretCredential() {
        Auth auth = new Auth(Auth.Mode.SPN, "tenant", "client", "secret");

        assertThat(AzureCredentials.of(auth)).isInstanceOf(ClientSecretCredential.class);
    }

    @Test
    void uamiBuildsAManagedIdentityCredential() {
        Auth auth = new Auth(Auth.Mode.UAMI, "", "identity-client-id", "");

        assertThat(AzureCredentials.of(auth)).isInstanceOf(ManagedIdentityCredential.class);
    }

    @Test
    void uamiWithoutAClientIdIsTheSystemAssignedIdentity() {
        // A legitimate deployment, and the only identity available on some hosts — so blank is a
        // choice to honour rather than an omission to reject.
        assertThat(AzureCredentials.of(new Auth(Auth.Mode.UAMI, "", "", "")))
                .isInstanceOf(ManagedIdentityCredential.class);
    }

    @Test
    void spnNamesTheTenantItIsMissing() {
        Auth auth = new Auth(Auth.Mode.SPN, "", "client", "secret");

        assertThatThrownBy(() -> AzureCredentials.of(auth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tenant-id");
    }

    @Test
    void spnNamesTheClientItIsMissing() {
        Auth auth = new Auth(Auth.Mode.SPN, "tenant", "", "secret");

        assertThatThrownBy(() -> AzureCredentials.of(auth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-id");
    }

    @Test
    void spnNamesTheSecretItIsMissing() {
        Auth auth = new Auth(Auth.Mode.SPN, "tenant", "client", "");

        assertThatThrownBy(() -> AzureCredentials.of(auth))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-secret");
    }
}
