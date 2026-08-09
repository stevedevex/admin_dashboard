import type { MockSummary } from '@/api';
import { formatBytes } from '@/lib/format';
import { Icon } from '@/ui';
import { MockStateTag } from './MockStateTag';
import styles from './FileTabs.module.css';

export type FileTabsProps = {
  files: MockSummary[];
  selectedId: string;
  onSelect: (mockId: string) => void;
};

/**
 * The files of the selected operation, as tabs.
 *
 * Tabs rather than a list, because of what the data actually looks like: an operation typically
 * has two or three files — a `_default` and a case or two — and a table costs a hundred and fifty
 * pixels of the editor's height to show what a single row of tabs shows in thirty-six. The editor
 * is the work surface, and every pixel spent above it is spent against the thing being written.
 *
 * Renders nothing for a lone file: a tab strip offering one choice is decoration, and the file's
 * name is already in the meta strip above.
 */
export function FileTabs({ files, selectedId, onSelect }: FileTabsProps) {
  if (files.length < 2) return null;

  return (
    <div className={styles.tabs} role="tablist" aria-label="Files for this operation">
      {files.map((file) => {
        const active = file.id === selectedId;

        return (
          <button
            key={file.id}
            type="button"
            role="tab"
            aria-selected={active}
            className={active ? styles.tabActive : styles.tab}
            onClick={() => onSelect(file.id)}
            title={
              file.inherited
                ? `${file.fileName} — inherited from ${file.scenarioId}, ${formatBytes(file.sizeBytes)}`
                : `${file.fileName} — ${formatBytes(file.sizeBytes)}`
            }
          >
            {file.inherited && <Icon name="scenarios" size={11} />}
            <span className={styles.name}>{file.fileName}</span>
            <MockStateTag state={file.state} compact />
          </button>
        );
      })}
    </div>
  );
}
