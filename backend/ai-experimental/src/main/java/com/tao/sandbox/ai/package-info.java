/**
 * Experimental AI capabilities for the sandbox.
 *
 * <p><strong>Nothing here is wired into the mock service yet.</strong> The module exists so
 * experiments have somewhere to live that cannot destabilise request serving, and so the
 * dependency on Spring AI stays off the mock service's classpath until it earns a place there.
 *
 * <h2>First intended use: generating mock payloads</h2>
 *
 * <p>Authoring a fully populated response by hand is the most tedious part of using the sandbox,
 * and the schemas needed to do it automatically are already parsed and in memory. The flow:
 *
 * <ol>
 *   <li>the user picks a service and operation in the dashboard
 *   <li>the sandbox supplies that operation's schema — an OpenAPI response schema or an XSD
 *   <li>the user adds a prompt: <em>"a corporate customer with three active accounts"</em>
 *   <li>a model returns a payload, which is then <em>validated against the same schema</em> before
 *       anyone is offered the chance to save it
 * </ol>
 *
 * <p>That last step matters more than the generation: a model will produce plausible-looking XML
 * that does not satisfy the schema, and a mock that is wrong in a subtle way is worse than no mock
 * at all. Validation is not a nicety here, it is what makes the feature safe.
 *
 * <h2>Why the module is separate</h2>
 *
 * <ul>
 *   <li><strong>Different failure modes.</strong> Mock serving must be deterministic and offline;
 *       a model call is neither. Keeping them apart means an unreachable model cannot stop the
 *       sandbox answering requests.
 *   <li><strong>Different dependencies.</strong> No model provider is declared yet, and none
 *       should reach the mock service unless deliberately added.
 *   <li><strong>Room to be wrong.</strong> "Experimental" means things here may be deleted; that
 *       is only safe if nothing depends on them.
 * </ul>
 *
 * <h2>How it is wired</h2>
 *
 * <p>The question this note used to leave open — whether to depend on mock-data or to have a third
 * module hold both — is settled by what the split produced: {@code app} is that third module, and
 * it is a bare runner that composes features and contributes no behaviour of its own. Putting a
 * controller there would make it a feature module in all but name. So this module depends on
 * mock-data directly, which is ordinary layering: {@code SpecRegistry} supplies the schema a
 * payload must satisfy and {@code MockValidator} decides whether it does, and both are stable
 * public API of the library rather than internals reached around.
 *
 * <p>There is no auto-configuration file here. The mock-data module already component-scans
 * {@code com.tao.sandbox}, which is where this package lives, so adding the dependency to
 * {@code app} is the whole integration — and a second scan over the same namespace would find
 * these classes again and register the controller twice, which fails at startup.
 *
 * <h2>No stand-in generator</h2>
 *
 * <p>There is deliberately no offline implementation. One existed while the seam was being built
 * and was removed: a generator producing schema-shaped placeholder data looks exactly like the
 * feature working, and which one produced a given payload is precisely what a reader cannot
 * establish by looking at it. A capability that is off should be visibly off. So with no {@code
 * tao.sandbox.ai.endpoint} configured there is no {@code LlmClient} and no {@code
 * PayloadGenerator}; the control panel reports the capability unavailable with the reason, and the
 * dashboard disables the action rather than offering one that fails.
 *
 * <h2>Authentication</h2>
 *
 * <p>{@code azure-identity} is a dependency; an Azure OpenAI SDK is not. The wire format is
 * OpenAI's and is spoken over the HTTP client the application already has — what Azure genuinely
 * adds is how a caller proves who it is. Every mode ends at a bearer token for the configured
 * scope, so a service principal on a laptop and a user-assigned managed identity in Azure are the
 * same code path with different environment. See {@code AzureCredentials}.
 *
 * <p><strong>Written without connectivity.</strong> {@code AzureOpenAiLlmClient}'s request mapping
 * and response parsing are covered by tests against a stubbed server; no call has been made to a
 * live endpoint. The first real call is the first real test of it.
 */
package com.tao.sandbox.ai;
