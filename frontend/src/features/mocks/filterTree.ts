import type { TreeNode } from './hooks/useMockTree';

/**
 * Narrow the file tree to what a query matches. Pure: no React, no I/O.
 *
 * Matches against the three names a reader has in mind — the service, the operation, the file —
 * and keeps a whole branch when any of them matches. A query naming an operation is asking for
 * that operation's files, so listing the operation with nothing under it would be a worse answer
 * than none.
 *
 * Deliberately plain substring matching, case-insensitive. Fuzzy matching would rank
 * `inta=2&intb=3.xml` against `tab` and be right often enough to be trusted and wrong often
 * enough to hide a file somebody knows exists, which is the failure that stops people using a
 * filter at all.
 */
export function filterTree(nodes: TreeNode[], query: string): TreeNode[] {
  const needle = query.trim().toLowerCase();
  if (needle === '') return nodes;

  const result: TreeNode[] = [];

  for (const node of nodes) {
    const serviceMatches =
      node.service.name.toLowerCase().includes(needle) ||
      node.service.id.toLowerCase().includes(needle);

    const operations = node.operations
      .map((operation) => {
        // A match higher up keeps everything beneath it: naming a service or an operation is
        // asking for its contents, so narrowing them to the ones that happen to repeat the query
        // would answer a question nobody asked — and usually with nothing.
        const kept = serviceMatches || operation.id.toLowerCase().includes(needle);

        return {
          ...operation,
          mocks: kept
            ? operation.mocks
            : operation.mocks.filter((mock) => mock.fileName.toLowerCase().includes(needle)),
        };
      })
      .filter((operation) => operation.mocks.length > 0);

    if (operations.length > 0) {
      result.push({
        ...node,
        operations,
        // Recounted rather than carried over: the count beside a filtered service must describe
        // what is on screen, not what exists.
        mockCount: operations.reduce((total, operation) => total + operation.mocks.length, 0),
      });
    }
  }

  return result;
}

/** Files on screen, for saying how much of the library a query is hiding. */
export function countFiles(nodes: TreeNode[]): number {
  return nodes.reduce((total, node) => total + node.mockCount, 0);
}
