/**
 * Reading the two forms a mock's location comes back in.
 *
 * A resolution trace lists the candidates it formed — `scenarios/baseline/petstore/showPetById/petid=1`
 * — while `matched` is an address — `baseline/petstore/showPetById/petid=1.json`. One is a path the
 * resolver walked, the other is a mock id, and neither side invents the other's form.
 *
 * So comparing them is a rule rather than a formality, and it lives here because two features apply
 * it: the request log marks the winner among the files it tried, and so does the playground. Two
 * copies would be two chances to disagree about which line gets the tick.
 */

/** The file name without its extension — resolution matches on the stem, not the suffix. */
function stemOf(path: string): string {
  const file = path.split('/').pop() ?? path;
  const dot = file.lastIndexOf('.');
  return dot > 0 ? file.slice(0, dot) : file;
}

function scenarioOf(path: string): string {
  return path.replace(/^scenarios\//, '').split('/')[0] ?? '';
}

/** Whether an attempted store path and a matched mock id name the same file. */
export function sameFile(attempted: string, matched: string): boolean {
  return stemOf(attempted) === stemOf(matched) && scenarioOf(attempted) === scenarioOf(matched);
}

/**
 * The file name alone.
 *
 * A mock id is `scenario/service/operation/file`, and wherever the first three are already on
 * screen — as columns, as tags — repeating them pushes the part that differs off the edge.
 */
export function fileOf(mockId: string): string {
  return mockId.split('/').pop() ?? mockId;
}

/** The scenario a mock id belongs to, for handing one to a page that shows one scenario at a time. */
export function scenarioIn(mockId: string): string {
  return mockId.split('/')[0] ?? '';
}
