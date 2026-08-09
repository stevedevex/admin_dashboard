package com.tao.sandbox;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Boots this module's tests the way a real application would: nothing here but
 * auto-configuration, so the beans under test arrive through {@link MockDataAutoConfiguration} —
 * the exact wiring the app module relies on. A test app that component-scanned instead would keep
 * passing with a broken auto-configuration, which is the one thing these tests must not miss.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class MockDataTestApplication {}
