/**
 * Mocks as files on disk, served from memory.
 *
 * <p>Files, so a scenario is a directory, a change is a reviewable diff and reproducing a colleague's
 * run is a checkout. Memory, because the resolve path must do no file I/O — the whole library is read
 * at startup and on explicit reload.
 *
 * <p>Explicit reload rather than watching: a mounted network share gives no change notification, and
 * behaviour that works on a laptop but not in the deployed instance is worse than none.
 */
package com.tao.sandbox.store.fs;
