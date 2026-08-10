/**
 * What a stored mock is, and the two ways of reaching one.
 *
 * <p>The split is the point. {@link com.tao.sandbox.store.MockProvider} is the hot path — resolve a
 * query, list what was tried — and is all request handling is given. {@link
 * com.tao.sandbox.store.MockRepository} adds the control plane: browsing, writing, scenarios. A
 * request handler that could write mocks would eventually write one.
 *
 * <p>{@link com.tao.sandbox.store.MockQuery} is the boundary that keeps the storage choice from
 * leaking upward: a filesystem provider turns one into a path, a document store into a query
 * document, and nothing above knows which is in use.
 *
 * <p>Depends on nothing else in the module — deliberately, since it is the vocabulary the layers
 * above share.
 */
package com.tao.sandbox.store;
