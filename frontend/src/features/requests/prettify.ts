/**
 * Readable formatting for a captured payload.
 *
 * A client sends its request on one line — every SOAP stack does — and one line is unreadable at
 * exactly the moment someone is trying to see which field they sent. Formatting happens on the way
 * to the screen and never on the way to disk: what was captured is what arrived.
 *
 * Every failure returns the input untouched. A payload nobody can format is still a payload
 * somebody needs to read, and a formatter that mangles what it does not understand is worse than
 * one that declines.
 */

const INDENT = '  ';

export function prettifyJson(source: string): string {
  try {
    return JSON.stringify(JSON.parse(source), null, 2);
  } catch {
    return source;
  }
}

export function prettifyXml(source: string): string {
  const text = source.trim();

  // CDATA and comments can carry `><` inside them, which the split below would treat as a tag
  // boundary and break apart. Rare in a request payload, and declining beats corrupting.
  if (text.includes('<![CDATA[') || text.includes('<!--')) {
    return source;
  }

  // Already laid out across lines — a payload someone formatted, or one a client sent that way.
  // Reformatting it would fight whatever they intended.
  if (text.includes('\n')) {
    return source;
  }

  const lines: string[] = [];
  let depth = 0;

  for (const token of text.replace(/>\s*</g, '>\n<').split('\n')) {
    const line = token.trim();
    if (line === '') continue;

    // A closing tag belongs at its parent's level, so it un-indents before it is written.
    if (line.startsWith('</')) depth = Math.max(0, depth - 1);

    lines.push(INDENT.repeat(depth) + line);

    const opens =
      line.startsWith('<') &&
      !line.startsWith('</') &&
      !line.startsWith('<?') &&
      !line.startsWith('<!') &&
      !line.endsWith('/>') &&
      // `<a>text</a>` opened and closed on one line, so it changes nothing.
      !/^<([^\s/>]+)(\s[^>]*)?>.*<\/\1>$/.test(line);

    if (opens) depth++;
  }

  return lines.join('\n');
}

/** Formats by shape rather than by a declared media type, which a captured body does not carry. */
export function prettify(source: string): string {
  const text = source.trimStart();
  if (text.startsWith('{') || text.startsWith('[')) return prettifyJson(source);
  if (text.startsWith('<')) return prettifyXml(source);
  return source;
}

/** What CodeMirror should highlight it as. */
export function languageOf(source: string): 'json' | 'xml' {
  return source.trimStart().startsWith('<') ? 'xml' : 'json';
}
