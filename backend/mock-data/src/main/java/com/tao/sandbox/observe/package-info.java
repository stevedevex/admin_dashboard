/**
 * The in-memory log of what the application under test actually called.
 *
 * <p>Bounded and dropped on restart, deliberately: persisting it would make the sandbox a system with
 * state worth backing up, which is the opposite of what it is for. Nothing else depends on it —
 * serving re-derives everything from each incoming request.
 *
 * <p>Misses are what the log is usually opened for. A miss entry names the exact file that would have
 * answered, which turns "my mock is not matching" into "create this file".
 */
package com.tao.sandbox.observe;
