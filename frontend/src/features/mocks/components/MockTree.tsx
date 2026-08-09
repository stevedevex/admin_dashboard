import { useState } from 'react';
import { Icon } from '@/ui';
import type { OperationNode, TreeNode } from '../hooks/useMockTree';
import { worstState } from '../operationState';
import { MockStateTag } from './MockStateTag';
import styles from './MockTree.module.css';

export type MockTreeProps = {
  nodes: TreeNode[];
  /** The mock currently open. The operation containing it is what shows as selected. */
  selectedId: string | null;
  onSelect: (mockId: string) => void;
  /**
   * A filter is narrowing the tree, so every group shows open regardless of how it was left.
   * A collapsed group under a filter hides the very rows the filter was typed to find, and the
   * reader has no way to tell an empty result from a folded one.
   */
  filtering?: boolean;
};

/**
 * Services and the operations they serve.
 *
 * Files are deliberately absent. They used to be a third level here, which meant two collapse
 * toggles and two levels of indentation to group sets averaging under two items — more chrome
 * than content, and a wall of rows the moment a library grew. An operation is also the thing that
 * has a contract, so it is the thing worth selecting; which of its files you are editing is a
 * smaller choice, made where the payload is, on the tabs above it.
 */
export function MockTree({ nodes, selectedId, onSelect, filtering = false }: MockTreeProps) {
  return (
    <ul className={styles.tree}>
      {nodes.map((node) => (
        <ServiceGroup
          key={node.service.id}
          node={node}
          selectedId={selectedId}
          onSelect={onSelect}
          filtering={filtering}
        />
      ))}
    </ul>
  );
}

/** Below the root the flag is resolved, so the groups take it as a plain boolean. */
type GroupProps = {
  selectedId: string | null;
  onSelect: (mockId: string) => void;
  filtering: boolean;
};

function ServiceGroup({ node, selectedId, onSelect, filtering }: { node: TreeNode } & GroupProps) {
  const [open, setOpen] = useState(true);
  const expanded = filtering || open;

  return (
    <li>
      <button
        type="button"
        className={styles.service}
        onClick={() => setOpen(!open)}
        aria-expanded={expanded}
      >
        <Icon name={expanded ? 'expanded' : 'collapsed'} size={14} />
        <span className={styles.serviceName}>{node.service.name}</span>
        <span className={styles.count}>{node.mockCount}</span>
      </button>

      {expanded && (
        <ul className={styles.operations}>
          {node.operations.map((operation) => (
            <OperationRow
              key={operation.id}
              serviceId={node.service.id}
              operation={operation}
              selectedId={selectedId}
              onSelect={onSelect}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

/**
 * One operation, and the state of the files under it.
 *
 * Selecting it opens its first file. Every operation reaching this list has at least one — the
 * tree drops services with no mocks — so there is no case where a click leads nowhere.
 */
function OperationRow({
  serviceId,
  operation,
  selectedId,
  onSelect,
}: {
  serviceId: string;
  operation: OperationNode;
  selectedId: string | null;
  onSelect: (mockId: string) => void;
}) {
  // `scenario/service/operation/file` — the open file names its operation, so selection needs no
  // state of its own.
  const [, openService, openOperation] = (selectedId ?? '').split('/');
  const active = openService === serviceId && openOperation === operation.id;

  const first = operation.mocks[0];
  const inherited = operation.mocks.every((mock) => mock.inherited);

  return (
    <li>
      <button
        type="button"
        className={active ? styles.operationActive : styles.operation}
        onClick={() => first && onSelect(first.id)}
        title={inherited ? `${operation.id} — every file inherited` : operation.id}
      >
        <span className={styles.marker} />
        <span className={inherited ? styles.operationNameInherited : styles.operationName}>
          {operation.id}
        </span>
        {inherited && <Icon name="scenarios" size={11} />}
        <span className={styles.count}>{operation.mocks.length}</span>
        <MockStateTag state={worstState(operation.mocks)} compact />
      </button>
    </li>
  );
}
