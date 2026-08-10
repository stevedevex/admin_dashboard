/**
 * Every operation the sandbox serves, resolved once at startup.
 *
 * <p>Specs are parsed here and nowhere else, so a misconfigured service fails at boot with the full
 * list of problems rather than on whichever request happens to hit it first. Request handling reads
 * the registry and never a document.
 *
 * <p>Not re-read on reload. Routes are registered from these at startup, so a changed spec means a
 * changed route table — a restart, not a reload, and pretending otherwise would leave the registry
 * and the router disagreeing.
 */
package com.tao.sandbox.spec;
