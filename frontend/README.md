# AirSense dashboard

Local React + Recharts frontend for the scope in `../PLAN.md`, using `../contracts/openapi.json` and the shared fixtures directly. No broker or database connection is made from the browser.

## Source organization

`src/app/App.jsx` composes the workspace and owns navigation/session state. Feature modules live in
`src/features/monitoring`, `src/features/history`, and `src/features/settings`. Monitoring polling is
isolated in `useMonitoring`; pure feature models are tested directly. Shared UI, formatting, and HTTP
transport live in `src/shared`. Synthetic preview data lives separately in `src/fixtures`.

Follow `../AGENTS.md` for frontend and backend conventions. Run `npm run format` to format source,
tests, styles, and configuration; run `npm run format:check` to check without writing.

## Local development

Node.js 20.19+ (or 22.12+) and npm are required.

```sh
cd frontend
npm ci
npm run dev
```

Open http://127.0.0.1:5173. The default is **Live API**. Select **Fixture preview** to inspect the dashboard without a backend. No failed live request silently switches to fixtures.

```sh
npm test
npm run build
npm run preview
```

## API connection and authentication

Vite forwards `/api` to `http://localhost:8080`. Override `AIRSENSE_API_TARGET` in the process environment for a different backend. Production should serve `dist/` and reverse-proxy `/api` on the same HTTPS origin; Vite's development proxy is not part of the production output. Configure the backend's `APP_ORIGIN` to the browser's exact origin. Secure session cookies require HTTPS in production; a local HTTP backend must explicitly use its development cookie configuration (`SECURE_COOKIE=false`). Credentials and CSRF tokens are never stored in browser storage or logs.

Login calls the contract's login endpoint. Sessions live in memory, so refresh requires login again; there is no invented session-inspection endpoint. Threshold PUT sends only `expectedRevision`, `direction`, `warning`, `alert`, `recovery`, and `enabled`, with the returned CSRF token. Rule identity, units and calibration binding remain immutable. A failed save preserves the draft; a conflict offers an explicit replacement with current server settings. Backend authorization remains mandatory.

## Monitoring behavior

- Latest readings poll every five seconds, without overlapping polls. Failed refreshes preserve the last measurement with an error message; freshness continues expiring locally from acquisition time. Device connectivity, sensor quality and environmental condition remain distinct.
- The API exposes only an aggregate condition and string reasons, not per-channel classifications. Cards show channel quality and freshness; the condition panel displays the backend's aggregate classification and reasons. The frontend does not duplicate the rule engine.
- History traverses all opaque cursors at limit 500, using a fixed half-open UTC interval of at most 31 days. Statistics come from the separate API for that complete interval, even if history fails. History and statistics errors are independent. Large ranges can load many records; no silently truncated chart is presented.
- Charts retain nulls and insert gaps for missed reporting intervals and derived calibration changes. No smoothing/interpolation. Statistics calibration conflicts ask the user to narrow the interval. An accessible measurement table supplements the charts.
- Presets use a fixed snapshot ending when selected; use **Refresh range** in History to move the window to now. Custom inputs explicitly use the computer's local timezone; displayed timestamps use the selected timezone. UTC requests preserve `[from,to)` boundaries.
- Dust response and experimental estimate are separate history channels with separate units. The overview uses the estimate only when available. MQ-135 is a project-specific response ratio, not a gas concentration or certified index.

## Fixtures

Imports use the existing API and telemetry JSON files; fixture timestamps are never rewritten. Preview status is evaluated at the recorded acquisition time plus one second, explicitly identified in the UI. History uses the original measurement page and statistics fixture and filters the chosen range; it does not manufacture a continuous signal from the single supplied sample. Preview settings are read-only. Warning/alert scenarios are labeled synthetic extensions of the fixture; values and recommendations exercise presentation only. Actual conditions and advice always come from the live backend.

## Verification

`npm test` checks expiry, quality/null handling, chart gaps, calibration boundaries, range limits, threshold ordering and cursor traversal against shared fixtures. `npm run build` verifies the production bundle. To review manually, exercise all preview scenarios; use History's custom range and dust channels; verify Settings is read-only before login. With the backend running, check sign-in, CSRF-protected edits, revision conflict, logout, API outage, device changes and reconnect. Full hardware/trial acceptance remains under PLAN.md.
