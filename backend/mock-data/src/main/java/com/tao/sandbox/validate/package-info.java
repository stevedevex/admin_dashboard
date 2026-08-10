/**
 * Whether a stored payload is what its contract says the operation returns.
 *
 * <p>Takes a body rather than a mock id: the editor validates what is on screen, not what was last
 * saved. The distinction the whole package exists to preserve is between "checked and clean" and
 * "nothing looked at this" — a mock displaying as valid when nothing checked it is the failure this
 * mechanism is here to prevent, so {@code UNCHECKED} is a state the dashboard must draw, never an
 * absence it may round down to a pass.
 *
 * <p>Verdicts are in memory and derived; everything here can be recomputed from a payload and its
 * schema.
 */
package com.tao.sandbox.validate;
