/**
 * The dashboard's API. Everything under {@code /__tao}.
 *
 * <p>A reserved prefix, because a dropped-in customer contract can declare any path it likes —
 * including {@code /services} — and a control panel sharing that namespace would mean one of the two
 * silently wins.
 *
 * <p>Failures answer {@code application/problem+json} through an advice scoped to this package only.
 * The data plane imitates someone else's contract and answers in that contract's own vocabulary — a
 * SOAP fault, a miss diagnostic — and a global advice would quietly replace those with a shape the
 * real upstream never returns.
 *
 * <p>The top of the module: depends on nearly everything, and nothing depends on it. Domain decisions
 * belong below this line — a controller that computes an answer rather than asking for one is a
 * second implementation waiting to drift.
 */
package com.tao.sandbox.control;
