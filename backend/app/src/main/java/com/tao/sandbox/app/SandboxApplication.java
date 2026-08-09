package com.tao.sandbox.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The one runnable thing in the backend.
 *
 * <p>Deliberately empty of features: every capability the sandbox serves — mock data today,
 * whatever comes next — is a library dependency that wires itself in through its own
 * auto-configuration. This module contributes the runtime configuration and the packaging, and
 * adding a feature means adding a dependency to this module's pom, nothing more.
 *
 * <p>Lives in its own package rather than {@code com.tao.sandbox} so component scanning stays
 * confined to the runner: feature beans arrive only through their auto-configurations, the same
 * way in development as when a module is used from anywhere else.
 */
@SpringBootApplication
public class SandboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(SandboxApplication.class, args);
    }
}
