import { useId, type ReactNode } from 'react';
import type { Capability } from '@/config/navigation';
import { Tag } from '@/ui';
import styles from './CapabilitySection.module.css';

export type CapabilitySectionProps = {
  capability: Capability;
  /** The capability's own panels. Omitted for one that does not exist yet. */
  children?: ReactNode;
};

/**
 * One capability of the product, and everything the dashboard has to say about it.
 *
 * The page is a list of these rather than a list of panels. A panel on its own says what is
 * true — how many mocks, what is arriving — but says nothing about what part of the product it
 * belongs to, and a page made only of them reads as the whole product rather than as one section
 * of it. That reading was accurate once and stops being accurate the moment a second capability
 * exists, at which point the fix is a re-layout rather than an addition.
 *
 * So the boundary is drawn now, while there is one capability to draw it around, and the ones
 * that do not exist are on the page as reserved outlines. Announcing an absence costs a few lines
 * here; discovering later that the landing page has no room for a second thing costs the layout.
 *
 * Labelled by its own heading, so the section is announced by name rather than as "section".
 */
export function CapabilitySection({ capability, children }: CapabilitySectionProps) {
  const headingId = useId();
  const planned = capability.status === 'planned';

  return (
    <section className={planned ? styles.sectionPlanned : styles.section} aria-labelledby={headingId}>
      <header className={styles.head}>
        <div className={styles.identity}>
          <h2 id={headingId} className={styles.name}>
            {capability.name}
          </h2>
          <p className={styles.summary}>{capability.summary}</p>
        </div>

        {/* What state the capability itself is in — not a verdict on anything inside it. */}
        <Tag tone={planned ? 'neutral' : 'ok'}>{planned ? 'planned' : 'serving'}</Tag>
      </header>

      {children ? <div className={styles.body}>{children}</div> : null}
    </section>
  );
}
