/**
 * Data contracts.
 *
 * Deliberately domain-neutral: this describes *a* mock server, not any
 * particular one. Everything specific — protocols, field names, endpoints —
 * arrives as data from the backend and is never enumerated here.
 */

/** One field the resolver extracts, as the server names it. */
export type KeyField = {
  /** The short name used in file names and traces — `tickerSymbol`, not the xpath it came from. */
  name: string;
  /** Where it is read from: PATH, QUERY, HEADER, BODY, XPATH. */
  source: string;
  expression: string;
  /**
   * The field's own name, when `name` is a short one configuration chose with `as`. Null when the
   * two are the same.
   *
   * An aliased key is the one case where a name explains nothing: `brid` is somebody's
   * abbreviation, not a word any schema uses, so anywhere the name is shown alone has to be able
   * to say what it stands for.
   */
  aliasOf: string | null;
};

/**
 * How much of a request a mock's file name has to account for.
 *
 * `ALL` — every declared key. `FIRST_PRESENT` — exactly one, the first with a value.
 * `BEST_MATCH` — any subset, each file naming the keys that decide it and matching whatever the
 * rest happen to be.
 */
export type KeyStrategy = 'ALL' | 'FIRST_PRESENT' | 'BEST_MATCH';

/** An operation a service serves, and what identifies a request to it. */
export type Operation = {
  id: string;
  method: string;
  path: string;
  keys: KeyField[];
  /**
   * Which of `keys` a file name carries. Needed wherever key values are collected: the same blank
   * field is an omission under `ALL` and a deliberate wildcard under `BEST_MATCH`.
   */
  strategy: KeyStrategy;
};

/** A mocked upstream endpoint. `protocol` is free-form: the backend names it. */
export type Service = {
  id: string;
  name: string;
  protocol: string;
  endpoint: string;
  format: MockFormat;
  hasSchema: boolean;
  /**
   * Fields the resolver extracts from a request, in priority order, flattened across operations.
   *
   * The whole descriptor rather than the name: a name on its own is enough to render only while
   * every name is the field's own, and an alias breaks that quietly — the page keeps rendering,
   * and what it renders stops meaning anything.
   */
  keyFields: KeyField[];
  operations: Operation[];
  mockCount: number;
};

/**
 * The file name a set of key values resolves to, computed by the server.
 *
 * Never derived on this side. Normalisation — lowercasing, trimming, stripping leading zeros —
 * decides whether a saved mock is ever reachable, and a second implementation here would drift
 * silently: a file that exists and no request resolves to.
 *
 * @param normalised what each value became on the way, so an author sees that `00005678` is
 *   stored as `5678` rather than discovering it when the mock they saved is not the one served
 */
export type MockName = {
  fileName: string;
  normalised: Record<string, string>;
};

/** An operation's declared response schema, and an empty payload shaped like it. */
export type OperationSchema = {
  format: string;
  available: boolean;
  schema: string | null;
  reason: string | null;
  skeleton: string | null;
};

export type MockFormat = 'xml' | 'json' | 'text';

/** State of a single mock file, as assessed by the backend. */
export type MockState = 'valid' | 'incomplete' | 'invalid' | 'unchecked';

/** Metadata only — payload bytes are fetched separately and never listed. */
export type MockSummary = {
  id: string;
  fileName: string;
  serviceId: string;
  /** Operations within a service answer different shapes, so each is its own namespace. */
  operationId: string;
  scenarioId: string;
  format: MockFormat;
  sizeBytes: number;
  state: MockState;
  /** Percentage of schema-declared fields populated, when a schema exists. */
  completeness: number | null;
  /** True when served from a parent scenario rather than this one. */
  inherited: boolean;
  /**
   * Whether any request could produce this file's name. False is not a broken payload — it is a
   * file at an address nothing computes, so it never answers and the operation's default does.
   */
  reachable: boolean;
  /** Why not, in words an author can act on. Null when reachable. */
  unreachableReason: string | null;
  modifiedAt: string;
};

export type MockContent = MockSummary & {
  body: string;
  /**
   * The call this mock was written for, when it was created from a recorded one. Documentation,
   * never a matcher: resolution reads declared keys and nothing else.
   */
  request: string | null;
  /**
   * What a client actually receives, once the contract's defaults are applied over anything the
   * mock overrides. Shown beside the payload because a mock answering 201 with nothing of its own
   * got that from its contract, and there is otherwise no way to discover it.
   */
  effective: { status: number; contentType: string };
};

export type Scenario = {
  id: string;
  name: string;
  description: string;
  /** Parent scenario this one inherits from, if any. */
  extends: string | null;
  serviceIds: string[];
  mockCount: number;
};

/**
 * Headline numbers for one capability, for the dashboard.
 *
 * A single call per capability rather than the dashboard fanning out and
 * counting client-side — that stays cheap as the mock library grows, and it
 * keeps "what counts as invalid" a server decision.
 */
export type MockDataSummary = {
  serviceCount: number;
  servicesWithoutSchema: number;
  scenarioCount: number;
  activeScenarioId: string;
  mockCount: number;
  invalidCount: number;
  incompleteCount: number;
  /**
   * How many mocks nothing has validated — the denominator the other two counts are missing.
   *
   * Verdicts are remembered in memory and only ever written by a validation actually running, so a
   * restarted sandbox reports every mock unchecked and both other counts zero. Without this, that
   * is indistinguishable from a library checked end to end and found clean.
   */
  uncheckedCount: number;
  /**
   * How many mocks sit at an address no request produces — named for a key the operation does not
   * declare, or for a subset under a strategy that demands all of them.
   *
   * Counted apart from the validation buckets because it is a different kind of wrong: those
   * describe a payload, this describes an address, and a mock can be flawless on one and hopeless
   * on the other. It has to be surfaced somewhere, because nothing about serving reveals it — the
   * file never wins and the operation's default answers in its place.
   */
  unreachableCount: number;
  largestMockBytes: number;
};

export type ValidationIssue = {
  path: string;
  line: number | null;
  message: string;
  rule: string;
};

export type ValidationResult = {
  valid: boolean;
  /**
   * What was actually verified.
   *
   * `syntax` means the payload parses but nothing checked it against a schema;
   * reporting that as plain "valid" would let a schema-invalid mock look clean.
   * `none` is the honest answer for formats with no validator at all.
   */
  checked: 'schema' | 'syntax' | 'none';
  completeness: number | null;
  issues: ValidationIssue[];
};

/**
 * Whether payload generation can be offered, and by what.
 *
 * `generator` is displayed, not merely recorded: what produced a payload is the one thing a
 * reader cannot establish by looking at it.
 *
 * @param reason why it cannot be used, or null when it can. Two unavailabilities need telling
 *   apart — nothing configured, and configured but unreachable — because only one is fixed by
 *   editing configuration, and a single "unavailable" sends people to the wrong one.
 */
export type AiStatus = {
  available: boolean;
  generator: string;
  model: string;
  reason: string | null;
};

/**
 * A payload a model proposed for an operation, and the sandbox's verdict on it.
 *
 * Nothing is written by asking for this. The body arrives in the editor as a draft like any
 * other, and saving is the same deliberate action it always was.
 *
 * @param validation from the same validator the Validate button uses, so the two can never
 *   disagree. Returned whatever it says: a payload that failed is still worth showing, because
 *   the issues are what an author fixes — but it must never be presented as verified when
 *   `checked` is not `schema`.
 * @param attempts model calls it took, so a loop repairing on every request is visible rather
 *   than merely slow
 */
export type PayloadGeneration = {
  body: string;
  validation: ValidationResult;
  attempts: number;
  generator: string;
  model: string;
};

/**
 * A request described rather than sent.
 *
 * Two shapes, because the protocols identify an operation differently: REST is described, since
 * its method and path carry meaning no body contains; SOAP is pasted whole, since the envelope
 * carries everything and whoever is debugging one has it on their clipboard already.
 */
export type ResolveRequest = {
  scenarioId?: string;
  /** Present means REST. Absent means the body is a SOAP envelope. */
  method?: string;
  path?: string;
  body?: string;
  /** Sent as given, minus the few the HTTP client owns. Only meaningful when actually sending. */
  headers?: Record<string, string>;
  /** Overrides what the protocol would otherwise imply. */
  contentType?: string;
};

/**
 * What a client would actually have received.
 *
 * The response verbatim, not a description of one — which is the whole difference from
 * {@link ResolutionTrace}. A mock file holds a payload; what leaves the server is that payload
 * wrapped in an envelope, given a status a sidecar or the contract chose, carrying headers written
 * in neither place.
 *
 * `requestId` names the log entry this call was recorded under, so the trace is one fetch away
 * rather than duplicated here. `discarded` is the exception: the serving path never enumerates a
 * request's fields, so the log cannot carry that one and the server computes it alongside.
 *
 * `tookMillis` is the round trip including the sandbox's own loopback hop — not the resolution
 * time, which the log entry holds.
 */
export type PlaygroundResult = {
  serviceId: string;
  operationId: string;
  scenarioId: string;
  /** What was called, so the loopback is visible rather than implied. */
  url: string;
  status: number;
  /** Names arrive lower-cased: that is how they come off the wire, and inventing casing would lie. */
  headers: Record<string, string>;
  body: string;
  tookMillis: number;
  requestId: string | null;
  discarded: string[];
};

/**
 * A request composed from the contract, ready to send or edit.
 *
 * Values are written at the locations each key's own declaration reads from, so a draft made from a
 * mock's keys resolves to that mock by construction rather than by looking plausible.
 *
 * `method` is null for SOAP, where the contract fixes both the verb and the endpoint. `note` says
 * what could not be filled in — a request that falls through to an operation's default otherwise
 * reads as a miss.
 */
export type PlaygroundDraft = {
  method: string | null;
  path: string;
  body: string | null;
  contentType: string | null;
  note: string | null;
};

/**
 * Where a request would have resolved, and what was tried on the way.
 *
 * `discarded` is the point of asking: seeing a correlation id or a timestamp listed there is what
 * turns "it did not match" into "of course, that is not what identifies it".
 *
 * `matched` being null is a successful answer describing a miss, not a failure.
 */
export type ResolutionTrace = {
  serviceId: string;
  operationId: string;
  scenarioId: string;
  extracted: Record<string, string>;
  discarded: string[];
  attempted: string[];
  matched: string | null;
  inherited: boolean;
  tookMillis: number;
};

/**
 * One call the application under test made.
 *
 * Bodies are absent here and fetched per entry: a log page listing megabyte payloads would be
 * unusable, and they are one click away on the entry someone actually opens.
 *
 * `operationId` is null for a request rejected before resolution — a malformed envelope, or an
 * operation the contract declares but configuration does not serve. Those are recorded rather
 * than dropped: a client sending nothing the sandbox understands otherwise sees an empty log,
 * which reads as "my requests are not arriving".
 */
export type RequestEntry = {
  id: string;
  at: string;
  /**
   * Whether the application under test made this call, or somebody made it by hand in the
   * playground.
   *
   * Labelled rather than filtered out at the source. Dropping hand-made calls would leave the log
   * disagreeing with what the server demonstrably served; mixing them in unlabelled would have the
   * log answer "did my application send that?" wrongly, which is the one question it exists for.
   */
  source: 'client' | 'playground';
  serviceId: string | null;
  operationId: string | null;
  scenarioId: string | null;
  status: number;
  tookMillis: number;
  /** The mock that answered, or null on a miss. The misses are what the log is opened for. */
  matched: string | null;
  inherited: boolean;
};

/** An entry with everything the trace recorded. */
export type RequestDetail = RequestEntry & {
  extracted: Record<string, string>;
  attempted: string[];
  requestBody: string | null;
  responseBody: string | null;
  /** Set when a body was longer than the retained limit, so nobody reads a cut-off payload as whole. */
  bodiesTruncated: boolean;
};

/**
 * The mock a recorded call is asking for.
 *
 * A proposal — nothing exists until an author fills the payload in and saves it. `note` explains
 * a name that is not the obvious one: a call whose keys did not satisfy the operation's strategy
 * was answered by the operation's default, and a file named from the partial keys would sit there
 * unreachable.
 */
export type MockDraft = {
  mockId: string;
  serviceId: string;
  operationId: string;
  scenarioId: string;
  fileName: string;
  keys: Record<string, string>;
  /** True when something is already stored there, so the dashboard can warn before overwriting. */
  exists: boolean;
  /** An empty payload shaped like the declared response, or null when nothing declares one. */
  skeleton: string | null;
  requestBody: string | null;
  note: string | null;
};

/**
 * @param mode `sampled` when the ring buffer wrapped past the caller's cursor, so some requests
 *   were lost. It must be said out loud: a silently truncating log is worse than none, because
 *   quiet reads as "no traffic".
 * @param cursor pass back as `since` to fetch only what arrived after this page
 */
export type RequestPage = {
  mode: 'full' | 'sampled';
  cursor: string;
  entries: RequestEntry[];
};
