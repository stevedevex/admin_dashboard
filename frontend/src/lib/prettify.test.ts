import { describe, expect, it } from 'vitest';
import { languageOf, prettify, prettifyXml } from './prettify';

describe('prettifyXml', () => {
  it('lays a single-line envelope out by nesting', () => {
    const source =
      '<soapenv:Envelope xmlns:soapenv="http://x"><soapenv:Body><calc:Divide xmlns:calc="http://y"><calc:intA>7</calc:intA><calc:intB>3</calc:intB></calc:Divide></soapenv:Body></soapenv:Envelope>';

    expect(prettifyXml(source)).toBe(
      [
        '<soapenv:Envelope xmlns:soapenv="http://x">',
        '  <soapenv:Body>',
        '    <calc:Divide xmlns:calc="http://y">',
        '      <calc:intA>7</calc:intA>',
        '      <calc:intB>3</calc:intB>',
        '    </calc:Divide>',
        '  </soapenv:Body>',
        '</soapenv:Envelope>',
      ].join('\n'),
    );
  });

  it('keeps an element with text on one line', () => {
    expect(prettifyXml('<a><b>text</b></a>')).toBe('<a>\n  <b>text</b>\n</a>');
  });

  it('does not indent past a self-closing element', () => {
    expect(prettifyXml('<a><b/><c>1</c></a>')).toBe('<a>\n  <b/>\n  <c>1</c>\n</a>');
  });

  it('leaves the declaration at the top level', () => {
    expect(prettifyXml('<?xml version="1.0"?><a><b>1</b></a>')).toBe(
      '<?xml version="1.0"?>\n<a>\n  <b>1</b>\n</a>',
    );
  });

  /** Declining beats corrupting: the split would cut a CDATA block containing `><` in half. */
  it('leaves a payload carrying CDATA untouched', () => {
    const source = '<a><b><![CDATA[x><y]]></b></a>';
    expect(prettifyXml(source)).toBe(source);
  });

  it('leaves an already formatted payload as its author wrote it', () => {
    const source = '<a>\n    <b>1</b>\n</a>';
    expect(prettifyXml(source)).toBe(source);
  });
});

describe('prettify', () => {
  it('formats JSON by shape, without being told a media type', () => {
    expect(prettify('{"id":1,"tag":"dog"}')).toBe('{\n  "id": 1,\n  "tag": "dog"\n}');
  });

  it('returns malformed input untouched rather than guessing', () => {
    expect(prettify('{ "id": ')).toBe('{ "id": ');
    expect(prettify('not a payload')).toBe('not a payload');
  });
});

describe('languageOf', () => {
  it('reads the shape, since a captured body carries no content type', () => {
    expect(languageOf('  <a/>')).toBe('xml');
    expect(languageOf('{"a":1}')).toBe('json');
  });
});
