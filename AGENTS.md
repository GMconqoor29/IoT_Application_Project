# AirSense coding conventions

These instructions apply to this project, including frontend and backend. Preserve existing uncommitted work and keep refactors behavior-preserving unless a behavior change is requested.

## Formatting and readability

- Use UTF-8, LF line endings, a final newline, and spaces rather than tabs.
- Frontend: use Prettier via `npm run format` and `npm run format:check` in `frontend`. Use two-space indentation, single quotes in JavaScript, semicolons, and the configured 100-character print width.
- Backend: use the existing Spotless/Google Java Format configuration via `mvn spotless:apply` and `mvn spotless:check` in `backend`. Do not introduce a competing Java formatter.
- Write one statement per line. Expand control flow and JSX so readers can follow it without unpacking compressed expressions. Use braces for new conditional and loop bodies.
- Prefer descriptive names, small cohesive functions, and comments that explain constraints or intent. Avoid arbitrary abstractions, duplicate state, and comments that merely restate the code.
- Keep unrelated reformatting out of functional changes. Never edit generated output, dependency directories, or applied database migrations.

## Frontend structure

- `src/app`: application composition, navigation, and cross-feature state.
- `src/features/monitoring`: current readings, status evaluation, and polling lifecycle.
- `src/features/history`: history presentation, chart transformations, ranges, and pagination.
- `src/features/settings`: administrator interactions and threshold editing/validation.
- `src/shared/components`: reusable presentation components with no feature orchestration.
- `src/shared/api`: HTTP transport and normalized API errors. Fixtures must not enter the transport layer.
- `src/shared/format.js`: presentation formatting; `src/fixtures`: explicitly selected synthetic preview data.
- Keep feature-only components and pure model functions beside their feature. Use PascalCase component filenames, `use`-prefixed hook names, and named exports for utilities. Use explicit relative imports with extensions; avoid barrel files that conceal dependencies.
- Keep App focused on composition. Put asynchronous polling and cleanup in hooks; keep model functions independent of React and browser state so Node tests can import them.
- Cancel obsolete requests, clean up timers, and avoid overlapping polls. Preserve loading, empty, error, and retry states. Never silently fall back from live API data to fixtures.
- Preserve accessible labels, semantic elements, keyboard controls, status announcements, and the history table when changing UI.
- Keep session/CSRF credentials in memory, never in logs or browser storage. The backend remains responsible for authorization.

## Backend structure

- Organize by feature (`auth`, `monitoring`, `threshold`, `ingestion`, `operations`); reserve `common` for genuinely shared infrastructure.
- Controllers handle HTTP mapping, validation entry points, and response mapping. Services own use cases, business policy, and transaction boundaries. Repositories own SQL and persistence mapping.
- Prefer constructor injection and final dependencies. Keep domain policies separate from HTTP, MQTT, and database concerns.
- Use explicit imports for new code. Use records for immutable transport/value data where appropriate; retain cohesive feature model containers rather than forcing every nested record into its own file.
- Use parameterized SQL, centralized API exceptions, and contract validation. Do not expose secrets or internal exceptions in responses or logs.
- Keep transaction and concurrency guarantees intact, including threshold revisions, ingestion idempotency, and authentication limits. Add a new Flyway migration for schema changes.

## Contracts and verification

- `contracts` is the source of truth for API/schema payloads and shared fixtures. Do not invent endpoints, reinterpret units, or weaken validation during cleanup.
- Preserve null readings, sensor quality, freshness, calibration gaps, half-open history ranges, and pagination. Environmental conditions come from the backend; the frontend must not duplicate its rule engine.
- After frontend changes run `npm run format:check`, `npm test`, and `npm run build` in `frontend`. Review affected flows in the browser when available.
- After backend changes run `mvn spotless:check` and appropriate tests in `backend`; run `mvn test` for broad changes. Integration tests require Docker/Testcontainers; report skips or environmental blockers explicitly.
- Update relevant README architecture/commands when structure changes. Report what changed, what was verified, and any remaining limitations.
