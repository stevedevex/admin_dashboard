import type {
  AiStatus,
  MockContent,
  MockDataSummary,
  MockDraft,
  MockName,
  MockSummary,
  OperationSchema,
  PayloadGeneration,
  RequestDetail,
  RequestPage,
  ResolutionTrace,
  ResolveRequest,
  Scenario,
  Service,
  ValidationResult,
} from './types';

/**
 * The contract every transport implements.
 *
 * The in-memory transport satisfies it today; an HTTP transport will satisfy
 * it later. Callers depend on this interface and never on either implementation,
 * so the swap happens in `client.ts` alone.
 *
 * Methods are added here first — that keeps the mock and the eventual real
 * backend from drifting apart silently.
 */
export type Transport = {
  /** Headline numbers for the dashboard. One call, not a fan-out. */
  getMockDataSummary(): Promise<MockDataSummary>;

  listServices(): Promise<Service[]>;
  getService(id: string): Promise<Service | null>;

  listScenarios(): Promise<Scenario[]>;

  /**
   * Change the scenario the sandbox *serves*.
   *
   * The one call here that alters what the application under test receives — everything else is
   * browsing. On a shared instance it affects every caller at once, which is why it belongs to a
   * deliberate control and not to a page-level picker somebody changes without noticing.
   */
  setActiveScenario(scenarioId: string): Promise<Scenario>;

  /**
   * Create a scenario. It owns nothing until mocks are written into it.
   *
   * `parent` decides whether that matters: extending one starts the scenario serving everything
   * the parent does, so only the differences need authoring. Without a parent it serves nothing
   * at all, and every request misses until a file exists for it.
   */
  createScenario(scenario: {
    id: string;
    name?: string;
    description?: string;
    parent?: string | null;
  }): Promise<Scenario>;

  /**
   * Mocks visible in a scenario, with anything supplied by a parent scenario
   * flagged `inherited`. Resolving the inheritance chain is the server's job,
   * not the client's.
   */
  listMocks(scenarioId: string, serviceId?: string): Promise<MockSummary[]>;

  /**
   * The file name a set of key values resolves to.
   *
   * The dashboard must ask rather than build one: normalisation decides whether a mock is ever
   * reachable, and a second implementation on this side would drift into saving files no request
   * can resolve to.
   */
  nameMock(serviceId: string, operationId: string, keys: Record<string, string>): Promise<MockName>;

  /** An operation's declared response schema, and an empty payload shaped like it. */
  getSchema(serviceId: string, operationId: string): Promise<OperationSchema>;

  /** Metadata plus payload. Separate from `listMocks` so browsing never loads bodies. */
  getMock(id: string): Promise<MockContent | null>;

  /**
   * Persist a payload. Returns the stored record, so size, timestamp and state
   * come back from the server rather than being guessed at by the client.
   */
  saveMock(id: string, body: string, options?: { request?: string }): Promise<MockContent>;

  /**
   * Remove a mock and its sidecars.
   *
   * Sends the ETag the file was read with, so deleting something a colleague changed underneath
   * fails loudly rather than discarding their work — the same protection `saveMock` has, for the
   * operation where losing the race is least recoverable.
   */
  deleteMock(id: string): Promise<void>;

  /**
   * The mock a recorded call is asking for: where it would live, what it would be called, and an
   * empty payload shaped like the declared response.
   *
   * A read — nothing is created. Saving the filled-in draft is the ordinary `saveMock`, so
   * validation and freshness behave as they do everywhere else.
   */
  draftFromRequest(requestId: string): Promise<MockDraft>;

  /**
   * Check a candidate payload without saving it, so the editor can validate
   * what is on screen rather than what was last persisted.
   *
   * Takes the mock's id rather than loose coordinates: the id names the
   * operation, and an operation is what has a contract to check against.
   *
   * `remember` records the verdict against that mock, which is the only thing
   * that ever fills in the state the tree shows. It must be false while the
   * text is unsaved: a verdict on a draft describes bytes nobody has stored,
   * and stamping the file with it would make the tree vouch for a payload that
   * does not exist.
   */
  validateMock(
    mockId: string,
    body: string,
    options?: { remember?: boolean },
  ): Promise<ValidationResult>;

  /**
   * Calls the application under test made, oldest first.
   *
   * `since` is a cursor from a previous page, so polling asks only for what is new rather than
   * refetching a log that is mostly unchanged.
   */
  listRequests(options?: { since?: string; limit?: number }): Promise<RequestPage>;

  /** One entry with its bodies and full resolution trace. */
  getRequest(id: string): Promise<RequestDetail | null>;

  /**
   * Whether payload generation is available, and which provider would answer.
   *
   * Asked before the action is offered. A button that fails on click teaches people the feature
   * is broken, which is more expensive than a button that was never drawn.
   */
  getAiStatus(): Promise<AiStatus>;

  /**
   * Ask for a payload for one operation, generated against its contract and checked against it.
   *
   * A read: nothing is created, exactly like {@link draftFromRequest}. What comes back is a
   * proposal and a verdict, and saving it is the ordinary save an author makes after reading it.
   *
   * @param prompt what is wanted in words — how many records, which fields matter. Optional;
   *   absent means a representative, fully populated response.
   * @param current what the editor already holds, sent as context so a request that is really
   *   an adjustment to what is already there can be answered by adjusting it rather than by
   *   inventing a replacement. Which of the two it is, is the model's judgement to make.
   */
  generatePayload(
    serviceId: string,
    operationId: string,
    prompt?: string,
    current?: string,
  ): Promise<PayloadGeneration>;

  /**
   * Re-read the mock library from the underlying store.
   *
   * Distinct from refetching. The server answers from an in-memory index, so a file edited on
   * disk — by an editor, a `git checkout`, a colleague's branch — is invisible until this is
   * called. Explicit rather than watched, because a mounted network share gives no change
   * notification and behaviour that works on a laptop but not on the deployed instance is worse
   * than none.
   */
  reloadStore(): Promise<void>;

  /**
   * Try a request without sending one: which operation it reaches, what identified it, what was
   * ignored, and which file would answer.
   *
   * Runs the server's real resolution pipeline. A dry run with its own copy of the matching rules
   * would agree with the server right up until they drifted — which is precisely when someone
   * would be using it to find out why a request did not match.
   */
  resolve(request: ResolveRequest): Promise<ResolutionTrace>;
};
