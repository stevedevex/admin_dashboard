/**
 * The one place this application talks to the network.
 *
 * The control panel answers failures as RFC 9457 `application/problem+json`, whose `detail` is
 * written for whoever is looking at the screen — it names what was asked for and, where the list
 * is short enough to help, what exists instead. Surfacing that text is the whole reason this
 * wrapper exists: a bare "Request failed with 404" turns a typo into an investigation.
 */

/** Relative on purpose: the dev server proxies `/__tao`, so nothing is ever cross-origin. */
const BASE = '/__tao';

export type Problem = {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
};

/** Carries the server's own words, plus the machine-readable type for callers that branch on it. */
export class ApiError extends Error {
  readonly status: number;
  readonly type: string | undefined;

  constructor(message: string, status: number, type?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.type = type;
  }
}

async function fail(response: Response): Promise<never> {
  let problem: Problem | null = null;
  try {
    problem = (await response.json()) as Problem;
  } catch {
    // Not problem+json — a proxy error page, or an empty body. Fall through to the status line.
  }

  const message = problem?.detail ?? problem?.title ?? `${response.status} ${response.statusText}`;
  throw new ApiError(message, response.status, problem?.type);
}

export type RequestOptions = {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  /** Reads the response headers as well as the body — needed wherever an ETag matters. */
  withHeaders?: boolean;
};

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { data } = await requestWithHeaders<T>(path, options);
  return data;
}

export async function requestWithHeaders<T>(
  path: string,
  options: RequestOptions = {},
): Promise<{ data: T; headers: Headers }> {
  const hasBody = options.body !== undefined;

  const response = await fetch(`${BASE}${path}`, {
    method: options.method ?? 'GET',
    headers: {
      Accept: 'application/json',
      ...(hasBody ? { 'Content-Type': 'application/json' } : {}),
      ...options.headers,
    },
    ...(hasBody ? { body: JSON.stringify(options.body) } : {}),
  });

  if (!response.ok) return fail(response);

  // 204, and any other body-less success.
  if (response.status === 204 || response.headers.get('Content-Length') === '0') {
    return { data: undefined as T, headers: response.headers };
  }

  return { data: (await response.json()) as T, headers: response.headers };
}

/**
 * A path segment that may contain anything a file name can.
 *
 * Mock ids travel as a path rather than one encoded segment — a servlet container rejects `%2F`
 * in a path by default — so the separators must survive while everything else is escaped.
 */
export function encodePath(id: string): string {
  return id.split('/').map(encodeURIComponent).join('/');
}
