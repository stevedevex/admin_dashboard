# frontend

The dashboard for the mock sandbox: browse the contracts it serves, author and validate mock
payloads, switch scenarios, watch what an application actually called, and send requests by hand to
see what comes back.

It is a **client of a running sandbox**, not a standalone app. There is no fixture mode — everything
on screen came from the server, on purpose, so the dashboard can never agree with itself and
disagree with the thing it describes. Start the backend first or every page will be empty.

For how the code is organised — the two seams, the dependency rules, the naming conventions — read
[ARCHITECTURE.md](ARCHITECTURE.md). This file is about running it.

## Running it

Node `>=22.12.0` and npm `>=11.0.0` (see `engines`).

```bash
npm ci
npm run dev
```

That serves the dashboard on **http://localhost:5173**. It expects the sandbox's control panel on
**http://localhost:8080**, which comes from the sibling backend:

```bash
cd ../backend/app && mvn spring-boot:run
```

The backend reads its mock library from `../mock-data/mocks` by default, so launched from that
directory it needs no arguments.

### Why only `/__tao` is proxied

`vite.config.ts` proxies exactly one prefix, and the omission is deliberate rather than an oversight.
`/__tao/**` is the control panel, which is same-origin in production — proxying it in development
keeps it same-origin here too, so no CORS configuration ever ships.

The **mock endpoints are not proxied**. They belong to the application under test, and routing them
through the dashboard's dev server would make the dashboard look like a client of them. If you want
to call a mock endpoint directly, call the backend on `:8080` — or use the Playground, which asks the
server to call itself.

## Commands

| | |
| ----------------------- | ----------------------------------------------------------------- |
| `npm run dev`           | Vite dev server on 5173, HMR                                      |
| `npm run verify`        | **The gate.** guard → lint → typecheck → test. Run before pushing  |
| `npm test`              | Vitest once; `npm run test:watch` to iterate                      |
| `npm run typecheck`     | `tsc --noEmit`                                                    |
| `npm run lint`          | ESLint, including the architectural boundary rules                 |
| `npm run guard`         | Fails on any term from `.denyterms` — see below                    |
| `npm run build`         | Typecheck, then production build                                  |

`npm run verify` is the one to remember; the rest are what it runs.

**`npm run guard`** enforces that no organisation-specific or product-specific vocabulary appears
anywhere in this directory — code, comments, tests and fixtures alike. Copy `.denyterms.example` to
`.denyterms` if you do not have one. It is not a style rule: the repository is a teaching sample, and
the domain is deliberately generic.

**Prettier is present but not enforced.** `format:check` is not part of `verify`, no git hook is
installed, and a number of files predate it, so it currently reports failures that nobody is acting
on. Either wire it up or drop it — but do not run `prettier --write .` casually, because the reflow
touches comment prose that is written deliberately.

## The pages

Each is one directory under `src/features/`, and each is a nav entry in `src/config/navigation.ts`.

| Page | What it answers |
| ---------- | ------------------------------------------------------------------------------------ |
| Dashboard  | What this sandbox is, and what it is currently serving |
| Services   | Which contracts are loaded, their operations, and the keys that identify a request |
| Mocks      | The file tree and the payload editor, with a dry run above it |
| Scenarios  | Create scenarios, and switch the one the sandbox serves to everybody |
| Requests   | What the application under test actually called, matched or not — and draft a mock from a call |
| Playground | Send a request for real and read the response the way a client would receive it |

The Mocks page's dry run and the Playground answer neighbouring questions and are not the same
thing. The dry run says *which file would answer this, and why*, sends nothing, and stores nothing.
The Playground *sends the request*, so what it shows is the wrapped envelope, the status a sidecar
chose, and the headers — none of which are visible in the file an author edits.

## Things that will cost you an hour

**Every page is empty, or a "not polling" notice appears.** The backend is not running on 8080.
Nothing here has a fallback, by design.

**You added a service to the backend config and it does not appear.** Specs are read once, at
startup, and routes are registered from them — so a new or changed spec needs a **backend restart**.
The Reload button re-reads the *mock files* only, which is a different thing and deliberately so.

**A mock you edited on disk does not show up.** That is what Reload is for. The store is an in-memory
index, and a mounted network share gives no change notification, so changes surface explicitly rather
than by watching.

**Requests shows calls you made yourself.** The Playground sends real requests, so they are logged
like any other — labelled `playground` in the *From* column, so the log never implies your
application sent them.

**A drafted request resolves to `_default` instead of the mock you meant.** Read the note under the
request. A draft can only fill in what it can address, and an operation identified by an HTTP header
is the known case it cannot: the Playground has no header field yet.

**ESLint rejects an import you think is reasonable.** Features may not import from other features,
and nothing outside `src/ui` or `src/api` may reach around those seams. The fix is to promote the
shared code — to `lib/` if it is a pure function, `state/` if it is genuinely global — not to widen
the rule. See [ARCHITECTURE.md](ARCHITECTURE.md#rules).
