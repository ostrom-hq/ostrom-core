# Decision 0001: the generated OpenAPI document is the only API contract

- **Decided by Nathan (2026-11-13):** Ostrom's API contract has one source of truth, and it is 100 % generated. An
  OpenAPI document is fine only if Ktor generates all of it, with nothing written by hand. The hand-maintained
  contract (`api/openapi.yaml`) and the copies the web console and the site keep go away.
- **Pipeline by Claude (2026-11-13)**, from TeamCode's `presentation/.../docs/ApiDocs.kt`, `ApiDocDsl.kt` and
  `ApiDocsTest`, and the parity audit `clients-structure` rows CS-02 to CS-06.
- **Status:** in progress. The order, the slices done and the slices left are in [Delivery](#delivery).

## The decision in five lines

1. **Source:** the Kotlin `@Serializable` models and typed resources (ostrom-core `api`) and the server's Ktor routes.
   Nothing else describes the API.
2. **Generation:** the server builds its OpenAPI document from its live routing tree with
   `ktor-server-routing-openapi`, as TeamCode does: each route gets its description from a catalogue and its schemas
   from `jsonSchema<T>()` of the model it answers. A route that exists is documented by construction. A route that
   does not exist cannot be documented. A test fails when a mounted route has no description. This needs the server
   on the JVM (ostrom-server docs/decisions/0001).
3. **Distribution:** ostrom-server's CI publishes the document as the npm package `@ostrom-hq/openapi` on GitHub
   Packages, versioned `0.1.<run>` from main. It holds `openapi.json` and its TypeScript types. The server also serves
   the document, as TeamCode serves `/api/openapi.json`.
4. **Consumers:** the web console and the site install a pinned version of the package (a bot bumps it) and generate
   from it: hey-api for the console, the API reference for the site. Their copies (`contract/` in ostrom-web,
   `content/api/openapi.yaml` in ostrom-site) and `contract:sync` are deleted. The CLI and the app call the server
   only through the typed Kotlin client (`run.ostrom:ostrom-client`), never with hand-written paths: `cli -> client
   -> api`, as TeamCode's AGENTS.md says.
5. **What goes:** `api/openapi.yaml`, `x-ostrom-status`, `x-ostrom-kotlin`, `api/contract/served-routes.txt`,
   `api/contract/shapes.json`, `scripts/served-routes.py`, `api-types/` (`contract-shapes.mjs`, `check-served.mjs`,
   `check-limits.mjs`, the `@ostrom-hq/api-types` package), and in ostrom-server `scripts/check-contract.py`,
   `KnownDrifts.kt` and `contract/unobserved-operations.txt` once the observer reads the generated document.

## Why

- Two sources that agree only as far as scripts can check: 185 of 410 schemas had no Kotlin model, routes were matched
  with regular expressions over the server's sources, throttled routes were kept in hand-written lists. Each check
  was another place to forget, such as grantSignUp's undeclared 403, which turned server main red twice.
- `planned` operations made the contract a roadmap: clients generated types for routes that do not exist.
- The copies drift. The console's copy was 13 core commits behind, so its tests and mocks proved agreement with an
  old contract.
- A feature cost four to six ordered PRs (core contract, server, core `served`, then clients); 20 of core's 148
  commits were `X is served` bookkeeping.

## What changes for contributors

- **Planned work** is no longer an operation in a contract. It is a section of the feature's page in `docs/`
  ("What is planned", as TeamCode writes it), and a client hides a feature until `GET /api/v1/capabilities`
  announces its key (`ServerFeatures`). The client never sees the route's types before the server serves it.
- **A new route** is one server PR: the Kotlin model in ostrom-core `api` (released first, as today), then the route,
  its catalogue entry and its tests in ostrom-server. No `served` PR follows.
- **Enums are open on the wire.** Kotlin decodes an unknown value to `Unknown` (`UnknownFallbackEnumSerializer`). The
  console's generated types and Zod schemas accept any string for an enum of two values or more, and screens show an
  unknown value as text (CS-04). A newer server never breaks an older client's page.
- **The contract stays additive** (AGENTS rule 8). The generated document makes a removal visible as a diff of the
  package between two versions.

## Options set aside

- **Keep the hand-written YAML and strengthen its checks.** Rejected by Nathan: this keeps two sources.
- **Generate the YAML from the Kotlin models alone (Zodable, kotlinx-schema).** Models give schemas, not routes,
  statuses or errors, so the paths would stay hand-written.
- **Publish under the existing `@ostrom-hq/api-types` name.** That package is linked to ostrom-core. ostrom-server's
  `GITHUB_TOKEN` cannot publish to it without a manual grant, and its content (types from the hand-written YAML)
  goes away. A new name, `@ostrom-hq/openapi`, also marks the change of source.
- **Ship hey-api's output in the package.** Each consumer picks its own generator options (the console uses Zod
  mini, an SDK with validators, React Query and MSW; the site renders a reference). The package carries the document
  and plain types; each consumer generates in its own build.
- **Merge the repositories first (CS-01).** A separate decision. This pipeline works in both layouts: in one
  repository the package becomes a project dependency and the pin disappears.

## Delivery

The steps, in order:

1. **CS-03:** server CI checks against the pinned core, not core main (ostrom-server#416).
2. **The package:** server CI builds and publishes `@ostrom-hq/openapi` (ostrom-server#417). Until step 4, the
   document in the package is the pinned core's `api/openapi.yaml` as JSON, so consumers switch now and do not change
   again when the source does.
3. **Enum fallbacks** in the console (CS-04).
4. **Generated document:** `ktor-server-routing-openapi` in ostrom-server, after the JVM migration's step 1. This adds
   the operations catalogue, a test that every route is described, a Gradle task that writes the document for the
   package job, and ContractObserver reading that document.
5. **Consumers switch:** ostrom-web installs the package, deletes `contract/` and `contract:sync`, and a bot bumps the
   version. ostrom-site does the same for its API reference.
6. **Core deletes** the YAML and its machinery (the list under point 5 above). The server's docs-truth reads the
   routes from the generated document instead of `served-routes.txt`.
7. **CLI and app** move onto the typed client (CS-06), one command group per PR. Then the CLI's `api-client` module
   and its 83 hard-coded `/api/v1` paths are deleted.
