/**
 * Which protocol a request body is, decided once.
 *
 * Three callers, which is why it lives here rather than in the playground that asks most often. The
 * playground reads it twice — to lay the form out, and again to build the call — and the request
 * log reads it to decide whether a recorded call can be replayed from its own bytes or has to be
 * rebuilt. All three must agree: a form showing a method and a path while the request is sent as an
 * envelope, or the reverse, is a bug nobody can see on screen, because both halves look right and
 * only the answer is wrong.
 *
 * Read from the text rather than asked for, because the shape is unambiguous. Anything starting
 * with `<` is an envelope, and an envelope carries its own operation and endpoint, so a method and
 * path are neither needed nor meaningful beside one.
 */
export function isEnvelope(body: string): boolean {
  return body.trimStart().startsWith('<');
}

/**
 * Whether there is enough to send.
 *
 * An envelope needs only itself. A REST call needs a path — the method always has a value, since it
 * is chosen from a list, so it can never be the missing part.
 */
export function isSendable(path: string, body: string): boolean {
  return isEnvelope(body) ? body.trim() !== '' : path.trim() !== '';
}
