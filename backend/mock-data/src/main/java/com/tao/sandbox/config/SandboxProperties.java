package com.tao.sandbox.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The entire adoption surface: drop in a spec, name the operations you care about, run it.
 *
 * <p>Bound as records so the configuration is immutable once the context is up, and so an invalid
 * configuration fails at startup rather than on the first request that happens to hit it.
 */
@Validated
@ConfigurationProperties(prefix = "tao.sandbox")
public record SandboxProperties(
        @DefaultValue("filesystem") StoreType store,
        @Valid Filesystem filesystem,
        @Valid Scenario scenario,
        @DefaultValue @Valid RequestLog requestLog,
        @DefaultValue @Valid Verdicts verdicts,
        @Valid @NotEmpty List<ServiceConfig> services) {

    /**
     * Single-valued until a second store exists.
     *
     * <p>A document store is a plausible next one — {@link com.tao.sandbox.store.MockRepository} is
     * written to allow it — but naming it here before it is implemented is worse than not offering
     * it: the filesystem store is conditional on this property, so selecting the absent value
     * leaves the context with no repository bean and fails startup with a missing-bean error that
     * names nothing a reader can act on. Left off the list, the same configuration fails at
     * binding instead, and Spring's message states the values that do exist.
     */
    public enum StoreType {
        FILESYSTEM
    }

    public record Filesystem(@DefaultValue("./mock-data") String root) {}

    /**
     * When the library is checked against its schemas.
     *
     * @param onStartup check everything once the application is serving. On by default, because a
     *     mark that only appears where somebody clicked describes the clicking rather than the
     *     library — and on a shared store the files nobody here has clicked are exactly the ones
     *     worth knowing about. Turn it off for a library large enough that the work is unwelcome,
     *     or for a test that wants to observe the unchecked state; {@code POST /__tao/reload}
     *     checks everything on demand either way.
     */
    public record Verdicts(@DefaultValue("true") boolean onStartup) {}

    public record Scenario(
            @DefaultValue("baseline") @NotBlank String active,
            /**
             * Optional per-request override header. Present so a shared instance can serve two
             * scenarios concurrently; absent means every caller gets {@link #active}.
             */
            @DefaultValue("X-Sandbox-Scenario") String header) {}

    /**
     * The in-memory log of what the application under test called.
     *
     * @param capacity how many entries to retain. Bounded because an unbounded log of a service
     *     under load is a memory leak with a friendly name.
     * @param maxBodyChars bodies longer than this are truncated rather than dropped, so a large
     *     payload still shows what it was without retaining megabytes per entry. Characters, not
     *     bytes — truncation is a substring, and naming it in bytes would overstate the cap.
     */
    public record RequestLog(
            @DefaultValue("500") int capacity, @DefaultValue("32768") int maxBodyChars) {}

    public enum ServiceType {
        REST,
        SOAP
    }

    public record ServiceConfig(
            @NotBlank String id,
            /** Human-readable label for the dashboard. Defaults to {@link #id}. */
            String name,
            ServiceType type,
            /** OpenAPI document for REST services. */
            String spec,
            /** WSDL for SOAP services. */
            String wsdl,
            /** Mount point. REST prefixes the spec's paths; SOAP is the single endpoint. */
            @DefaultValue("") String basePath,
            String path,
            /** Prefix bindings for {@code xpath:} keys. Required because default namespaces
             *  carry no prefix and XPath 1.0 cannot address them. */
            @DefaultValue Map<String, String> namespaces,
            /**
             * SOAP envelope header applied to every response from this service, unless a mock
             * supplies its own. Most headers are constant per service, and repeating them in
             * every mock is how they drift apart.
             */
            String responseHeader,
            @Valid @NotEmpty List<OperationConfig> operations) {

        /** A name is always present, so nothing downstream has to decide what to show. */
        public ServiceConfig {
            name = (name == null || name.isBlank()) ? id : name;
        }
    }

    /**
     * An operation the sandbox will serve. Anything absent from this list returns
     * NOT_IMPLEMENTED — a spec with forty operations does not become forty half-working endpoints.
     */
    public record OperationConfig(
            /** REST: the spec's operationId. */
            String operationId,
            /** SOAP: the operation's local name in the body. */
            String operation,
            @NotEmpty List<String> keys,
            @DefaultValue("ALL") KeyStrategy strategy) {

        /** Identifier used in configuration, storage paths and traces. */
        public String name() {
            return operationId != null ? operationId : operation;
        }
    }

    public enum KeyStrategy {
        /** Every declared key must be present, and all take part in the lookup. */
        ALL,
        /** Take the first key present, in declaration order. */
        FIRST_PRESENT;

        /**
         * Whether the keys found identify a specific mock, or the request falls through to the
         * operation's default.
         *
         * <p>Asked both when a live request is read and when the control panel computes a filename
         * for one. The rule has to be the same in both, or a file is written under a name that no
         * request can produce — a mock that exists, lists, and never answers.
         */
        public boolean satisfiedBy(int found, int declared) {
            return switch (this) {
                case ALL -> found == declared;
                case FIRST_PRESENT -> found > 0;
            };
        }

        /** Declaration order is the tie-break, so nothing past the first present key is read. */
        public boolean takesFirstOnly() {
            return this == FIRST_PRESENT;
        }
    }
}
