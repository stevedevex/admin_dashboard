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
 */
const PRESENTATION: Record<MockState, { tone: TagTone; icon: IconName; label: string }> = {
  valid: { tone: 'ok', icon: 'ok', label: 'valid' },
  incomplete: { tone: 'warn', icon: 'warn', label: 'incomplete' },
  invalid: { tone: 'error', icon: 'error', label: 'invalid' },
  unchecked: { tone: 'neutral', icon: 'unknown', label: 'unchecked' },
};

export function MockStateTag({ state, compact = false }: { state: MockState; compact?: boolean }) {
  const { tone, icon, label } = PRESENTATION[state];
  return (
    <Tag tone={tone} icon={<Icon name={icon} size={12} />}>
      {compact ? '' : label}
    </Tag>
  );
}
