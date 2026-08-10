/**
 * Client-facing REST endpoints, generated from the spec at startup.
 *
 * <p>Routes are registered per operation so Spring performs URI-template matching and unconfigured
 * paths never reach the pipeline. Operations are grouped by path first, so a path that is served can
 * answer 405 for a verb that is not — a real service distinguishes "wrong verb" from "no such
 * resource", and a client's error handling often does too.
 *
 * <p>Nothing here is annotated, so the served paths are not greppable. The startup log is the route
 * table.
 */
package com.tao.sandbox.runtime.rest;
