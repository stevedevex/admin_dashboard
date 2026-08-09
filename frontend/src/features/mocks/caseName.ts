import type { KeyField } from '@/api';

/**
 * A mock file name, read as the record it actually is.
 *
 * These are not names. `inta=10&intb=0.xml` is the operation's identifying keys serialised, and
 * every character of it was computed by the server from a request. Shown as a string it is
 * something to squint at, and it gets worse with every key an operation declares; shown as fields
 * it is a row of data whose columns are the contract's own key list.
 *
 * Presentation only, and deliberately one-way. The server owns naming — what normalises to what,
 * how a value is padded, which characters survive — and `MockName` exists precisely so this side
 * never guesses at it. Nothing here builds a name or resolves one. A name that does not decompose
 * cleanly against the declared keys is reported as raw text and rendered verbatim, which is what
 * the page did for every name until now and is never wrong, only plainer.
 */

/** One key of a file name, under the contract's spelling rather than the file's. */
export type CaseField = { name: string; value: string };

export type CaseName =
  /** The file every unmatched request falls back to. */
  | { kind: 'default' }
  | { kind: 'fields'; fields: CaseField[] }
  /** Anything the declared keys do not account for — shown as written, never guessed at. */
  | { kind: 'raw'; text: string };

const DEFAULT_STEM = '_default';

export function describeCase(fileName: string, keys: readonly KeyField[]): CaseName {
  const stem = fileName.replace(/\.[^.]+$/, '');
  if (stem === DEFAULT_STEM) return { kind: 'default' };

  const fields: CaseField[] = [];

  for (const part of stem.split('&')) {
    const at = part.indexOf('=');
    if (at <= 0) return { kind: 'raw', text: stem };

    // Matched case-insensitively, then reported under the *contract's* spelling. The file says
    // `inta` because that is how the resolver normalises; the schema and the reader both say
    // `intA`, and the reader is who this is for.
    const written = part.slice(0, at);
    const declared = keys.find((key) => key.name.toLowerCase() === written.toLowerCase());
    if (!declared) return { kind: 'raw', text: stem };

    fields.push({ name: declared.name, value: part.slice(at + 1) });
  }

  return fields.length > 0 ? { kind: 'fields', fields } : { kind: 'raw', text: stem };
}

/** One line of plain text for the same thing, for hover text, filtering and assistive tech. */
export function caseLabel(name: CaseName): string {
  switch (name.kind) {
    case 'default':
      return 'default';
    case 'fields':
      return name.fields.map((field) => `${field.name}=${field.value}`).join(' ');
    case 'raw':
      return name.text;
  }
}

/**
 * Every term must appear somewhere, in any order.
 *
 * Matched against both the written name and the decomposed one, so `intb=0` finds a file whose
 * name spells it that way *and* one the contract spells `intB`, and two bare terms — `10 0` —
 * find the same file without anybody typing punctuation.
 */
export function matchesCase(query: string, fileName: string, name: CaseName): boolean {
  const terms = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (terms.length === 0) return true;

  const haystack = `${fileName} ${caseLabel(name)}`.toLowerCase();
  return terms.every((term) => haystack.includes(term));
}
