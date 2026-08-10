import * as RadixTooltip from '@radix-ui/react-tooltip';
import type { ReactElement, ReactNode } from 'react';
import styles from './Tooltip.module.css';

export type TooltipProps = {
  /** What the trigger is. Kept short — a tooltip is a label, not documentation. */
  label: ReactNode;
  side?: 'top' | 'right' | 'bottom' | 'left';
  /**
   * Milliseconds before it appears. Short by default: these name controls whose meaning is not
   * otherwise on screen, and a delay long enough to be noticed is a delay long enough to give up in.
   */
  delay?: number;
  /** The control being labelled. Must accept a ref and spread props — Radix asChild. */
  children: ReactElement;
};

/**
 * A label for a control that has no room for one.
 *
 * Wraps Radix rather than leaning on the browser's native `title`. The native one is roughly a
 * second late, cannot be styled, never appears for a keyboard user, and is announced by some
 * screen readers as well as the accessible name, so the label is read twice. For a rail collapsed
 * to bare icons that is the difference between "the label is technically present" and "somebody
 * can tell what the icon does".
 *
 * The trigger keeps whatever accessible name it already has. This adds a visible label, not an
 * `aria-label` — Radix marks the content as a description, so assistive technology is told once by
 * the control and once more here, rather than twice by the same string.
 */
export function Tooltip({ label, side = 'right', delay = 150, children }: TooltipProps) {
  return (
    <RadixTooltip.Root delayDuration={delay}>
      <RadixTooltip.Trigger asChild>{children}</RadixTooltip.Trigger>
      <RadixTooltip.Portal>
        <RadixTooltip.Content className={styles.content} side={side} sideOffset={8}>
          {label}
          <RadixTooltip.Arrow className={styles.arrow} />
        </RadixTooltip.Content>
      </RadixTooltip.Portal>
    </RadixTooltip.Root>
  );
}

/**
 * Wraps the part of the app that uses tooltips.
 *
 * Radix needs one provider so that moving between neighbouring triggers does not re-serve the
 * opening delay each time — which is exactly what running a rail of them feels like without it.
 */
export function TooltipProvider({ children }: { children: ReactNode }) {
  return (
    <RadixTooltip.Provider delayDuration={150} skipDelayDuration={300}>
      {children}
    </RadixTooltip.Provider>
  );
}
