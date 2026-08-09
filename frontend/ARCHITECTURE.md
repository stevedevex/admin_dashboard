# Architecture

A config-driven dashboard for browsing, authoring and validating mock service responses.

## The two seams

Everything else follows from these. Both are barrel-exported directories that the rest of the
application reaches only through their `index.ts`.

```
src/ui/          the design system     — swap the implementation, not the callers
src/api/         all data access       — swap the transport, not the callers
```

**`src/ui/`** wraps whichever component library is in use. Today: Radix primitives styled with
`--tao-*` tokens. Tomorrow: a different library, changed in this directory alone. No file outside
`src/ui/` may import a component library directly.

**`src/api/`** is the only place that performs I/O. It calls the sandbox's control panel over
HTTP, and wire shapes are translated to the domain-neutral types in `types.ts` there and nowhere
else. No file outside `src/api/http/` may `fetch`, and no component may know which transport is
active.

There is no fixture mode. The dashboard describes a running sandbox, so it reads one: a dashboard
that can render data the server never produced agrees with itself and nothing else, and every
disagreement it hides is a bug found later, by someone else. Nothing here recomputes what the
server can state — counts, inheritance, validation verdicts and effective status all arrive as
facts.

If a change to either concern touches a file outside its directory, the seam has leaked.

## Layout

```
src/
  main.tsx                 entry point
  App.tsx                  providers + router

  ui/                      SEAM — design system
    index.ts               the only import surface for components
    tokens.css             --tao-* custom properties (light + dark)
    primitives/            Button, Tag, Panel, Table, Tabs, Dialog, …

  api/                     SEAM — data access
    index.ts               the only import surface for data
    types.ts               domain-neutral DTOs
    client.ts              transport selection
    transport.ts           the contract a transport implements
    http/                  the control panel over HTTP
      request.ts           fetch, problem+json errors, path encoding
      transport.ts         wire shapes translated to domain types

  app/                     application shell
    router.tsx             route table, derived from config/navigation
    providers.tsx          Jotai store, error boundary, toasts
    layout/                AppShell, SideRail, PageHeader

  features/                one directory per navigation destination
    <feature>/
      <Feature>Page.tsx    the route component
      components/          used only by this feature
      hooks/               used only by this feature
      atoms.ts             state scoped to this feature

  config/                  navigation, feature flags, app metadata
  hooks/                   hooks used by two or more features
  state/                   Jotai atoms that are genuinely global
  lib/                     pure functions, no React, no I/O
  styles/                  global stylesheets
```

## Rules

**Dependency direction is one-way.**

```
features  →  ui, api, hooks, state, lib, config
app       →  ui, config, features
ui        →  (nothing from this app)
api       →  lib only
lib       →  (nothing)
```

`ui/` and `lib/` are leaves. If either imports from `features/`, the design is wrong. Enforced by
ESLint `no-restricted-imports`, not by good intentions.

**Code starts local and is promoted, never predicted.** A component lives in
`features/x/components/` until a _second_ feature needs it, and only then moves to `ui/` or a
shared location. Directories named `common/`, `shared/` or `utils/` accumulate everything and
explain nothing — `lib/` holds pure functions with real names, and nothing else.

**Navigation is configuration.** `config/navigation.ts` defines the rail. Adding a page means
adding an entry and a route, never editing `SideRail`. The rail renders whatever it is given,
including disabled placeholders.

**The domain is generic.** Types are `Service`, `Scenario`, `MockFile`, `Schema` — a mock server
for anything. No vocabulary from any particular organisation or product appears in this
repository, in any form, including comments, fixtures and commit history. See `.denyterms.example`
and `npm run guard`.

**One component per file, named for the file.** Co-locate a component's styles as
`Component.module.css` beside it. Tests live beside the code as `Component.test.tsx`.

## Conventions

|            |                                                                               |
| ---------- | ----------------------------------------------------------------------------- |
| Imports    | `@/` alias for `src/`; never `../../..`                                       |
| Components | `PascalCase.tsx`, default-free named exports                                  |
| Hooks      | `useThing.ts`, one hook per file                                              |
| Types      | `type` for shapes, `interface` only for extension                             |
| State      | Jotai. Local `useState` first; an atom only when state outlives one component |
| Styling    | CSS Modules + `--tao-*` tokens. No inline colour values                       |
| Tests      | Vitest + Testing Library, beside the code                                     |
