import { useAtom } from 'jotai';
import { useEffect, useRef } from 'react';
import { playgroundHandoffAtom, type PlaygroundHandoff } from '@/state/handoff';

/**
 * Delivers a request another page asked the playground to take over.
 *
 * Consumed and cleared on arrival, like the mock handoff it mirrors: it is a message, not a store.
 * Leaving it set would reload the same request every time somebody navigated back, discarding
 * whatever they had since typed.
 *
 * The handoff is handed to the caller whole rather than applied here, and that is the point. A
 * request that is meant to be sent immediately has to be sent from these values, not from the state
 * they were just written into — that state does not exist yet in the commit that queued the send,
 * and reading it would send whatever the page held before.
 */
export function usePlaygroundHandoff(onArrive: (handoff: PlaygroundHandoff) => void) {
  const [handoff, setHandoff] = useAtom(playgroundHandoffAtom);

  // Kept in a ref so a caller passing an inline closure — which every caller does — cannot make
  // the delivery effect run again and redeliver a handoff that has already been consumed. Written
  // in its own effect rather than during render, which React forbids and which would leave the
  // ref holding a closure from a render that was thrown away.
  const arrive = useRef(onArrive);
  useEffect(() => {
    arrive.current = onArrive;
  });

  useEffect(() => {
    if (!handoff) return;
    setHandoff(null);
    arrive.current(handoff);
  }, [handoff, setHandoff]);
}
