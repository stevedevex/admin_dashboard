/**
 * The Mocks page's selection, encoded into the address bar — and back.
 *
 * A mock id already reads as `scenario/service/operation/file`, the same shape a sequence of URL
 * path segments takes, so it becomes the route's splat directly rather than a separate query
 * param or piece of in-memory state. That is the whole fix for the vanishing Validate/Save
 * buttons: they were never conditional on anything fragile, but the page used to hold "which file
 * is open" in a plain Jotai atom with no persistence, so a refresh, a shared link, or the browser
 * back button silently reset it to nothing and the editor — buttons included — disappeared with
 * it. The URL cannot do that; it is what survives a reload.
 *
 * **Do not reintroduce a `selectedMockIdAtom`.** Every place that changes the selection must go
 * through {@link mockUrl} and `navigate`, and every place that reads it must come from
 * `useParams()['*']` (via {@link mockIdFromSplat}) — so there is exactly one source of truth, and
 * it is the one thing on this page that a reload cannot lose.
 */

/** The Mocks page URL for a given selection — or the bare page, for "nothing open". */
export function mockUrl(mockId: string | null): string {
  if (!mockId) return '/mock-data/mocks';
  return `/mock-data/mocks/${mockId.split('/').map(encodeURIComponent).join('/')}`;
}

/**
 * The inverse of {@link mockUrl}: a route splat back into a mock id, or `null` for "nothing
 * open".
 *
 * Wrapped in a `try`, not because the app ever produces a malformed splat, but because this one
 * also has to survive a URL a person typed or edited by hand — a stray `%` should land on "no
 * file selected", not a crashed page.
 */
export function mockIdFromSplat(splat: string | undefined): string | null {
  if (!splat) return null;
  try {
    return splat
      .split('/')
      .map((segment) => decodeURIComponent(segment))
      .join('/');
  } catch {
    return null;
  }
}
