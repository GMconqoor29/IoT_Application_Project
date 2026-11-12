# AirSense backend

Java 21 / Spring Boot 3.5.7, PostgreSQL 17, Flyway, and MQTT. The existing `contracts/` directory is packaged into the application; schema copies are not maintained in Java. Run commands from the repository root unless indicated.

## Implemented scope

- Registered-device MQTT telemetry/state/metadata ingestion with strict JSON parsing, 4096-byte payload limits, trusted boot profiles, ADC/calibration checks, immutable original evidence, and post-commit QoS 1 receipts.
- Identical replay returns the original receipt. Conflicting IDs preserve the original measurement. Stable terminal rejections are retained in a capped 10,000-record diagnostic store. Missing provisioning/database failures leave delivery pending.
- Public devices/latest/history/statistics endpoints, current severity/recommendations, independent quality and freshness, and mixed-calibration rejection.
- Persisted history snapshot membership with 15-minute cursors, filter binding, 100/500 default/maximum page sizes, and a 31-day range limit. At most 1,000 live snapshots; capacity exhaustion returns 503.
- BCrypt administrators, hashed session/CSRF verification, absolute eight-hour sessions, login rotation, logout, exact Origin checks, login throttling, request limits, and atomic revision-checked threshold edits. The next poll reads the committed rule directly.
- Dashboard conditions and recommendations are calculated on demand using current rules. Notification counters, rule caches, and startup evaluation are removed. External notification delivery remains optional and unimplemented.

The migration account currently owns the schema. Use a separately managed migration account and least-privilege application role for a shared deployment. Keep measurement/profile/calibration triggers enabled. The application uses a single MQTT client identity and one bounded ingestion worker; deploy one backend consumer instance.

## Source structure

The implementation follows [AGENTS.md](../AGENTS.md) and [docs/mqtt-topics.md](../docs/mqtt-topics.md).

| Package | Files and responsibilities |
|---|---|
| ingestion | MqttClientConfig owns lifecycle/readiness and the bounded worker; MqttListener dispatches messages and publishes committed receipts; IngestionService coordinates transactions; IngestionRepository owns SQL; TelemetryValidator checks trusted semantics; IngestionModels contains typed context/results. |
| monitoring | MonitoringController exposes four reads; MonitoringService assembles responses and pagination; MonitoringRepository owns queries/snapshot membership; FreshnessPolicy uses an injected Clock; ConditionEvaluator calculates severity without side effects; MonitoringModels contains records. |
| threshold | ThresholdController validates wire input and invokes AdminGuard; ThresholdService coordinates updates; ThresholdRepository performs revision-checked SQL; ThresholdValidator checks bounds; ThresholdModels contains rules and updates. |
| auth | AuthController owns cookies; AdminGuard extracts request credentials; AuthService handles credentials, throttling, session rotation and CSRF; AuthRepository owns persistence; AuthModels redacts secret-bearing records. |
| common | JsonSupport provides strict/canonical JSON; ContractValidator validates shared schemas; ApiExceptionHandler and RequestLimitsFilter provide safe HTTP behavior; CleanupJob delegates session/snapshot expiry. |
| operations | Provision and Simulator remain explicit operator commands. |

Services and controllers do not access JDBC. Repositories return typed rows; original telemetry stays as a JSON tree to preserve its versioned wire shape. Devices and thresholds return `{ items: [...] }` envelopes. Latest selects one measurement for both display and evaluation. No Flyway migration was changed for this refactor.

## Build and test

For a local JVM with the shared EMQX broker, load the ignored `backend/.env`
into the same PowerShell terminal before starting the application. Spring Boot
does not load this file automatically:

```powershell
./backend/tools/load-env.ps1
mvn -f backend/pom.xml "-Dtest=ContractTest,PolicyTest" verify
java -jar backend/target/airsense-backend-1.0.0.jar
```

Paho uses `ssl://host:8883` for MQTT over TLS (rather than `mqtts://`). The
local environment uses the frontend origin `http://127.0.0.1:5173` and non-Secure
cookies for local HTTP. Use the exact browser origin; deployments require HTTPS
and `SECURE_COOKIE=true`. The existing database password is preserved in `.env`.
The local Compose configuration uses its own Mosquitto service and overrides MQTT
settings; use the JVM commands above for the shared EMQX configuration.

Building and unit tests need no live broker. PostgreSQL/HTTP integration tests
require separate `TEST_DATABASE_*` variables. `RUN_MQTT_TESTS=true` starts isolated
Docker test services; it does not target the shared EMQX broker. The example env
file documents these opt-in variables without enabling integration tests.

```powershell
mvn -f backend/pom.xml package
npm --prefix contracts test
```

Unit tests validate shared fixtures, strict parsing, canonical equality, rule boundaries, freshness, quality/calibration-aware severity, typed response envelopes, and trusted telemetry validation. To run only implementation checks without any service tests: `mvn -f backend/pom.xml "-Dtest=ContractTest,PolicyTest" verify`. Database and HTTP tests require an explicitly supplied disposable PostgreSQL database. They create random isolated schemas and remove only their own schemas afterward:

```powershell
$env:TEST_DATABASE_URL='jdbc:postgresql://localhost:5432/airsense_test'
$env:TEST_DATABASE_USER='airsense'
$env:TEST_DATABASE_PASSWORD='your-test-password'
mvn -f backend/pom.xml test
```

`MqttTest` requires Docker and `$env:RUN_MQTT_TESTS='true'`. It provisions its own PostgreSQL and authenticated Mosquitto containers, starts the Spring Boot application, and checks committed storage receipts, exact and reordered-payload replay, conflicting duplicates, missed-receipt recovery, public latest data, and anonymous broker access rejection. It does not require an existing database. Tests report skipped service tests explicitly when their environment flags are absent.

Run the complete suite, including database/HTTP tests and formatting verification, with disposable Docker databases:

```powershell
./backend/tools/verify-docker.ps1
# If Maven is not on PATH:
./backend/tools/verify-docker.ps1 -MavenPath 'C:/path/to/mvn.cmd'
```

The script removes only its own test container, and Testcontainers cleans up the MQTT test services. Docker Desktop stays running.

## Source formatting

Java production and test source uses the pinned Google Java Format 1.24.0 formatter through Spotless, with two-space indentation and normalized imports/line wrapping. The Maven POM uses SortPom. `backend/.editorconfig` records UTF-8, LF, spaces, and final-newline conventions. Existing Flyway migrations retain their checksums.

```powershell
mvn -f backend/pom.xml spotless:apply
mvn -f backend/pom.xml spotless:check
```

`mvn verify` also checks formatting, so later changes cannot silently drift from the configured style.

## Create the PostgreSQL database

For an existing PostgreSQL server, run [provision/database.sql](provision/database.sql)
once before starting the backend. It creates the `airsense` login and UTF-8 database.
Flyway then creates the tables when the backend starts; Flyway does not create the
database itself.

From the repository root, set `DATABASE_PASSWORD` to your chosen application
password in the current shell, then run:

```powershell
psql -X -h localhost -p 5432 -U postgres -d postgres -W -f backend/provision/database.sql
```

`-W` prompts for the PostgreSQL administrator password, which is separate from
`DATABASE_PASSWORD`. If PostgreSQL tools are not on PATH, use the full path to
`psql.exe`, for example `C:/Program Files/PostgreSQL/17/bin/psql.exe`.

The script can be rerun: it preserves existing databases and login passwords,
and refuses an existing database owned by another role. A newly created login
has no superuser, database-creation, or role-management privileges. It owns the
application database so the current backend can run Flyway migrations. Do not
use `--single-transaction`, because PostgreSQL requires `CREATE DATABASE` outside
a transaction.

For a separate test database, use the same script with an explicit database name:

```powershell
psql -X -h localhost -p 5432 -U postgres -d postgres -W -v database_name=airsense_test -f backend/provision/database.sql
```

An optional `-v database_user=...` selects another application login. Configure
`DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD` to match the result.
When reusing an existing login, supply its existing password to the backend.

Docker Compose already creates the login and database through `POSTGRES_USER`
and `POSTGRES_DB` when its database volume is first initialized. The standalone
bootstrap is not needed for that fresh-volume path. Existing volumes are
preserved; changing those environment variables does not recreate their databases.

## Local Docker deployment

`docker-compose.yml` (at the repository root) defines the local stack (`mosquitto`, `database`, and `backend`). For hardened local profiles, keep ports bound to loopback, use authenticated MQTT within the local Docker network, and only use non-Secure HTTP cookies in local development.

1. Create `backend/secrets/` and a Mosquitto password file named `mosquitto.passwd`. With Mosquitto installed, run `mosquitto_passwd -c backend/secrets/mosquitto.passwd backend`, then `mosquitto_passwd backend/secrets/mosquitto.passwd AIRSENSE-001`. Both prompt for passwords. The second command must omit `-c` so it preserves the backend account. Ensure the container can read this file.
2. Set `DATABASE_PASSWORD` and `MQTT_PASSWORD` in your shell; MQTT_PASSWORD must match the backend broker account. There are no default passwords. `backend/.env.example` lists variable names but is not automatically loaded by root Compose.
3. Run `docker compose up -d --build`. Flyway creates/validates the schema when the backend starts.
4. Explicitly install simulator data into this **test deployment**:

```powershell
Get-Content backend/provision/simulator.sql -Raw | docker compose exec -T database psql -U airsense -d airsense -v ON_ERROR_STOP=1
```

The provisioning script is intentionally non-idempotent: reusing immutable IDs must not silently overwrite evidence. Current thresholds are read directly on the next request; provisioning does not require a cache reload.

5. Query `http://localhost:8080/api/devices` and `http://localhost:8080/actuator/health`. Health separately reports database and broker availability; MQTT-disabled tests report UNKNOWN for that component.

For a local JVM instead, supply `DATABASE_URL` (JDBC URL), `DATABASE_USER`, `DATABASE_PASSWORD`, `MQTT_URI`, `MQTT_USERNAME`, `MQTT_PASSWORD`, `APP_ORIGIN`, and `SECURE_COOKIE`, then run `java -jar backend/target/airsense-backend-1.0.0.jar`.

## Administrator provisioning

After migrations, set `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`, `ADMIN_USERNAME`, and `ADMIN_PASSWORD` in the shell. Use at least 12 password characters and at most 72 UTF-8 bytes. This is an explicit administration command; startup never creates a default account.

```powershell
java -Dloader.main=com.airsense.operations.Provision -cp backend/target/airsense-backend-1.0.0.jar org.springframework.boot.loader.launch.PropertiesLauncher admin
```

Login with JSON `{ "username": "...", "password": "..." }` at `POST /api/auth/login`, including the exact configured Origin. Retain the `airsense_session` cookie and returned `csrfToken`; include `X-CSRF-Token` and Origin on threshold PUT/logout requests. The allowed origin is one exact origin, with no trailing slash. Public monitoring requires no login. Accounts are normalized with trim + lowercase on creation/login.

## Simulator and commissioning

Set `SIMULATOR_MQTT_PASSWORD` to the AIRSENSE-001 broker password and `MQTT_URI` to the test broker. After simulator provisioning:

```powershell
java -Dloader.main=com.airsense.operations.Simulator -cp backend/target/airsense-backend-1.0.0.jar org.springframework.boot.loader.launch.PropertiesLauncher contracts/fixtures/valid/telemetry-normal.json
```

This publishes one fresh synthetic record, waits for STORED, replays the same bytes, and verifies the receipt is unchanged. It is a smoke-test publisher, not firmware or a durable device queue.

Real hardware requires separate device/profile/calibration IDs and commissioning evidence. Insert the device, validated acquisition profile, immutable calibration records, and `(device_id, boot_id, profile_id, location_snapshot)` binding through trusted administration **before** sending each new boot's telemetry. MQTT metadata never provisions these records. New profile settings require a new profile ID and new boot. The backend validates a full profile definition against `adc-profile.schema.json` as well as database bounds.

Gas calibration uses `gasAdcMv / baselineAdcMv` with absolute tolerance 0.001. The implemented dust conversion method is explicitly `linear-adc-mv`: definition fields `equation`, `slope`, `intercept`, `minMv`, `maxMv`, `maxUgM3`; equation is `slope * ADC-pin mV + intercept`, output ug/m3. Its coefficients must incorporate documented analog conditioning and have calibration evidence. Other equations require reviewed implementation; keep the estimate null meanwhile. Simulator coefficients and threshold values are synthetic, not health limits.

## Deployment and operational limits

- For deployment, configure Mosquitto TLS using `deploy/mosquitto-tls.conf.example`, change MQTT_URI to `ssl://broker-host:8883`, and install the issuing CA into the JVM truststore. Hostname verification remains enabled. Use HTTPS termination, set the actual APP_ORIGIN, and leave SECURE_COOKIE=true.
- Keep broker ACLs per device and backend identity; provision broker passwords separately from application users. Never commit `.env`, password files, tokens, private keys, or database dumps.
- The single MQTT worker has 256 pending slots. Saturation or database failure produces no application receipt; the node must keep/retry its durable queue. MQTT PUBACK is not storage confirmation. After commit, publishing failure cannot undo evidence.
- Rejection evidence is capped, so original rejection timestamps are stable only while the outcome remains retained. Unparseable/oversized envelopes do not receive an arbitrary identity receipt. The operational retention cap intentionally allows oldest rejection evidence to expire.
- Metadata observations are stored separately; trusted location/profile history is not overwritten from descriptive messages. Retained online state cannot establish connectivity after restart. A fresh heartbeat or newly received telemetry can establish it.
- Latest polling has no mutable notification state. Adding external delivery later requires its own ordering, recovery, cooldown, and restart tests.
- Canonical comparison sorts object keys, preserves array order and exact decimal numerical meaning, normalizes numerical scale, and compares the entire content rather than only its SHA-256 hash. Original UTF-8 bytes are stored independently. SQL numeric values are unscaled. No source measurement is rounded for display.

## Backup and restore rehearsal

Before the trial, schedule daily custom-format PostgreSQL backups to a restricted directory outside the application volume. Retain all trial data and immutable evidence through assessment; choose an explicit off-host retention policy with the team. Back up deployment configuration and operational logs separately, excluding plaintext credentials from reports.

Using PostgreSQL client tools and environment-based connection settings:

```powershell
pg_dump --format=custom --file=airsense.dump --dbname=airsense
createdb airsense_restore_check
pg_restore --exit-on-error --no-owner --dbname=airsense_restore_check airsense.dump
psql --dbname=airsense_restore_check -c 'SELECT count(*) FROM measurements; SELECT count(*) FROM calibrations; SELECT count(*) FROM acquisition_profiles;'
```

Use a newly named restore-check database; never restore over the running trial database. Compare measurement counts, representative original payload hashes, profiles, calibration IDs, users, and current thresholds against the source. Start an isolated backend against the restored database and verify latest/history/statistics. A documented procedure is not proof of a successful restore; record execution and results in `docs/IMPLEMENTATION_VERIFICATION.md`.
