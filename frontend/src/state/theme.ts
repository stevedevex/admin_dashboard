import { atom } from 'jotai';

export type Theme = 'light' | 'dark';

const STORED = 'tao.theme';

/**
 * The theme, remembered across visits and applied to the document.
 *
 * Written as an attribute on the root element rather than a class, because that is what
 * `tokens.css` already keys its dark values on — the whole palette existed and nothing ever set
 * the attribute that switches it on.
 *
 * The first value follows the operating system, so somebody who has told their machine they want
 * dark is not asked a second time. Once they choose here, their choice stands: an explicit answer
 * outranks an inferred one, and a preference that silently reverts on the next visit is worse
 * than not offering it.
 */
function preferred(): Theme {
  if (typeof window === 'undefined') return 'light';

  const stored = window.localStorage.getItem(STORED);
  if (stored === 'light' || stored === 'dark') return stored;

  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

const base = atom<Theme>(preferred());

/**
 * Reading gives the theme; writing sets it, records it and applies it.
 *
 * The document write lives here rather than in an effect so the attribute and the atom cannot
 * disagree — a component that re-renders without the effect running would otherwise leave the
 * page in one theme while the toggle claims the other.
 */
export const themeAtom = atom(
  (get) => get(base),
  (_get, set, next: Theme) => {
    set(base, next);

    if (typeof document !== 'undefined') {
      document.documentElement.dataset.theme = next;
    }
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(STORED, next);
    }
  },
);

/** Applied before React renders, so the first paint is already the right colour. */
export function applyStoredTheme(): void {
  if (typeof document !== 'undefined') {
    document.documentElement.dataset.theme = preferred();
  }
}
