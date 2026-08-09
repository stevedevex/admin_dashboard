package com.tao.sandbox.ai.llm.azure;

import com.azure.core.credential.TokenCredential;
import com.azure.core.credential.TokenRequestContext;
import com.openai.credential.BearerTokenCredential;
import com.openai.credential.Credential;
import com.tao.sandbox.ai.AiProperties;
import com.tao.sandbox.ai.llm.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proving who we are to Azure OpenAI, and saying whether we still can.
 *
 * <p>All that survives of a hand-written client. The chat protocol is Spring AI's and the OpenAI
 * SDK's; what Azure genuinely adds is authentication, and that is what this holds — a
 * {@code TokenCredential} chosen by configuration, handed to the SDK as a {@link Credential} that
 * fetches a fresh token per call rather than a fixed key captured at startup.
 *
 * <p>The supplier matters. Entra ID tokens expire within the hour, so a token read once when the
 * context started would work in every test and fail in the afternoon. {@code DefaultAzureCredential}
 * and its siblings cache and refresh internally, so asking each time is cheap and always current.
 */
public class AzureModelAccess implements ModelProvider {

    private static final Logger log = LoggerFactory.getLogger(AzureModelAccess.class);

    private final TokenCredential credential;
    private final AiProperties properties;

    public AzureModelAccess(TokenCredential credential, AiProperties properties) {
        this.credential = credential;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "azure";
    }

    @Override
    public boolean available() {
        try {
            return token() != null;
        } catch (RuntimeException e) {
            log.warn("Azure OpenAI credential is not usable: {}", e.getMessage());
            return false;
        }
    }

    /** The SDK's view of the same thing: asked for a token whenever it needs one. */
    public Credential asCredential() {
        return BearerTokenCredential.create(this::token);
    }

    private String token() {
        return credential
                .getToken(new TokenRequestContext().addScopes(properties.scope()))
                .block()
                .getToken();
    }
}
