package com.tao.sandbox;

import com.tao.sandbox.config.SandboxProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * Wires the whole module into any Spring Boot application that has it on the classpath.
 *
 * <p>The application module holds nothing but a runner: each feature module is a dependency that
 * contributes its own beans through an auto-configuration like this one, registered in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * Adding a feature to the sandbox is adding a dependency, never editing the runner.
 *
 * <p>The scan excludes this class itself: it is registered through the imports file, and letting
 * the scan register it a second time would process the same configuration twice.
 */
@AutoConfiguration
@EnableConfigurationProperties(SandboxProperties.class)
@ComponentScan(
        basePackages = "com.tao.sandbox",
        excludeFilters =
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE,
                        classes = MockDataAutoConfiguration.class))
public class MockDataAutoConfiguration {}
