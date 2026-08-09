import type {
  AiStatus,
  MockContent,
  MockDataSummary,
  MockDraft,
  MockFormat,
  MockName,
  MockState,
  MockSummary,
  RequestDetail,
  RequestEntry,
  OperationSchema,
  PayloadGeneration,
  RequestPage,
  ResolutionTrace,
  ResolveRequest,
  Scenario,
  Service,
  ValidationResult,
} from '../types';
import type { Transport } from '../transport';
import { ApiError, encodePath, request, requestWithHeaders } from './request';

/**
 * The control panel, over HTTP.
 *
 * Wire shapes are translated here and nowhere else: the server names things in its own vocabulary
 * (`type`, `parent`, `SCHEMA`), the application in the domain-neutral one declared in `types.ts`.
 * Keeping the translation in this file is what lets either side be renamed without a sweep
 * through the features.
 *
 * Nothing here computes what the server can state. Counts, inheritance, validation verdicts and
 * effective status all arrive as facts — a second implementation on this side would drift, and a
 * dashboard disagreeing with the server it is describing is worse than one that cannot answer.
 */

// --- wire shapes ---------------------------------------------------------

type WireSummary = {
  serviceCount: number;
  servicesWithoutSchema: number;
  scenarioCount: number;
  activeScenarioId: string;
  mockCount: number;
  invalidCount: number;
  incompleteCount: number;
  largestMockBytes: number;
};

type WireKey = { name: string; source: string; expression: string };

type WireOperation = { id: string; method: string; path: string; keys: WireKey[] };

type WireService = {
  id: string;
  name: string;
  type: string;
  endpoint: string;
  specLocation: string;
  format: string;
  hasSchema: boolean;
  mockCount: number;
  operations: WireOperation[];
};

type WireMockName = { fileName: string; normalised: Record<string, string> };

type WireScenario = {
  id: string;
  name: string;
  description: string;
  parent: string | null;
  mockCount: number;
  serviceIds: string[];
};

type WireMockSummary = {
  id: string;
  scenarioId: string;
  serviceId: string;
  operationId: string;
  fileName: string;
  format: string;
  sizeBytes: number;
  modifiedAt: string;
  inherited: boolean;
  inheritedFrom: string | null;
  state: string;
  completeness: number | null;
};

type WireMockDetail = {
  id: string;
  summary: WireMockSummary | null;
  body: string;
  envelopeHeader: string | null;
  request: string | null;
  meta: unknown;
  effective: { status: number; contentType: string };
};

type WireRequestEntry = {
  id: string | number;
  at: string;
  serviceId: string | null;
  operationId: string | null;
  scenarioId: string | null;
  status: number;
  tookMillis: number;
  matched: string | null;
  inherited: boolean;
};

type WireRequestDetail = WireRequestEntry & {
  extracted: Record<string, string>;
  attempted: string[];
  requestBody: string | null;
  responseBody: string | null;
  bodiesTruncated: boolean;
};

type WireRequestPage = { mode: string; cursor: string; entries: WireRequestEntry[] };

/** `AiStatus` needs no translation: the server already names these as the domain does. */
type WirePayloadGeneration = {
  body: string;
  validation: WireValidation;
  attempts: number;
  generator: string;
  model: string;
};

type WireValidation = {
  valid: boolean;
  checked: string;
  completeness: number | null;
  issues: { path: string; line: number | null; message: string; rule: string }[];
};

// --- translation ---------------------------------------------------------

const FORMATS: MockFormat[] = ['xml', 'json', 'text'];
const STATES: MockState[] = ['valid', 'incomplete', 'invalid', 'unchecked'];

/** Unknown values fall back rather than throwing: a new server format must not blank the screen. */
function asFormat(value: string): MockFormat {
  return FORMATS.find((format) => format === value) ?? 'text';
}

function asState(value: string): MockState {
  return STATES.find((state) => state === value) ?? 'unchecked';
}

function asChecked(value: string): ValidationResult['checked'] {
  const lowered = value.toLowerCase();
  return lowered === 'schema' || lowered === 'syntax' ? lowered : 'none';
}

function toService(wire: WireService): Service {
  return {
    id: wire.id,
    name: wire.name,
    protocol: wire.type,
    endpoint: wire.endpoint,
    format: asFormat(wire.format),
    hasSchema: wire.hasSchema,
    // Flattened across operations, in declaration order: the services page lists what identifies a
    // request to this service, and the same field named by two operations is one field to a reader.
    keyFields: [...new Set(wire.operations.flatMap((op) => op.keys.map((key) => key.name)))],
    operations: wire.operations,
    mockCount: wire.mockCount,
  };
}

function toScenario(wire: WireScenario): Scenario {
  return {
    id: wire.id,
    name: wire.name,
    description: wire.description,
    extends: wire.parent,
    serviceIds: wire.serviceIds,
    mockCount: wire.mockCount,
  };
}

function toMockSummary(wire: WireMockSummary): MockSummary {
  return {
    id: wire.id,
    fileName: wire.fileName,
    serviceId: wire.serviceId,
    operationId: wire.operationId,
    scenarioId: wire.scenarioId,
    format: asFormat(wire.format),
    sizeBytes: wire.sizeBytes,
    state: asState(wire.state),
    completeness: wire.completeness,
    inherited: wire.inherited,
    modifiedAt: wire.modifiedAt,
  };
}

function toValidation(wire: WireValidation): ValidationResult {
  return {
    valid: wire.valid,
    checked: asChecked(wire.checked),
    completeness: wire.completeness,
    issues: wire.issues,
  };
}

/** The list types an entry id as a string, the detail as a number. Both are the same cursor. */
function toRequestEntry(wire: WireRequestEntry): RequestEntry {
  return { ...wire, id: String(wire.id) };
}

/** `scenario/service/operation/file` — the id is the address, and it is parseable. */
function parseId(mockId: string): { serviceId: string; operationId: string } {
  const [, serviceId = '', operationId = ''] = mockId.split('/');
  return { serviceId, operationId };
}

// --- optimistic concurrency ----------------------------------------------

/**
 * The ETag last read for each mock, and the sidecars that came with it.
 *
 * Held because a save must send back two things it did not author. The ETag is what makes
 * concurrent edits fail loudly instead of silently overwriting — re-reading it inside `saveMock`
 * would technically satisfy the server while defeating the entire point, since a fresh read always
 * matches. The sidecars are held because the save endpoint takes the whole mock: sending only the
 * body would clear a mock's status override or SOAP envelope header as a side effect of editing
 * its payload, and nothing on screen would explain where they went.
 */
const lastRead = new Map<string, { etag: string | null; envelopeHeader: string | null; meta: unknown }>();

export const httpTransport: Transport = {
  async getMockDataSummary(): Promise<MockDataSummary> {
    const wire = await request<WireSummary>('/summary');
    return wire;
  },

  async listServices(): Promise<Service[]> {
    const wire = await request<WireService[]>('/services');
    return wire.map(toService);
  },

  async getService(id: string): Promise<Service | null> {
    const wire = await request<WireService[]>('/services');
    return wire.map(toService).find((service) => service.id === id) ?? null;
  },

  async listScenarios(): Promise<Scenario[]> {
    const wire = await request<WireScenario[]>('/scenarios');
    return wire.map(toScenario);
  },

  async createScenario(scenario: {
    id: string;
    name?: string;
    description?: string;
    parent?: string | null;
  }): Promise<Scenario> {
    const wire = await request<WireScenario>('/scenarios', { method: 'POST', body: scenario });
    return toScenario(wire);
  },

  async setActiveScenario(scenarioId: string): Promise<Scenario> {
    const wire = await request<WireScenario>('/scenarios/active', {
      method: 'PUT',
      body: { scenarioId },
    });
    return toScenario(wire);
  },

  async listMocks(scenarioId: string, serviceId?: string): Promise<MockSummary[]> {
    const query = new URLSearchParams({ scenario: scenarioId });
    if (serviceId) query.set('service', serviceId);

    const wire = await request<WireMockSummary[]>(`/mocks?${query}`);
    return wire.map(toMockSummary);
  },

  async nameMock(
    serviceId: string,
    operationId: string,
    keys: Record<string, string>,
  ): Promise<MockName> {
    return request<WireMockName>('/mocks/name', {
      method: 'POST',
      body: { serviceId, operationId, keys },
    });
  },

  async getSchema(serviceId: string, operationId: string): Promise<OperationSchema> {
    return request<OperationSchema>(
      `/services/${encodeURIComponent(serviceId)}/operations/${encodeURIComponent(operationId)}/schema`,
    );
  },

  async getMock(id: string): Promise<MockContent | null> {
    let data: WireMockDetail;
    let headers: Headers;

    try {
      ({ data, headers } = await requestWithHeaders<WireMockDetail>(`/mocks/${encodePath(id)}`));
    } catch (cause) {
      // Absent is a normal answer, and the signature says so. It is also the ordinary case when
      // the editor is opened on a mock nobody has written yet.
      if (cause instanceof ApiError && cause.status === 404) return null;
      throw cause;
    }

    lastRead.set(id, {
      etag: headers.get('ETag'),
      envelopeHeader: data.envelopeHeader,
      meta: data.meta,
    });

    // A detail with no summary is a file the store has not indexed yet — present on disk, awaiting
    // a reload. It is still editable, so it is described from its id rather than refused.
    const summary = data.summary
      ? toMockSummary(data.summary)
      : {
          ...parseId(id),
          id,
          fileName: id.split('/').pop() ?? id,
          scenarioId: id.split('/')[0] ?? '',
          format: asFormat(id.endsWith('.json') ? 'json' : id.endsWith('.xml') ? 'xml' : 'text'),
          sizeBytes: new TextEncoder().encode(data.body).length,
          state: 'unchecked' as MockState,
          completeness: null,
          inherited: false,
          modifiedAt: new Date().toISOString(),
        };

    return { ...summary, body: data.body, request: data.request, effective: data.effective };
  },

  async validateMock(
    mockId: string,
    body: string,
    options: { remember?: boolean } = {},
  ): Promise<ValidationResult> {
    const { serviceId, operationId } = parseId(mockId);

    // The id travels as a query parameter only when the verdict should stick to the file. Sent for
    // a draft, it would record a judgement about text the store does not hold.
    const path = options.remember
      ? `/validate?mockId=${encodeURIComponent(mockId)}`
      : '/validate';

    const wire = await request<WireValidation>(path, {
      method: 'POST',
      body: { serviceId, operationId, body },
    });

    return toValidation(wire);
  },

  async getAiStatus(): Promise<AiStatus> {
    return request<AiStatus>('/ai/status');
  },

  async generatePayload(
    serviceId: string,
    operationId: string,
    prompt?: string,
    current?: string,
  ): Promise<PayloadGeneration> {
    const wire = await request<WirePayloadGeneration>('/ai/payload', {
      method: 'POST',
      body: { serviceId, operationId, prompt: prompt ?? null, current: current || null },
    });

    // The verdict goes through the same translation as every other one, so a generated payload and
    // a hand-typed one are described in identical terms on screen.
    return { ...wire, validation: toValidation(wire.validation) };
  },

  async saveMock(id: string, body: string, options: { request?: string } = {}): Promise<MockContent> {
    const known = lastRead.get(id);

    const { data, headers } = await requestWithHeaders<WireMockDetail>(`/mocks/${encodePath(id)}`, {
      method: 'PUT',
      headers: known?.etag ? { 'If-Match': known.etag } : {},
      body: {
        body,
        // Preserved, not re-authored: see `lastRead`.
        envelopeHeader: known?.envelopeHeader ?? null,
        meta: known?.meta ?? null,
        // Sent only on the save that creates a mock from a recorded call. Absent means "leave
        // whatever is recorded" — the server treats it that way, unlike the other two sidecars.
        ...(options.request === undefined ? {} : { request: options.request }),
      },
    });

    lastRead.set(id, {
      etag: headers.get('ETag'),
      envelopeHeader: data.envelopeHeader,
      meta: data.meta,
    });

    const summary = data.summary ? toMockSummary(data.summary) : null;
    if (!summary) throw new Error(`Saved ${id}, but the server did not describe it`);

    return { ...summary, body: data.body, request: data.request, effective: data.effective };
  },

  async resolve(probe: ResolveRequest): Promise<ResolutionTrace> {
    return request<ResolutionTrace>('/resolve', { method: 'POST', body: probe });
  },

  async reloadStore(): Promise<void> {
    await request<unknown>('/reload', { method: 'POST' });
  },

  async deleteMock(id: string): Promise<void> {
    const known = lastRead.get(id);

    await request<void>(`/mocks/${encodePath(id)}`, {
      method: 'DELETE',
      // Required by the server on a mock that exists: without it the delete is refused rather than
      // racing a concurrent edit. See `lastRead` for why it is not re-read here.
      headers: known?.etag ? { 'If-Match': known.etag } : {},
    });

    // Nothing left to be stale against, and a retained ETag would be offered for whatever file is
    // written to this id next.
    lastRead.delete(id);
  },

  async draftFromRequest(requestId: string): Promise<MockDraft> {
    return request<MockDraft>(`/requests/${encodeURIComponent(requestId)}/draft`);
  },

  async listRequests(options: { since?: string; limit?: number } = {}): Promise<RequestPage> {
    const query = new URLSearchParams();
    if (options.since) query.set('since', options.since);
    if (options.limit) query.set('limit', String(options.limit));

    const suffix = query.size > 0 ? `?${query}` : '';
    const wire = await request<WireRequestPage>(`/requests${suffix}`);

    return {
      mode: wire.mode.toLowerCase() === 'sampled' ? 'sampled' : 'full',
      cursor: wire.cursor,
      entries: wire.entries.map(toRequestEntry),
    };
  },

  async getRequest(id: string): Promise<RequestDetail | null> {
    try {
      const wire = await request<WireRequestDetail>(`/requests/${encodeURIComponent(id)}`);
      return { ...wire, id: String(wire.id) };
    } catch (cause) {
      // A bounded log evicts: an entry that has aged out is a normal answer, not a failure.
      if (cause instanceof ApiError && cause.status === 404) return null;
      throw cause;
    }
  },
};
