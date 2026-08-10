import { json } from '@codemirror/lang-json';
import { xml } from '@codemirror/lang-xml';
import { HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { linter, lintGutter } from '@codemirror/lint';
import { EditorView } from '@codemirror/view';
import { tags } from '@lezer/highlight';
import CodeMirror from '@uiw/react-codemirror';
import { useMemo, useSyncExternalStore } from 'react';
import styles from './CodeEditor.module.css';

export type CodeLanguage = 'xml' | 'json' | 'text';

/**
 * A problem to mark in the gutter and underline in the text.
 *
 * Deliberately not the API's `ValidationIssue`: the editor is a leaf and must
 * not know about the data layer. Callers map onto this shape.
 */
export type CodeMarker = {
  /** 1-based. `null` marks the whole document. */
  line: number | null;
  message: string;
  severity?: 'error' | 'warning' | 'info';
};

export type CodeEditorProps = {
  value: string;
  language: CodeLanguage;
  readOnly?: boolean;
  placeholder?: string;
  markers?: CodeMarker[];
  onChange?: (value: string) => void;
};

/**
 * Editor theme, expressed entirely in `--tao-*` tokens so it follows the
 * design system rather than shipping a palette of its own.
 *
 * Built per appearance rather than once, for the `dark` flag alone: every
 * colour here is a token and needs no variant, but CodeMirror's own base
 * styles — tooltips, the search panel, the lint popup — key off that flag, and
 * they are the surfaces this theme says nothing about.
 */
function editorTheme(dark: boolean) {
  return EditorView.theme(
    {
      // Height is supplied by the flex chain in CodeEditor.module.css, not here.
      // Setting it to 100% competes with that and collapses the scroller.
      '&': {
        fontSize: 'var(--tao-text-xs)',
        backgroundColor: 'var(--tao-surface)',
        color: 'var(--tao-text)',
      },
      '.cm-scroller': { overflow: 'auto' },
      '.cm-content': { fontFamily: 'var(--tao-font-mono)', padding: 'var(--tao-space-2) 0' },
      '.cm-gutters': {
        backgroundColor: 'var(--tao-surface-sunken)',
        color: 'var(--tao-text-muted)',
        border: 'none',
        borderRight: '1px solid var(--tao-border)',
      },
      '.cm-activeLine': { backgroundColor: 'var(--tao-surface-sunken)' },
      '.cm-activeLineGutter': { backgroundColor: 'var(--tao-surface-sunken)' },
      '.cm-foldPlaceholder': {
        backgroundColor: 'var(--tao-chip)',
        border: '1px solid var(--tao-border)',
        color: 'var(--tao-text-muted)',
      },
      '.cm-selectionBackground, &.cm-focused .cm-selectionBackground': {
        backgroundColor: 'var(--tao-info-subtle)',
      },
      '.cm-cursor': { borderLeftColor: 'var(--tao-accent)' },
      '&.cm-focused': { outline: 'none' },
    },
    { dark },
  );
}

const LIGHT = editorTheme(false);
const DARK = editorTheme(true);

/**
 * How source is coloured, in tokens, so it follows the palette like everything else.
 *
 * Replaces the library's default, which is a fixed light-mode palette — dark red strings and dark
 * green numbers, chosen against white. They stayed exactly that on the dark surface, which is a
 * payload technically rendered and practically unreadable.
 *
 * Four meanings only: what a thing is called, a literal string, a number, and a keyword. A finer
 * scheme would need colours this design system does not have, and the payloads here are JSON and
 * XML — neither has the grammar to spend more on.
 */
const highlighting = syntaxHighlighting(
  HighlightStyle.define([
    { tag: [tags.propertyName, tags.tagName, tags.attributeName], color: 'var(--tao-code-name)' },
    { tag: [tags.string, tags.attributeValue], color: 'var(--tao-code-string)' },
    { tag: [tags.number], color: 'var(--tao-code-number)' },
    { tag: [tags.bool, tags.null, tags.keyword], color: 'var(--tao-code-keyword)' },
    { tag: [tags.comment], color: 'var(--tao-code-quiet)', fontStyle: 'italic' },
    { tag: [tags.punctuation, tags.angleBracket, tags.bracket], color: 'var(--tao-code-quiet)' },
  ]),
);

/**
 * Whether the design system is currently in its dark palette.
 *
 * Read from the document rather than from application state: `tokens.css`
 * declares `data-theme` on the root element, so it is the design system's own
 * signal, and a primitive reading it stays independent of how the application
 * happens to store the preference.
 */
function subscribeToAppearance(onChange: () => void) {
  const observer = new MutationObserver(onChange);
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] });
  return () => observer.disconnect();
}

function useDarkAppearance() {
  return useSyncExternalStore(
    subscribeToAppearance,
    () => document.documentElement.dataset.theme === 'dark',
    () => false,
  );
}

function languageExtension(language: CodeLanguage) {
  if (language === 'xml') return [xml()];
  if (language === 'json') return [json()];
  return [];
}

/**
 * Markers become CodeMirror diagnostics — gutter icons, underlines and a
 * hover message, all for free.
 *
 * Line numbers are resolved against the live document inside the source
 * function, so a marker pointing past the end of an edited document clamps to
 * the last line instead of throwing.
 */
function markerExtension(markers: CodeMarker[]) {
  // The gutter matters more than the underline here: in a document of tens of
  // thousands of lines the offending line is usually off-screen, and the
  // gutter marker is what makes it findable.
  return [
    lintGutter(),
    linter(
      (view) =>
        markers.map((marker) => {
          const lineCount = view.state.doc.lines;
          const target = Math.min(Math.max(marker.line ?? 1, 1), lineCount);
          const line = view.state.doc.line(target);

          return {
            from: line.from,
            to: line.to,
            severity: marker.severity ?? 'error',
            message: marker.message,
          };
        }),
      { delay: 0 },
    ),
  ];
}

/**
 * Source editor for mock payloads.
 *
 * CodeMirror renders only the visible viewport, which is why this is not a
 * `<textarea>`: payloads here reach megabytes, and folding plus a gutter are
 * the only practical way to navigate them.
 *
 * Lines are not wrapped — wrapping a very long XML line is expensive and makes
 * structure harder to follow. The pane scrolls horizontally instead.
 */
export function CodeEditor({
  value,
  language,
  readOnly = false,
  placeholder,
  markers,
  onChange,
}: CodeEditorProps) {
  const dark = useDarkAppearance();

  const extensions = useMemo(
    () => [
      ...languageExtension(language),
      highlighting,
      ...(markers?.length ? [markerExtension(markers)] : []),
    ],
    [language, markers],
  );

  return (
    <div className={styles.wrap}>
      <CodeMirror
        value={value}
        readOnly={readOnly}
        // Passed as the theme rather than appended to `extensions`, which is what it was before.
        // Unset, the library adds a light theme of its own that paints `.cm-editor` white, and
        // that one won: in the dark palette every part of this theme applied except the one thing
        // underneath all of it, leaving a white page behind dark gutters and dark text.
        theme={dark ? DARK : LIGHT}
        extensions={extensions}
        // Spread rather than pass `undefined`: `exactOptionalPropertyTypes`
        // distinguishes "absent" from "explicitly undefined", and the
        // underlying props are not declared to accept the latter.
        {...(placeholder === undefined ? {} : { placeholder })}
        {...(onChange === undefined ? {} : { onChange })}
        basicSetup={{
          lineNumbers: true,
          foldGutter: true,
          highlightActiveLine: !readOnly,
          highlightActiveLineGutter: !readOnly,
          autocompletion: false,
          searchKeymap: true,
        }}
      />
    </div>
  );
}
