# AirSense data contract version 1

Status: implementation baseline for review. Current-alert-only scope amendment: 7 November 2026. This implements the shared-contract step in [PLAN.md](../PLAN.md). It does not implement a broker, database, firmware, API server, or dashboard. Real board profiles and calibration records remain hardware commissioning work. All fixture voltages, baselines, conversions, and thresholds are synthetic software examples, not health limits or verified sensor calibration.

## Files and validation

- `schemas/`: JSON Schema draft 2020-12 for telemetry, state, metadata, receipts, and optional commands/results.
- `schemas/adc-profile.schema.json`: mandatory trusted provisioning profile, separate from device-supplied metadata. The supplied example under `fixtures/profiles/` is simulator-only.
- `openapi.json`: OpenAPI 3.1 definition of the planned REST interface, including request/response models and administrator security.
- `fixtures/valid/`, `fixtures/invalid/`: shared MQTT payloads. `fixtures/manifest.json` identifies each expected schema result.
- `fixtures/api/`: dashboard examples, also embedded in OpenAPI.
- `tools/validate.mjs`: reusable schema validators and telemetry semantic checks against a trusted device/calibration registry.
- `tools/check.mjs`: executable contract checks. This is contract evidence, not end-to-end reliability evidence.

From this directory, with Node.js 20 or later and npm available:

```sh
npm ci --ignore-scripts
npm test
```

No running services, credentials, sensors, or database are needed. Production code must enforce the semantic rules below as well as JSON Schema. The Java backend can consume the same schemas and fixtures using a draft 2020-12 validator. The Node helper is a reference for cross-field validation, not a requirement to use Node in production.

## Common wire rules

Payloads are UTF-8 JSON objects, at most 4096 encoded bytes on each MQTT topic, without duplicate object keys. Receivers reject duplicate JSON keys before schema validation; ordinary `JSON.parse` in the reference helper cannot detect these, so this parser-level check belongs in the real ingress implementation. Reject non-finite numbers and unsupported versions. All defined properties are required unless explicitly optional; unknown properties are rejected. Nullable fields are present with `null`, never omitted. Never use zero, an empty string, or the previous measurement to represent failure.

`schemaVersion` is the integer `1`. Device IDs contain uppercase ASCII letters, digits, underscores, and hyphens, at most 64 characters. A topic's device ID must match the payload and the authenticated device's ACL. Provision devices in a trusted registry; incoming metadata cannot register a new device, change authorized voltage limits, or install calibration records.

Timestamps use UTC `YYYY-MM-DDTHH:mm:ss[.SSS]Z`, with at most three fractional digits. They describe acquisition unless explicitly named otherwise. `receivedAt` is assigned once by the backend on first successful ingestion, stored with the measurement, and reused for duplicate receipts. Node payloads never supply it. No service silently substitutes receipt time for unknown acquisition time.

For telemetry, generate a fresh random 8–32 lowercase-hex `bootId` per boot and increment a nonnegative `sequence` per collected record. Start at zero; never reuse a sequence within a boot. Persist each record's identity with its contents. `messageId` is exactly `deviceId:bootId:sequence`, with a canonical decimal sequence. Sequence and uptime use integers no greater than 2^53−1; boot before rollover. `uptimeMs` is acquisition uptime and must not wrap at 32 bits.

Changing a timestamp, quality flag, calibration ID, or value during retransmission creates conflicting content. Reconnect and replay the original record unchanged. A new boot never rewrites queued records from an earlier boot.

## MQTT routing

| Topic suffix under `airsense/{deviceId}/` | Publisher | QoS | Retained | Schema |
|---|---|---|---|---|
| `telemetry` | Node | 1 | No | telemetry |
| `state` | Node or broker Last Will | 1 | Yes | state |
| `metadata` | Node | 1 | Yes | metadata |
| `receipts` | Backend | 1 | No | receipt |
| `commands` | Backend, optional | 1 | No | command |
| `command-results` | Node, optional | 1 | No | command-result |

Subscribe to receipts before publishing queued telemetry. Nodes can publish only their own telemetry/state/metadata/command-results and subscribe only to their own receipts/commands. Backend service ACLs are separate. Require authenticated connections and verified TLS in deployment; never place credentials in payloads. Commands are optional and do not gate the initial monitoring pipeline.

## Telemetry and measurement meaning

Start with `fixtures/valid/telemetry-normal.json`. Its dust estimate is deliberately null: valid raw acquisition does not imply a calibrated particulate estimate.

| Field | Unit or meaning |
|---|---|
| `values.temperatureC` | SHT30 temperature, °C; transport bounds −40 to 125 |
| `values.humidityPct` | SHT30 relative humidity, %RH; 0 to 100 |
| `values.gasRelative` | Dimensionless MQ-135 response ratio, `raw.gasAdcMv / baselineAdcMv` |
| `values.dustEstimateUgM3` | Experimental dust estimate, µg/m³, only with a documented conversion |
| `raw.gasAdcCounts`, `raw.dustAdcCounts` | Integer ADC codes; record the same sample or aggregation used for millivolts |
| `raw.gasAdcMv`, `raw.dustAdcMv` | Calibrated voltage at the conditioned ESP32 ADC pin, mV; not the unscaled sensor output |
| `calibration.gasId`, `calibration.dustId` | Immutable, device-specific registry references or null |

Never add `co2`, `eco2`, TVOC concentration, `pm25`, or AQI fields to v1. MQ-135 does not identify a gas or measure CO₂. The Sharp sensor is not size-selective PM2.5 hardware.

Generic raw schema bounds are 0–65535 counts and 0–10000 mV to allow fault diagnostics across board variants; these are transport bounds, **not electrical safety limits**. Trusted provisioning supplies the actual ADC resolution and usable voltage ranges. Counts cannot exceed that resolution. A voltage above the usable range cannot be marked VALID, but may be retained under INVALID to show clipping. Record the board profile snapshot used for validation alongside stored data. Review analog protection separately before wiring.

The current acquisition baseline is explicitly ESP32-S3, oneshot mode, 12 bits (0–4095), rather than a generic ESP32 default. See [Espressif's S3 ADC guide](https://docs.espressif.com/projects/esp-idf/en/latest/esp32s3/api-reference/peripherals/adc/index.html). No change to telemetry field names or wire version is needed; these are tightened provisioning/semantic rules. A trusted profile requires an immutable `profileId`, `kind`, chip, mode, resolution, and both lower/upper usable millivolt bounds per channel. All numbers must be finite, lower bounds must be less than upper bounds, and upper bounds cannot exceed the 3100 mV nominal envelope for this selected acquisition baseline. The actual commissioned range must be narrower where required by attenuation, calibration, loading, or circuit design. The 150–3000 mV simulator example is synthetic and does not approve a real board. HARDWARE profiles require a commissioning-reference document covering exact board/module variant, GPIO assignments, attenuation, divider/loading, ADC calibration, acquisition timing/aggregation, and bench-verified usable ranges. Changing mode or resolution requires a reviewed profile/schema revision.

Missing, incomplete, or invalid server profiles produce retryable internal `CONFIGURATION_ERROR`. Do not convert this to a terminal rejection receipt or remove the node's queued data: fix provisioning and retry. Metadata cannot repair or bypass it. ADC codes at either rail (0 or 4095) cannot be VALID; preserve them as fault diagnostics with non-valid quality. A code above 4095 is impossible under this profile and is rejected even under INVALID. A valid raw voltage must lie within both provisioned voltage bounds. Broad transport-schema acceptance alone is never hardware acceptance.

### Datasheet alignment and acquisition notes

The supplied module is the ESP32-S3-WROOM-1 family, not the classic ESP32. The development-board identity and full module ordering code still need recording. Its ADC1 signals are GPIO1–10; GPIO4/5 are candidates only after board review. GPIO3 is a strapping pin; GPIO35 is not an S3 ADC input and may be occupied by PSRAM. Module supply is 3.0–3.6 V; sensor 5 V rails must not be connected directly to its analog inputs. Use conditioned signals and compatible I²C pull-ups.

The supplied SHT30 document is SHT3x-DIS August 2016 version 3. It specifies temperature −40 to 125°C and humidity 0–100%RH, matching the wire bounds. Typical SHT30 tolerances are ±3%RH and ±0.3°C under the sheet's stated conditions; decimal display precision is not accuracy. That revision specifies a 2.4–5.5 V supply. Firmware converts `RH=100*SRH/65535` and `T=-45+175*ST/65535`, checking each CRC-8 (polynomial 0x31, initialization 0xFF). The shared SHT30 quality deliberately treats the pair conservatively: either failed CRC invalidates both values.

The supplied Hanwei MQ-135 sheet specifies over 24 hours initial preheating, 5 V ±0.1 V heater/circuit supplies, −10 to 45°C operating conditions, and humidity below 95%RH. Do not mix this with the previous Winsen 48-hour source or label `gasRelative` as its `Rs/Ro`. Commissioning records stabilization readiness; firmware reports WARMING_UP until ready. If reliable environmental context establishes operation outside the gas sensor's specified conditions, mark gas INVALID and retain raw diagnostics; never narrow SHT30's own transport range to the MQ range. Missing environmental context cannot certify MQ accuracy.

For GP2Y1010AU0F, the supplied sheet specifies a 10±1 ms LED pulse cycle, 0.32±0.02 ms pulse width, sampling 0.28 ms after LED activation, 5±0.5 V operating supply, and −10 to 65°C operation. Retain the manufacturer's reference LED circuit and verify timing under network load. Firmware reports INVALID on known timing failure; the backend cannot infer pulse timing from an ADC value. A dust conversion uses sensor-output voltage reconstructed from the documented conditioning circuit, not ADC-pin voltage blindly. Datasheet dust units of mg/m³ require ×1000 for µg/m³; sensitivity and zero-dust offset vary by unit. These data do not establish PM2.5 selectivity or an official AQI.

`gasRelative` must agree with the registered positive baseline within an absolute tolerance of 0.001. Serialize the ratio to sufficient precision for that check. Reference conditions, baseline voltage, ADC conditioning, method, aggregation, and date belong in an immutable calibration record. New calibration means a new ID. Never auto-rebase to make a reading normal. Dust conversion records must specify the equation, input-voltage location, coefficients, valid range, aggregation, evidence, and limitations. The firmware and backend must verify that conversion; merely having an ID is insufficient. Keep the dust estimate null until that work is complete. Negative or out-of-range conversion results produce INVALID and null, while raw evidence remains preserved.

`measuredAt` is the end of the acquisition window represented by the record; `uptimeMs` corresponds to that instant. Before using aggregation in firmware, record window length and reducer in the calibration/acquisition profile; a configuration change requires a new profile and historical snapshot. The first prototype may use one sample per channel per report. Do not mix an old sensor value into a new window.

### Quality and nulls

| Quality | Meaning | Derived values |
|---|---|---|
| VALID | Acquisition usable for its declared purpose | Required temperature/humidity or gas ratio; dust estimate may still be null |
| WARMING_UP | Stabilization incomplete | Null |
| UNCALIBRATED | Required baseline/conversion unavailable | Null |
| INVALID | Reading exists but failed CRC, range, clipping, or timing checks | Null |
| UNAVAILABLE | Sensor missing, timed out, or not acquired | Null |

SHT30 quality covers both temperature and humidity. VALID requires both numbers; every other status requires both null. VALID MQ-135 requires both raw fields, a positive registered baseline, and a numeric gas ratio. Non-valid MQ-135 keeps the ratio null but may include raw diagnostics.

VALID dust means valid raw acquisition; both raw fields are required. A missing dust conversion does not invalidate usable raw dust response: use VALID with a null estimate and null `dustId`. Non-null dust estimates require VALID and a documented registered `dustId`. Charts and rules can use `dustAdcMv` only when dust quality is VALID, or the estimate only when it is also non-null. Use INVALID plus a state error code for dust timing or clipping failure. Do not infer that analog diagnostics can detect every physical sensor failure.

`timeQuality=SYNCED` requires non-null `measuredAt`; `UNSYNCED` requires null. Retain unsynchronized records for diagnostics with sequence/uptime. Exclude them from latest values, time-based history/statistics, and live environmental rules. v1 does not reconstruct acquisition time later. A synchronized acquisition more than 120 seconds ahead of backend time is rejected; this is a proposed clock-tolerance default, not permission to set a wrong device clock.

## Storage receipts and replay

The receipt key is `(deviceId, messageId)`. Database insertion and processing bookkeeping must be atomic. The response `STORED/COMMITTED` is sent only after durable commit. On identical replay, do not reinsert or re-evaluate rules: return the stored receipt with the original `receivedAt`. JSON object key order and whitespace are insignificant; array order and numeric values are significant. Compare all parsed fields using a canonical representation, and preserve original payload bytes separately. Check already-stored identity/content before applying changing registry or clock policies to a duplicate; a committed record must not become a poison message after calibration configuration changes.

Same identity with different content yields `REJECTED/ID_CONFLICT`, with no overwrite. Invalid schema/version, impossible range, invalid calibration, or future clock may produce a terminal rejection. Save a bounded rejection audit record before sending it. Rejection details must not include credentials or unbounded input. The node removes a queued measurement only after a matching STORED receipt, or after durably counting/logging a matching terminal REJECTED receipt. Count rejections separately from queue-overflow drops. Duplicate receipts are harmless. Never delete records based on MQTT PUBACK alone.

Database/broker outages are transient: do not send terminal rejection, leave records pending, and retry with bounded exponential backoff and jitter. v1 has no transient-failure receipt. A malformed payload without a trustworthy identity, an unknown device, or an unauthorized topic gets a bounded diagnostic entry without a receipt. An authorized malformed envelope with an extractable, validated message ID may receive a terminal rejection; never echo arbitrary untrusted IDs onto other device topics. Receipt schema is v1 even when reporting UNSUPPORTED_VERSION for an otherwise identifiable envelope.

Late records remain historical evidence. For latest selection, choose the greatest valid synchronized acquisition timestamp, with `(bootId, sequence)` as a deterministic tie-breaker. Invalid per-sensor values remain null; do not silently substitute older values. Separately show device connectivity and sensor quality. For live rules, require acquisition age no greater than `max(90 seconds, 3 × reportIntervalSec)`, no future acquisition, and progression beyond the device's live-evaluation watermark. Delayed replay cannot replace newer latest data or cause stale notifications. Within a running process, evaluate each newly committed eligible record once using an in-memory watermark. Current dashboard status directly compares fresh valid latest values to current thresholds. Invalid/stale data shows UNKNOWN, not a measured recovery. No persistent alert state or history is required.

Persist records before first publish. Initial queue target is 120 records, subject to measured capacity. Drop oldest on overflow, increment a durable `droppedTotal`, and report the event; no silent loss. Persist queue removal/rejection counters safely across reboot. Rate-limit replay while acquisition continues.

## State, metadata, and Last Will

State is operational information, not telemetry. Publish retained online state after connection and heartbeat at least every reporting interval. Queue depth/capacity are nonnegative integers with `queueDepth <= queueCapacity`. Drop/rejection counters are device-lifetime durable counters; uptime is boot-local. Publish the same boot ID as that session's newly acquired records. State `errors` contains bounded diagnostic codes, such as `DUST_ADC_CLIPPED` or `SHT30_CRC_FAILED`; their detailed evidence belongs in logs.

Prepare retained Last Will at connection with `online=false`, `reason=CONNECTION_LOST`, and `observedAt=null`. The broker cannot fill in the actual disconnect timestamp, and its uptime/counter/sensor snapshots are those from connection setup. Consumers treat those fields as stale snapshots, not live counters. Graceful disconnect uses `GRACEFUL_DISCONNECT`. A trusted backend receive time tracks state arrival separately. Retained online state alone never establishes freshness. After backend restart, retained online is UNKNOWN until new telemetry or a trusted current-session heartbeat arrives; retained offline is useful evidence. Freshness uses acquisition age as well as reception activity so backlog cannot impersonate live measurements.

Metadata describes exact board/chip, firmware, location, sensor models, capabilities, ADC profile, and calibration references. Example metadata is explicitly a simulator profile, not a wiring recommendation. Publish metadata on connection and after an authorized configuration change. Store historical snapshots; metadata is descriptive and cannot override provisioning. Treat conflicts with provisioned sensor/ADC/calibration profiles as configuration faults for review.

## REST interface

`openapi.json` defines the endpoints in PLAN.md. Public monitoring reads do not require administrator login. Writes use a verified administrator session cookie and a matching `X-CSRF-Token`. Login verifies Origin against the deployment allowlist, is throttled, returns a CSRF token, and rotates the session. Deployment uses HTTPS and Secure, HttpOnly, SameSite=Strict cookies. Logout invalidates the server session and expires its cookie. Passwords, cookies, and CSRF tokens never appear in logs.

- History/statistics use half-open acquisition intervals `[from,to)`, `from < to`, maximum 31 days per request. Unsynchronized records remain stored but are absent from these time-based responses.
- Measurement history sorts ascending by `(measuredAt, messageId)`. Opaque cursors bind device, filters, and a first-page ingestion snapshot so concurrent inserts do not corrupt pagination. Reject a reused cursor with different filters. Default limit 100, maximum 500; null cursor means the last page. No historical-alert endpoint is provided.
- Latest before any eligible synchronized record returns HTTP 200 with `measurement=null`, `condition=UNKNOWN`, and independent device status. Unknown registered-resource IDs return 404. Stale/faulted data never becomes NORMAL by default.
- Statistics return min/max/arithmetic mean and valid count for each channel; no valid values means null min/max/mean and count zero. Do not time-weight or interpolate. Use independent channel quality checks; dust raw response can count while dust estimate cannot. Units are fixed per parameter.
- Do not average across different gas baselines or dust conversion IDs. If more than one eligible calibration ID exists for a derived channel in the selected range, return 409 `CALIBRATION_CONFLICT` and ask the client to choose a narrower interval. Raw dust statistics similarly require one compatible ADC/acquisition profile; retain its historical snapshot. Calibration IDs are null for temperature/humidity/raw dust and for empty derived results.
- All API errors use `{code,message,details}`; `details` is an array of `{field,reason}`. Return 400 for validation, 401 for missing/invalid session, 403 for failed role/CSRF/origin checks, 404 for missing resource, 409 for concurrency/calibration conflicts, 429 for login throttling, and 503 for temporary service failure. Do not expose database internals.
- Threshold update requires `expectedRevision`; successful atomic replacement increments revision. Parameter, device, units, and calibration binding are immutable in this edit endpoint. Provisioning creates rule identities. Keep updated_by/updated_at on current threshold rows and log successful configuration changes with sanitized settings. There is no config_audit table or complete queryable edit history; database-backed admin_sessions remains unchanged. Validate finite ordered boundaries, direction, calibration compatibility, and units before committing. Revision is a current-row concurrency token; previous executable threshold revisions are not stored.

Threshold bounds have nullable `low` and `high`. UPPER rules require only high bounds and `recovery.high < warning.high < alert.high`. LOWER rules require only low bounds and `alert.low < warning.low < recovery.low`. RANGE rules require all bounds and `alert.low < warning.low < recovery.low < recovery.high < warning.high < alert.high`. Enter violations inclusively (`>=` upper, `<=` lower); recover only strictly beyond the recovery boundary, inside the band for RANGE. Three enter samples, three clear samples, and 600-second notification cooldown are backend-configuration defaults, not database/API threshold fields or health guidance. They apply to in-memory notification suppression; dashboard warnings appear immediately on fresh threshold violations. Gas/dust rules require compatible calibration/profile references. The synthetic threshold fixture is only a rule-engine test case.

The OpenAPI document describes the interface; semantic boundary ordering, per-parameter units, null/count consistency, cursor validity, and authorization are backend responsibilities beyond structural schema validation. Session authentication and threshold/rule services are subsequent implementation stages.

## Current alerts and restart behavior

The reduced prototype keeps measurement history for graphs but no alert history, persistent rule state, threshold-version table, durable analysis work queue, or notification outbox. Original FR-21 is removed from scope. Backend API version 1.1.0 removes GET /api/alerts and the historical-alert models, plus per-rule enterConsecutive, clearConsecutive, and cooldownSec settings. MQTT schemaVersion remains 1 because wire telemetry/state/receipts are unchanged.

Load current thresholds into a backend cache at startup and refresh on administrator edits. Keep notification counters, hysteresis state, cooldowns, and evaluation watermark only in memory. On restart evaluate the latest synchronized non-future reading once if still fresh; otherwise return UNKNOWN/stale until fresh data arrives. Consecutive counts/cooldowns reset, so a continuing violation may notify again. Duplicate receipt replay does not advance live counters. A crash between storage and evaluation may lose a notification. Dashboard current conditions can be recomputed from stored latest data independently.

External notification delivery is optional and has only bounded in-memory retries; discard obsolete sends. Pending retries disappear on restart and downtime alerts are not replayed. Existing authentication, rejection evidence, and measurement durability requirements remain unchanged.

## Optional commands

Only `SET_REPORT_INTERVAL` is defined in v1, 1–3600 seconds. Local documented configuration is enough for the initial prototype. Commands use an administrator-authorized backend publisher, a unique `commandId`, UTC `issuedAt` and `expiresAt` with expiry after issuance, and are never retained. An unsynchronized node rejects commands requiring expiry evaluation. Apply only before expiry, persist the setting and result, and acknowledge APPLIED with the effective interval. Replay the same command result for the same ID/content; reject conflicting ID reuse. On rejection, return the current effective interval. Publish updated state after success. Do not let commands rewrite backend alert thresholds.

## Versioning and next step

Schemas, fixtures, and this behavior document are one contract. Embedded API telemetry/state/metadata models are checked for equality with their standalone schemas. New fields are rejected by v1: additions or changed meanings therefore require a coordinated version change, updated fixtures, and an explicit migration. Never change calibration contents under an existing ID.

The simulator and Spring Boot consumer are now available in [backend/README.md](../backend/README.md). Canonical v1 comparison uses sorted object keys, ordered arrays, and normalized plain-decimal numbers; `fixtures/canonical.json` is exercised by both Java and JavaScript. Firmware should serialize measured values at useful sensor precision; the backend preserves exact parsed decimal evidence. The parser bounds nesting to 32, numeric token length to 128, and decimal scale to 4096 to prevent unbounded work. Use the shared fixtures in firmware, backend, and dashboard tests. Do not mark the overall P0 milestone complete until its other hardware, roles, service setup, and review evidence exists.
