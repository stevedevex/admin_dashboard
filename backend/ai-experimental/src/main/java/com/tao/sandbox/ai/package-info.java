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
 * <h2>Open question, deferred until this module does something</h2>
 *
 * <p>Generation needs the parsed schemas that live in the mock-data module, and the dashboard
 * needs an endpoint to call. That implies either this module depending on mock-data, or a third
 * module holding the runnable application and depending on both. The second is the conventional
 * answer and the one to take when it stops being hypothetical.
 */
package com.tao.sandbox.ai;
