import { useAtom, useSetAtom } from 'jotai';
import { useEffect } from 'react';
import { mockHandoffAtom } from '@/state/handoff';
import { draftsAtom, provenanceAtom, selectedMockIdAtom, viewedScenarioAtom } from '../atoms';

/**
 * Opens whatever another page asked this one to open.
 *
 * Arriving from the request log with a drafted mock means selecting it, switching the browsed
 * scenario to where it belongs, and seeding the editor with the skeleton — so the page opens on
 * the file the caller was asking for rather than on whatever was last selected.
 *
 * The handoff is cleared as it is consumed. Left in place it would re-open on every visit, quietly
 * overwriting an edit in progress with a skeleton the author had already moved past.
 */
export function useMockHandoff() {
  const [handoff, setHandoff] = useAtom(mockHandoffAtom);
  const setSelected = useSetAtom(selectedMockIdAtom);
  const setViewedScenario = useSetAtom(viewedScenarioAtom);
  const setDrafts = useSetAtom(draftsAtom);
  const setProvenance = useSetAtom(provenanceAtom);

  useEffect(() => {
    if (!handoff) return;

    setViewedScenario(handoff.scenarioId);
    setSelected(handoff.mockId);

    // A body means the mock does not exist yet and this is its starting point. Without one the
    // caller only wanted it selected, and seeding a draft would falsely mark it edited.
    if (handoff.body !== undefined) {
      setDrafts((drafts) => ({ ...drafts, [handoff.mockId]: handoff.body ?? '' }));
    }

    if (handoff.request !== undefined) {
      setProvenance((all) => ({ ...all, [handoff.mockId]: handoff.request ?? '' }));
    }

    setHandoff(null);
  }, [handoff, setHandoff, setSelected, setViewedScenario, setDrafts, setProvenance]);
}
