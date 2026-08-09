import { describe, expect, it } from 'vitest';
import type { KeyField } from '@/api';
import { caseLabel, describeCase, matchesCase } from './caseName';

const key = (name: string): KeyField => ({ name, source: 'BODY', expression: `//${name}` });

const CALCULATOR = [key('intA'), key('intB')];

describe('describeCase', () => {
  it('reads the fallback file as the default rather than as a key', () => {
    expect(describeCase('_default.xml', CALCULATOR)).toEqual({ kind: 'default' });
  });

  it('decomposes a name into the keys it was built from', () => {
    expect(describeCase('inta=10&intb=0.xml', CALCULATOR)).toEqual({
      kind: 'fields',
      fields: [
        { name: 'intA', value: '10' },
        { name: 'intB', value: '0' },
      ],
    });
  });

  it('reports the contract spelling, not the file name spelling', () => {
    const name = describeCase('tickersymbol=acme.xml', [key('tickerSymbol')]);
    expect(caseLabel(name)).toBe('tickerSymbol=acme');
  });

  it('keeps a value that contains dots, stripping only the extension', () => {
    expect(caseLabel(describeCase('version=1.2.3.json', [key('version')]))).toBe('version=1.2.3');
  });

  it('keeps a negative value intact', () => {
    expect(caseLabel(describeCase('inta=-250000.xml', CALCULATOR))).toBe('intA=-250000');
  });

  it('falls back to raw text when a key is not declared by the contract', () => {
    expect(describeCase('colour=red.json', CALCULATOR)).toEqual({ kind: 'raw', text: 'colour=red' });
  });

  it('falls back to raw text when a segment carries no key at all', () => {
    expect(describeCase('hand-written.json', CALCULATOR)).toEqual({
      kind: 'raw',
      text: 'hand-written',
    });
  });

  it('falls back to raw text when only some segments decompose', () => {
    expect(describeCase('inta=10&nonsense.xml', CALCULATOR)).toEqual({
      kind: 'raw',
      text: 'inta=10&nonsense',
    });
  });

  it('treats a leading = as no key rather than an empty one', () => {
    expect(describeCase('=10.xml', CALCULATOR)).toEqual({ kind: 'raw', text: '=10' });
  });

  it('has nothing to decompose against when the operation declares no keys', () => {
    expect(describeCase('inta=10.xml', [])).toEqual({ kind: 'raw', text: 'inta=10' });
  });
});

describe('matchesCase', () => {
  const fileName = 'inta=10&intb=0.xml';
  const name = describeCase(fileName, CALCULATOR);

  it('matches everything on an empty query', () => {
    expect(matchesCase('   ', fileName, name)).toBe(true);
  });

  it('matches the name as the file spells it', () => {
    expect(matchesCase('intb=0', fileName, name)).toBe(true);
  });

  it('matches the name as the contract spells it', () => {
    expect(matchesCase('intB=0', fileName, name)).toBe(true);
  });

  it('matches bare values, in any order, without punctuation', () => {
    expect(matchesCase('0 10', fileName, name)).toBe(true);
  });

  it('requires every term, not any', () => {
    expect(matchesCase('intb=0 missing', fileName, name)).toBe(false);
  });

  it('finds the default file by name', () => {
    expect(matchesCase('default', '_default.xml', describeCase('_default.xml', CALCULATOR))).toBe(
      true,
    );
  });
});
