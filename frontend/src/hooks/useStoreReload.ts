import { useSetAtom } from 'jotai';
import { useState } from 'react';
import { api } from '@/api';
import { storeNonceAtom } from '@/state/store';

/**
 * Re-read the library from disk, then show what changed.
 *
 * Two steps, and both are needed. The server answers from an in-memory index, so asking it again
 * without re-reading returns the same answers however many times it is clicked — which is exactly
 * how a button labelled "Reload" comes to be the one thing that does not pick up a file edited on
 * disk. The refetch afterwards is what puts the new content on screen.
 *
 * Shared by every page with such a button, so they cannot drift into meaning different things.
 */
export function useStoreReload() {
  const bump = useSetAtom(storeNonceAtom);
  const [reloading, setReloading] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  const reload = async () => {
    setReloading(true);
    setError(null);
    try {
      await api.reloadStore();
      bump((n) => n + 1);
    } catch (cause) {
      setError(cause instanceof Error ? cause : new Error(String(cause)));
    } finally {
      setReloading(false);
    }
  };

  return { reload, reloading, error };
}
