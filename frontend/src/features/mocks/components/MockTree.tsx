import { useState } from 'react';
import { formatBytes } from '@/lib/format';
import { Icon } from '@/ui';
import type { OperationNode, TreeNode } from '../hooks/useMockTree';
import { MockStateTag } from './MockStateTag';
import styles from './MockTree.module.css';

export type MockTreeProps = {
  nodes: TreeNode[];
  selectedId: string | null;
  onSelect: (id: string) => void;
};

export function MockTree({ nodes, selectedId, onSelect }: MockTreeProps) {
  return (
    <ul className={styles.tree}>
      {nodes.map((node) => (
        <ServiceGroup key={node.service.id} node={node} selectedId={selectedId} onSelect={onSelect} />
      ))}
    </ul>
  );
}

function ServiceGroup({ node, selectedId, onSelect }: { node: TreeNode } & Omit<MockTreeProps, 'nodes'>) {
  const [open, setOpen] = useState(true);

  return (
    <li>
      <button type="button" className={styles.service} onClick={() => setOpen(!open)} aria-expanded={open}>
        <Icon name={open ? 'expanded' : 'collapsed'} size={14} />
        <span className={styles.serviceName}>{node.service.name}</span>
        <span className={styles.count}>{node.mockCount}</span>
      </button>

      {open && (
        <ul className={styles.operations}>
          {node.operations.map((operation) => (
            <OperationGroup
              key={operation.id}
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
 * Operations are a level of their own because they are one in storage: files live at
 * `scenario/service/operation/file`, and two operations of the same service each having a
 * `_default` is the normal case, not an edge one.
 */
function OperationGroup({
  operation,
  selectedId,
  onSelect,
}: { operation: OperationNode } & Omit<MockTreeProps, 'nodes'>) {
  const [open, setOpen] = useState(true);

  return (
    <li>
      <button
        type="button"
        className={styles.operation}
        onClick={() => setOpen(!open)}
        aria-expanded={open}
      >
        <Icon name={open ? 'expanded' : 'collapsed'} size={12} />
        <span className={styles.operationName}>{operation.id}</span>
        <span className={styles.count}>{operation.mocks.length}</span>
      </button>

      {open && (
        <ul className={styles.files}>
          {operation.mocks.map((mock) => (
            <li key={mock.id}>
              <button
                type="button"
                className={mock.id === selectedId ? styles.fileActive : styles.file}
                onClick={() => onSelect(mock.id)}
                title={
                  mock.inherited ? `${mock.fileName} — inherited from ${mock.scenarioId}` : mock.fileName
                }
              >
                <span className={styles.marker} />
                {/* Inheritance is shown as a glyph, not a text tag: the file
                    name is what people scan for, and a tag squeezes it out. */}
                <span className={mock.inherited ? styles.inherited : styles.own}>
                  {mock.inherited ? <Icon name="scenarios" size={12} /> : null}
                </span>
                <span className={mock.inherited ? styles.fileNameInherited : styles.fileName}>
                  {mock.fileName}
                </span>
                <span className={styles.size}>{formatBytes(mock.sizeBytes)}</span>
                <MockStateTag state={mock.state} compact />
              </button>
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}
