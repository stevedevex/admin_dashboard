import { describe, expect, it } from 'vitest';
import { isEnvelope, isSendable } from './protocol';

describe('isEnvelope', () => {
  it('reads a pasted envelope as SOAP however it is indented', () => {
    expect(isEnvelope('<soapenv:Envelope/>')).toBe(true);
    expect(isEnvelope('\n  <soapenv:Envelope/>')).toBe(true);
  });

  it('reads a JSON body as REST', () => {
    expect(isEnvelope('{ "name": "rex" }')).toBe(false);
  });

  /**
   * An empty body is a REST request being described, not an envelope half-typed. Guessing SOAP here
   * would replace the method and path fields the moment somebody cleared the editor.
   */
  it('reads nothing at all as REST', () => {
    expect(isEnvelope('')).toBe(false);
    expect(isEnvelope('   ')).toBe(false);
  });
});

describe('isSendable', () => {
  it('needs only the envelope, which carries its own endpoint', () => {
    expect(isSendable('', '<soapenv:Envelope/>')).toBe(true);
  });

  it('needs a path when there is no envelope', () => {
    expect(isSendable('', '')).toBe(false);
    expect(isSendable('   ', '')).toBe(false);
    expect(isSendable('/petstore/v1/pets/1', '')).toBe(true);
  });

  /** A REST call may carry a JSON body, and that body is never what makes it sendable. */
  it('still needs a path when a JSON body is present', () => {
    expect(isSendable('', '{ "name": "rex" }')).toBe(false);
    expect(isSendable('/petstore/v1/pets', '{ "name": "rex" }')).toBe(true);
  });
});
