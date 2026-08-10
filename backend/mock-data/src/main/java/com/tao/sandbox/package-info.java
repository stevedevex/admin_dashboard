/**
 * A schema-driven mock service for REST and SOAP contracts.
 *
 * <p>Drop in an OpenAPI document or a WSDL, name the operations to serve and what identifies a
 * request, add one mock file. This is a library, not an application: {@link
 * com.tao.sandbox.MockDataAutoConfiguration} wires it into whatever Boot app depends on it.
 *
 * <h2>One pipeline, whatever the protocol</h2>
 *
 * <pre>
 *   an HTTP request arrives
 *        │
 *        ├─ /__tao/**  ───────────────────────────────────►  control      the dashboard's API
 *        │
 *        └─ a path or endpoint some contract declares
 *                │
 *                ▼
 *        runtime.rest │ runtime.soap        which operation is this?
 *                │                          REST: the router matched a registered route
 *                │                          SOAP: the body element  (resolve.OperationLocator)
 *                ▼
 *        runtime.resolve.MockPipeline       extract declared keys   (match.KeyExtractor)
 *                │                          normalise them          (match.Normaliser)
 *                ▼
 *        store.MockProvider                 walk the scenario chain, most specific name first
 *                │
 *                ├─ hit   ──►  the stored payload, with the status the contract declares
 *                └─ miss  ──►  a diagnostic naming the keys read and every path tried
 *                │
 *                └──────────────────────────────────────────►  observe.RequestLog   hit or miss
 * </pre>
 *
 * <p>REST and SOAP differ only in the first two steps — how a request is identified and read.
 * Everything below {@link com.tao.sandbox.runtime.match.RequestFacade} is shared, which is why
 * resolution, tracing and the request log exist once rather than twice.
 *
 * <h2>Where a request actually goes</h2>
 *
 * <p><strong>Searching the source for a served path will not find it.</strong> Client-facing
 * routes are not annotated; they are registered at startup from the parsed contracts, by {@link
 * com.tao.sandbox.runtime.rest.RestRoutes} and {@link com.tao.sandbox.runtime.soap.SoapRoutes}.
 * The route table lives in configuration and the spec, not in the code.
 *
 * <p>So the authoritative answer to "what does this instance serve" is its <em>startup log</em>,
 * which prints one line per route and per SOAP endpoint, or {@code GET /__tao/services}, which
 * reports the same thing to the dashboard. Both are derived from {@link
 * com.tao.sandbox.spec.SpecRegistry}, so neither can drift from what is actually routed.
 *
 * <h2>The layers</h2>
 *
 * <p>Dependencies run upward, and adding an edge that does not is the change worth resisting.
 * {@code PackageLayeringTest} checks this from the source, so the diagram below cannot quietly stop
 * being true.
 *
 * <p>One exception, and only one: {@code spec} points down at its two loaders while they point back
 * up at the types they build. Breaking that means moving those types into a package beneath both.
 * It is recorded in the test rather than glossed over here.
 *
 * <pre>
 *   control, control.view       the /__tao API; depends on everything, depended on by nothing
 *   validate                    payload against contract  ──┐
 *   runtime.rest, runtime.soap  the client-facing endpoints ─┤
 *   observe                     the request log             ─┤
 *   runtime.resolve             the pipeline, naming, locating
 *   spec, spec.openapi, .wsdl   contracts parsed once, at startup
 *   runtime.match               how a request is read and a value normalised
 *   store, store.fs   config   xml       foundations; nothing internal below them
 * </pre>
 *
 * <h2>Two rules the code keeps deliberately</h2>
 *
 * <ul>
 *   <li><strong>Only declared keys are read.</strong> Extraction is an allowlist, so a client
 *       adding a correlation id or a timestamp cannot change which mock answers.
 *   <li><strong>A miss is loud.</strong> Nothing here ever answers an empty body, because that is
 *       exactly the upstream behaviour the sandbox exists to eliminate — and it would do it
 *       invisibly.
 * </ul>
 */
package com.tao.sandbox;
