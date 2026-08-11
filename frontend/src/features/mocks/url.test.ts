import { describe, expect, it } from 'vitest';
import { mockIdFromSplat, mockUrl } from './url';

describe('mockUrl', () => {
  it('is the bare page for no selection', () => {
    expect(mockUrl(null)).toBe('/mock-data/mocks');
  });

  it('lays a mock id down as one path segment per part', () => {
    expect(mockUrl('baseline/petstore/listPets/_default.json')).toBe(
      '/mock-data/mocks/baseline/petstore/listPets/_default.json',
    );
  });

  it('escapes a segment that would otherwise change what the URL means', () => {
    // A `#` would truncate the path into a fragment, a `?` into a query string, and a `/` would
    // add a segment that was not part of the id — exactly the class of file name "New Mock"
    // never rejects.
    expect(mockUrl('baseline/petstore/listPets/weird#name?.json')).toBe(
      '/mock-data/mocks/baseline/petstore/listPets/weird%23name%3F.json',
    );
  });
});

describe('mockIdFromSplat', () => {
  it('is no selection for an absent or empty splat', () => {
    expect(mockIdFromSplat(undefined)).toBeNull();
    expect(mockIdFromSplat('')).toBeNull();
  });

  it('reads a splat back into the same id mockUrl encoded it from', () => {
    expect(mockIdFromSplat('baseline/petstore/listPets/_default.json')).toBe(
      'baseline/petstore/listPets/_default.json',
    );
  });

  it('un-escapes a segment mockUrl escaped, round-tripping the original id', () => {
    const id = 'baseline/petstore/listPets/weird#name?.json';
    const splat = mockUrl(id).replace('/mock-data/mocks/', '');
    expect(mockIdFromSplat(splat)).toBe(id);
  });

  it('lands on "nothing selected" for a splat a person mistyped, rather than throwing', () => {
    expect(mockIdFromSplat('baseline/petstore/listPets/%')).toBeNull();
  });
});
