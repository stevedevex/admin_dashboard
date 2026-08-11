import { useAtom, useSetAtom } from 'jotai';
import { useEffect } from 'react';
import { useNavigate } from 'react-router';
import { mockHandoffAtom } from '@/state/handoff';
import { draftsAtom, provenanceAtom, viewedScenarioAtom } from '../atoms';
import { mockUrl } from '../url';

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
  const navigate = useNavigate();
  const setViewedScenario = useSetAtom(viewedScenarioAtom);
  const setDrafts = useSetAtom(draftsAtom);
  const setProvenance = useSetAtom(provenanceAtom);

  useEffect(() => {
    if (!handoff) return;

    setViewedScenario(handoff.scenarioId);
    // `replace`: the caller already pushed the trip to this page (see `RequestDetailPanel`); this
    // only fills in which file that trip was for, so it belongs on the same history entry rather
    // than a new one — a `push` here would make "back" from the mock land on the mocks page with
    // nothing open instead of back where the handoff came from.
    void navigate(mockUrl(handoff.mockId), { replace: true });

    // A body means the mock does not exist yet and this is its starting point. Without one the
    // caller only wanted it selected, and seeding a draft would falsely mark it edited.
    if (handoff.body !== undefined) {
      setDrafts((drafts) => ({ ...drafts, [handoff.mockId]: handoff.body ?? '' }));
    }

    if (handoff.request !== undefined) {
      setProvenance((all) => ({ ...all, [handoff.mockId]: handoff.request ?? '' }));
    }

    setHandoff(null);
  }, [handoff, setHandoff, navigate, setViewedScenario, setDrafts, setProvenance]);
}
