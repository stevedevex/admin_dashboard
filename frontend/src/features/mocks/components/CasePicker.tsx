import { useEffect, useId, useMemo, useRef, useState } from 'react';
import type { KeyField, MockSummary } from '@/api';
import { formatBytes } from '@/lib/format';
import { Icon, TextInput } from '@/ui';
import { caseLabel, describeCase, matchesCase, type CaseName } from '../caseName';
import { MockStateTag } from './MockStateTag';
import styles from './CasePicker.module.css';

/**
 * Which file of the operation is open, and how to switch to another.
 *
 * It replaced a strip of tabs, and the reason is arithmetic: a tab strip spends a row of the page
 * on however many files exist, so it is at its widest exactly when names are longest and there are
 * most of them. This spends one line whatever the count, which is the same line the file name
 * already occupied — the chooser is free.
 *
 * The file name stays, everywhere. These are real files in a real directory, and the name is what
 * somebody types into an editor, greps for, or reads in a resolution trace; a decomposition that
 * replaced it would be a prettier view of a thing you then could not find. So the trigger shows
 * the name, and each row shows the keys it was built from *above* the name it was built into.
 *
 * The list is filtered by typing, over the decomposed name as well as the written one, because
 * with a hundred files "which one has intB=0" is the only question anybody has.
 */
export type CasePickerProps = {
  /** Every file of this operation. May not contain the open one, which may not exist yet. */
  files: MockSummary[];
  selectedId: string;
  /** The open file's name, which for a draft is all there is. */
  fileName: string;
  keys: readonly KeyField[];
  onSelect: (mockId: string) => void;
};

/**
 * Enough rows to scroll through, not enough to build a thousand nodes nobody reads. Past this the
 * filter is the tool, and the footer says so rather than silently truncating.
 */
const SHOWN = 50;

/**
 * Whether the trigger should take focus as soon as it exists.
 *
 * Choosing a file replaces the panel — it is keyed by the open file, deliberately, so nothing from
 * the last one survives — and that includes this component. So the usual "close, then hand focus
 * back" cannot work: by the time it runs, the element it would focus is being unmounted, and a
 * keyboard user is left on `<body>` having just successfully picked something.
 *
 * Module scope rather than state because it has to outlive the component that sets it, and it is
 * read once and cleared: it means "the last thing that happened was a pick", never anything more.
 */
let handBackFocus = false;

export function CasePicker({ files, selectedId, fileName, keys, onSelect }: CasePickerProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const list = useRef<HTMLUListElement>(null);
  const listId = useId();

  const current = describeCase(fileName, keys);

  const matches = useMemo(
    () =>
      files
        .map((file) => ({ file, name: describeCase(file.fileName, keys) }))
        .filter(({ file, name }) => matchesCase(query, file.fileName, name)),
    [files, keys, query],
  );

  const shown = matches.slice(0, SHOWN);

  /*
   * Clamped on the way out rather than corrected in an effect.
   *
   * Typing shortens the list under a highlight that may be past its end, and writing the
   * correction back would be a render triggering a render. Clamping where it is read keeps the
   * stored value the reader's intent — narrowing one character and deleting it again leaves the
   * highlight where they left it, instead of throwing it back to the top of the list.
   */
  const activeIndex = shown.length === 0 ? -1 : Math.min(active, shown.length - 1);

  useEffect(() => {
    if (!open) return;

    const dismiss = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false);
    };

    document.addEventListener('pointerdown', dismiss);
    return () => document.removeEventListener('pointerdown', dismiss);
  }, [open]);

  // Keeps the keyboard highlight visible in a list that scrolls. `nearest` rather than centring,
  // so arrowing one row does not repaint the whole popover.
  useEffect(() => {
    list.current?.querySelector('[data-active="true"]')?.scrollIntoView({ block: 'nearest' });
  }, [activeIndex, open]);

  useEffect(() => {
    if (!handBackFocus) return;
    handBackFocus = false;
    trigger.current?.focus();
  }, []);

  const close = () => {
    setOpen(false);
    trigger.current?.focus();
  };

  const choose = (mockId: string) => {
    // Picking the file already open changes nothing, so nothing remounts and focus can simply be
    // handed back here. Claiming it for a mount that will never happen would leave the claim
    // standing, and the next file opened would steal focus from wherever the reader had gone.
    if (mockId === selectedId) {
      setOpen(false);
      trigger.current?.focus();
      return;
    }

    // Claimed before the selection lands, because that is what unmounts this component.
    handBackFocus = true;
    onSelect(mockId);
    setOpen(false);
  };

  // One file is not a choice, and a control that opens onto a list of one is a control that lies
  // about having somewhere to go. The name still shows — it is the file's identity, not its menu.
  if (files.length < 2) {
    return (
      <span className={styles.static}>
        <span className={styles.label}>File</span>
        <span className={styles.name}>{fileName}</span>
      </span>
    );
  }

  return (
    <div
      className={styles.root}
      ref={root}
      /*
       * Closed when focus leaves, not only when a pointer lands outside.
       *
       * Only the filter input is in the tab order inside the popover, so Tab out of it means the
       * reader is done with the list — and leaving it standing meant a popover covering controls
       * that now had focus, with Escape no longer reaching it because Escape is handled on the
       * input. `relatedTarget` is where focus is going; a null one is focus leaving the document,
       * which counts as leaving.
       */
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
      }}
    >
      <button
        ref={trigger}
        type="button"
        className={styles.trigger}
        aria-haspopup="listbox"
        aria-expanded={open}
        title={`${caseLabel(current)} — ${files.length} files for this operation`}
        onClick={() => {
          setQuery('');
          setActive(Math.max(files.findIndex((file) => file.id === selectedId), 0));
          setOpen((was) => !was);
        }}
      >
        <span className={styles.label}>File</span>
        <span className={styles.name}>{fileName}</span>
        {/*
          The count the tab strip used to convey simply by existing. Without it the only sign that
          this operation has alternatives is a twelve-pixel chevron, and a reader who never opens
          the control never learns there was anything to open.
        */}
        <span className={styles.count}>{files.length} files</span>
        <Icon name={open ? 'expanded' : 'collapsed'} size={12} />
      </button>

      {open && (
        <div className={styles.popover}>
          <div className={styles.search}>
            <TextInput
              mono
              autoFocus
              value={query}
              placeholder="Filter by key or value"
              aria-label="Filter files"
              /*
               * The combobox is the input, not the trigger: it is the only focusable thing in the
               * popover, and the rows are pointed at rather than visited. `aria-activedescendant`
               * is what makes arrowing audible — without it a screen reader announces the typed
               * text and nothing about the row Enter would take.
               */
              role="combobox"
              aria-expanded
              aria-controls={listId}
              aria-autocomplete="list"
              aria-activedescendant={
                activeIndex >= 0 ? `${listId}-${shown[activeIndex]?.file.id}` : undefined
              }
              onChange={(event) => setQuery(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Escape') {
                  event.preventDefault();
                  close();
                } else if (event.key === 'ArrowDown') {
                  event.preventDefault();
                  setActive(Math.min(activeIndex + 1, shown.length - 1));
                } else if (event.key === 'ArrowUp') {
                  event.preventDefault();
                  setActive(Math.max(activeIndex - 1, 0));
                } else if (event.key === 'Enter') {
                  event.preventDefault();
                  const chosen = shown[activeIndex];
                  if (chosen) choose(chosen.file.id);
                }
              }}
            />
          </div>

          <ul
            className={styles.list}
            id={listId}
            role="listbox"
            aria-label="Files for this operation"
            ref={list}
          >
            {shown.map(({ file, name }, index) => (
              <li key={file.id}>
                <button
                  type="button"
                  role="option"
                  id={`${listId}-${file.id}`}
                  aria-selected={file.id === selectedId}
                  data-active={index === activeIndex}
                  /*
                   * Out of the tab order on purpose. A row is pointed at or arrowed to, never
                   * tabbed to: leaving them tabbable walked focus through the list and out the
                   * far side into the controls the popover was covering — the first of which is
                   * Delete.
                   */
                  tabIndex={-1}
                  className={file.id === selectedId ? styles.optionSelected : styles.option}
                  onMouseEnter={() => setActive(index)}
                  onClick={() => choose(file.id)}
                >
                  <span className={styles.identity}>
                    <CaseName name={name} />
                    <span className={styles.fileName}>{file.fileName}</span>
                  </span>
                  {file.inherited && (
                    <span title={`inherited from ${file.scenarioId}`}>
                      <Icon name="scenarios" size={11} />
                    </span>
                  )}
                  <span className={styles.size}>{formatBytes(file.sizeBytes)}</span>
                  <MockStateTag state={file.state} />
                </button>
              </li>
            ))}

            {shown.length === 0 && <li className={styles.empty}>Nothing matches</li>}
          </ul>

          {matches.length > shown.length && (
            <p className={styles.more}>
              {matches.length - shown.length} more — narrow the filter to reach them
            </p>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * The name as fields when the contract accounts for it, as written when it does not.
 *
 * The key is shown beside its value rather than dropped: two bare numbers are not a name, and an
 * operation keyed on `intA` and `intB` produces files that differ only in which one is which.
 */
function CaseName({ name }: { name: CaseName }) {
  if (name.kind === 'default') {
    return <span className={styles.default}>default</span>;
  }

  if (name.kind === 'raw') {
    return <span className={styles.raw}>{name.text}</span>;
  }

  return (
    <span className={styles.fields}>
      {name.fields.map((field) => (
        <span key={field.name} className={styles.field}>
          <span className={styles.fieldName}>{field.name}</span>
          <span className={styles.fieldValue}>{field.value}</span>
        </span>
      ))}
    </span>
  );
}
