/**
 * The entire adoption surface: drop in a spec, name the operations you care about, run it.
 *
 * <p>Bound as records, so configuration is immutable once the context is up and an invalid one fails
 * at startup rather than on the first request that happens to hit it. {@code KeyStrategy} lives here
 * rather than beside either of its users on purpose — live extraction and filename computation both
 * ask it what "enough keys" means, and a rule with two homes is a rule with two answers.
 *
 * <p>Depends on nothing else in the module. Everything depends on it.
 */
package com.tao.sandbox.config;
