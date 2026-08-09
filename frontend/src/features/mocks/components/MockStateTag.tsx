import type { MockState } from '@/api';
import { Icon, Tag, type IconName, type TagTone } from '@/ui';

/**
 * Mock state as a tag.
 *
 * `unchecked` is a first-class state, not a failure — and it means exactly what it says: nothing
 * has validated this file yet. The server populates verdicts lazily, by validation actually
 * happening, rather than sweeping a whole library at startup. Drawing that as anything reassuring
 * would make "nobody looked" indistinguishable from "we looked and it was fine", which is the one
 * confusion this whole mechanism exists to prevent.
 *
 * Every state carries hover text, because in the tree this renders icon-only. A glyph with no
 * label and no tooltip is a thing readers have to guess at, and the guess for the most common
 * state here was "a button that deletes the file".
 */
const PRESENTATION: Record<MockState, { tone: TagTone; icon: IconName; label: string; hint: string }> =
  {
    valid: {
      tone: 'ok',
      icon: 'ok',
      label: 'valid',
      hint: 'Valid — checked against the contract’s schema and it passed.',
    },
    incomplete: {
      tone: 'warn',
      icon: 'warn',
      label: 'incomplete',
      hint: 'Incomplete — the payload is valid but leaves schema-declared fields unpopulated.',
    },
    invalid: {
      tone: 'error',
      icon: 'error',
      label: 'invalid',
      hint: 'Invalid — checked against the schema and rejected. It will still be served.',
    },
    unchecked: {
      tone: 'neutral',
      icon: 'unknown',
      label: 'unchecked',
      hint: 'Unchecked — nothing has validated this file yet. Not a problem, just unknown.',
    },
  };

export function MockStateTag({ state, compact = false }: { state: MockState; compact?: boolean }) {
  const { tone, icon, label, hint } = PRESENTATION[state];
  return (
    <Tag tone={tone} icon={<Icon name={icon} size={12} />} title={hint}>
      {compact ? '' : label}
    </Tag>
  );
}
