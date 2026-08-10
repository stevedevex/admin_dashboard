# mock-data

A schema-driven mock service for REST and SOAP contracts. Drop in an OpenAPI document or a WSDL,
name the operations you want served and what identifies a request, add one mock file — and the
sandbox answers exactly as the real service's contract promises, without a line of code.

This module is a **library**, not an application. It wires itself into any Spring Boot app that
depends on it (via its auto-configuration); the runnable server lives in the sibling
[`app`](../app) module. To run it:

```bash
cd ../app && mvn spring-boot:run
```

## What it does

- **Serves mocks from contracts.** REST endpoints are routed from the OpenAPI document (method,
  path, success status, media type all come from the spec); SOAP services answer 1.1 and 1.2
  clients at one endpoint, serve their `?wsdl` rewritten to point at the sandbox, and identify
  operations by the body element, as document/literal clients address them.
- **Resolves by declared identity, not whole payloads.** Each operation declares *keys* — the
  fields that identify a request (`path:petId`, `query:limit`, `body:$.name`,
  `xpath:/soapenv:Envelope/...`). Only declared keys are read, so a client adding a correlation id
  or a timestamp cannot change which mock is served. A key can be given a short name with `as`,
  and a mock can match on **any subset** of the declared keys — see
  [which keys have to match](#which-keys-have-to-match).
- **Scenarios with inheritance.** A scenario is a directory of mocks. A scenario can `extends` a
  parent and override only what differs — a `partial-data` scenario is a delta on `baseline`, not
  a copy.
- **Fails loudly.** A resolution miss answers a diagnostic (problem+json for REST, a SOAP fault
  for SOAP) naming the keys extracted and every path tried. Never an empty response.
- **A control panel for the dashboard.** Everything under `/__tao`: browse services and mocks,
  save/validate payloads against the contract's schema, dry-run a request, switch scenarios, read
  the request log. The full API is specified in `docs/control-panel-specs.md` (local design docs,
  not committed).

## How it works

One pipeline, whatever the protocol:

```
request → identify operation → extract declared keys → normalise
        → resolve against the scenario chain → answer (or explain the miss)
```

- **Specs are parsed once, at startup** (`spec/`). A misconfigured service fails boot with every
  problem listed, rather than failing on the first request that happens to hit it.
- **REST and SOAP converge on `RequestFacade`** (`runtime/match`): each protocol says how to read
  a request; everything downstream — normalisation, lookup, tracing — is shared.
- **Normalisation** trims, treats empty as absent, and strips leading zeros from purely numeric
  values, so `stamm=00005678` and `partnerId=5678` resolve to the same mock.
- **Storage is pluggable** (`store/`): the hot path sees `MockProvider`, the control panel
  `MockRepository`. The filesystem implementation keeps mocks git-versionable and diffable.

### Finding your way around the code

**Searching the source for a served path will not find it.** Client-facing routes carry no
annotations — they are registered at startup from the parsed contracts, by `runtime/rest/RestRoutes`
and `runtime/soap/SoapRoutes`. The route table lives in `application.yaml` and the spec, not in the
code.

So the authoritative answer to "what does this instance serve" is one of:

- the **startup log**, which prints a line per REST route and per SOAP endpoint;
- `GET /__tao/services`, which reports the same thing to the dashboard.

Both derive from `SpecRegistry`, so neither can drift from what is actually routed.

Each package carries a `package-info.java` saying what it is for and where it sits; the one on
`com.tao.sandbox` has the pipeline diagram and the layering. Read that first — dependencies run
upward, with one recorded exception between `spec` and its two loaders. `PackageLayeringTest`
checks all of this against the source, so the diagram cannot quietly stop being true.

### Storage layout

```
mocks/scenarios/<scenario>/scenario.yaml            # name, description, extends
                          /<service>/<operation>/<key=value&...>.<ext>   # one mock
                                                  /_default.<ext>        # fallback
                                                  /<stem>.meta.yaml      # optional: status, headers, kind
                                                  /<stem>.header.xml     # optional: SOAP envelope header
                                                  /<stem>.request.<ext>  # optional: the call it was written for
```

Resolution tries the most specific name first (`petid=1.json`), then `_default`, walking the
scenario's inheritance chain. Mock files hold the **payload only** — SOAP envelopes are wrapped at
serve time, matching whichever SOAP version the client used.

## Adding a new API

Three steps, no code. The `userservice` sample was added exactly this way.

### 1. Drop the contract in

Put the spec in its own folder under `src/main/resources/specs/` (or any `file:` path):

```
specs/orders/orders.yaml          # REST: OpenAPI 3.0 or 3.1
specs/billing/billing.wsdl        # SOAP: WSDL 1.1, inline schema or sibling .xsd files
```

A WSDL's `xsd:import`/`xsd:include` references are followed transitively, resolved relative to
the WSDL — keep the `.xsd` files next to it.

### 2. Declare the service in `application.yaml` (in the `app` module)

```yaml
tao.sandbox:
  services:
    - id: orders                        # directory name in the mock store
      name: Orders                      # dashboard label
      type: REST
      spec: classpath:specs/orders/orders.yaml
      basePath: /orders/v1              # prefixes every path in the spec
      operations:                       # anything not listed answers 501 NOT_IMPLEMENTED
        - operationId: getOrder         # must exist in the spec — a typo fails at startup
          keys: [ "path:orderId" ]

    - id: billing
      name: Billing
      type: SOAP
      wsdl: classpath:specs/billing/billing.wsdl
      path: /soap/billing               # the one endpoint; ?wsdl serves the rewritten contract
      namespaces:                       # prefixes for the xpath keys below (soapenv is built in)
        b: "http://example.org/billing"
      operations:
        - operation: GetInvoice
          keys: [ "xpath:/soapenv:Envelope/soapenv:Body/b:GetInvoiceRequest/b:InvoiceId" ]
```

Key sources: `path:`, `query:`, `header:`, `body:` (dotted JSONPath), `xpath:`.

#### Which keys have to match

| `strategy:` | A mock's filename carries | Use it when |
|---|---|---|
| `ALL` (default) | every declared key | the request is identified by the whole set |
| `FIRST_PRESENT` | exactly one key, the first present in declaration order | one of several identifiers arrives, never both |
| `BEST_MATCH` | **any subset** — each file says what it matches on | some fields decide the answer and the rest are noise |

`BEST_MATCH` is the one to reach for when a call carries more than it means. Given
`?id=1001&name=Laptop&category=Electronics&price=999.99` and all four declared as keys:

```
catalogue/getProduct/name=laptop&category=electronics.json   ← answers whatever id and price are
catalogue/getProduct/id=1001.json                            ← answers a call that only knows the id
catalogue/getProduct/_default.json                           ← answers everything else
```

A file is **eligible** when every `key=value` in its name matches what the request carried. Among
the eligible, **the one naming the most keys wins** — and `_default`, naming none, is simply the
least specific of them rather than a special case. Ties go to the file whose keys come first in
declaration order.

Two files naming different single keys give you *either/or* with a different response each:
`name=laptop.json` and `category=electronics.json` both match, and the declaration order decides
which answers when a request satisfies both.

The trade is that specificity becomes implicit — whether `name=laptop.json` answers depends on what
sits beside it. `POST /__tao/resolve` and every miss diagnostic list the candidates **in the order
they were tried**, so the ranking is always visible rather than inferred.

#### Shortening long key names

A schema that spells things out produces filenames that are hard to read and, on a Windows checkout
of the mock store, long enough to hit the 260-character path limit. Any key may be given a short
name with `as`:

```yaml
operations:
  - operation: GetBusinessRelation
    strategy: BEST_MATCH
    keys:
      - "xpath:/soapenv:Envelope/soapenv:Body/b:Request/b:BusinessRelationId as brid"
      - "xpath:/soapenv:Envelope/soapenv:Body/b:Request/b:BookingCentre as bc"
```

→ `brid=1001&bc=ch100.xml` rather than `businessrelationid=1001&bookingcentre=ch100.xml`.

Real element names remain the default: they need no configuration and a filename that uses them
explains itself. **Aliasing an operation renames its files** — every mock saved under the old name
stops resolving — which is why the next section exists.

#### Mocks that can never answer

A mock can be valid, listed, and unreachable: named for a key the operation does not declare, or
for a subset under a strategy that demands all of them. Nothing about serving reveals it — the file
simply never wins and the default answers instead.

So every mock is checked. Unreachable ones are logged at startup and on reload with the reason,
reported per mock as `reachable` / `unreachableReason` in `GET /__tao/mocks`, and counted as
`unreachableCount` in `GET /__tao/summary`. It is never an error and never refuses a write: a file
that cannot be reached today may be one whose configuration is about to change.

### 3. Add a mock

```
mocks/scenarios/baseline/orders/getOrder/orderid=42.json
mocks/scenarios/baseline/billing/GetInvoice/invoiceid=7.xml     # payload element only, no envelope
```

Filenames are lowercase `key=value` pairs joined by `&`, in declared key order — or ask the server
(`POST /__tao/mocks/name`), which computes the name with the same normalisation the resolver
applies. Add a `_default.<ext>` as the fallback answer for the operation.

Restart (specs define routes, so they are read at boot; mock **files** can be edited freely and
re-read with `POST /__tao/reload`). Startup logs each served route; a wrong operationId, a missing
spec, or an unparsable schema fails the boot with the full list of problems.

### Optional per-mock sidecars

```yaml
# orderid=42.meta.yaml — override what the contract declares
status: 404
contentType: application/problem+json
headers:
  X-Trace: abc123
kind: FAULT          # SOAP: serve this payload inside a fault envelope
```

```xml
<!-- orderid=42.header.xml — SOAP envelope header for this mock;
     a service-wide default comes from the service's responseHeader config -->
<b:Context xmlns:b="http://example.org/billing">...</b:Context>
```

## The request log

Every request the sandbox receives — hit, miss, or rejected — is recorded and browsable at
`GET /__tao/requests`, with each entry carrying the extracted keys, every path tried, and the
request/response bodies. The misses are what the log is usually opened for: a miss entry names
the exact file that would have answered, which turns "my mock is not matching" into "create this
file".

**It is in memory, bounded, and dropped on restart — deliberately.** The log is an observation
aid, not a store of record: persisting it would make the sandbox a system with state worth
backing up, which is the opposite of what it is for. Nothing else depends on it — serving mocks
re-derives everything from each incoming request, so replay works regardless of what the log
remembers.

Two properties tune it:

```yaml
tao.sandbox:
  requestLog:
    capacity: 500          # entries retained; oldest are evicted when the buffer wraps
    maxBodyChars: 32768    # bodies longer than this are truncated (and flagged), not dropped
```

When the buffer has wrapped past a reader's cursor, the log says so (`"mode": "SAMPLED"`) rather
than silently presenting a thinned history as the whole one. Capturing a long load-test run
means raising `capacity` for that instance, not trusting the defaults.

## Writing a mock from a call that missed

The quickest way to add a mock is to let a real call ask for it. Point your application at the
sandbox and run it: any call with no mock gets a loud diagnostic and lands in the request log.
Then `GET /__tao/requests/{id}/draft` answers with the mock that call was asking for —

```json
{
  "mockId":   "baseline/calculator/Divide/inta=10&intb=5.xml",
  "keys":     { "intA": "10", "intB": "5" },
  "exists":   false,
  "skeleton": "<DivideResponse xmlns=\"http://tempuri.org/\">\n  <DivideResult></DivideResult>\n</DivideResponse>",
  "requestBody": "<soapenv:Envelope …>"
}
```

— and nothing is created. Fill the skeleton in, `PUT` it to that `mockId` like any other mock, and
the same call now resolves. Pass the `requestBody` back as `request` on the save and it is kept
beside the payload as `<stem>.request.xml`, which answers the question a reader has months later:
what does a call that lands here actually look like?

The file name comes from the keys the resolver already extracted, normalised the same way — which
is why this beats naming files by hand. **The stored request is never matched against.** Resolution
reads declared keys and nothing else, deliberately: matching a stored request against an incoming
one breaks the first time a correlation id or a timestamp moves.

## Testing what you added

- `GET /__tao/services` — is the operation served, with the keys you meant?
- `POST /__tao/resolve` — paste a request, see extracted keys, every path tried, and what matched.
- `POST /__tao/validate` — check a payload against the contract's schema.
- `GET /__tao/requests` — what the application under test actually called, hits and misses.
