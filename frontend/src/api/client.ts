import type { Transport } from './transport';
import { httpTransport } from './http/transport';

/**
 * Transport selection.
 *
 * The dashboard describes a running sandbox, so it reads one — there is no fixture mode. A
 * dashboard that can render data the server never produced is a dashboard that agrees with itself
 * and nothing else, and every disagreement it hides is a bug found later, by someone else.
 */
export const client: Transport = httpTransport;
